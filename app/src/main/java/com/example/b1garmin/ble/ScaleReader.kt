package com.example.b1garmin.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.b1garmin.protocol.AfuProtocol
import com.example.b1garmin.protocol.A1Protocol
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * B1 体脂秤扫描/状态。
 *
 * 关键教训（2026-11-16 真机）：
 * 1. 蓝牙没开时 adapter.bluetoothLeScanner 是 null，直接 NPE —— 必须先查状态。
 * 2. 不能只用 ScanFilter 过滤 service UUID：部分固件广播包里不带 FC50。
 *    所以先按 FC50 过滤快速找一遍，扫不到再退化成无过滤。
 * 3. BLE 外设只能被一个中心连接。阿福 App 占着连接时我们连不上，
 *    必须先彻底关掉阿福（或关手机蓝牙再开）。
 * 4. **扫到就停**：旧版固定扫满 12 秒才返回，纯属白等——秤通常 1 秒内就出现了。
 */
@SuppressLint("MissingPermission")
class ScaleReader(private val context: Context) {
    companion object {
        private const val TAG = "ScaleReader"
        private val SERVICE = UUID.fromString(AfuProtocol.SERVICE_UUID)

        /** 名字里出现这些关键字就认为是候选秤（含实验性 A1） */
        private val NAME_HINTS = listOf(
            "AFU", "B1", "BH-TZ", "薄荷", "MINT", "SCALE", "TZ-B1",
            "AFU-WL", "WL-TZ-A1", "UAF028", "FG2508",
        )
    }

    data class Device(
        val address: String,
        val name: String,
        val rssi: Int = 0,
        val hasAfuService: Boolean = false,
    ) {
        val display: String
            get() = (name.ifBlank { "（无名设备）" }) + "  " + address +
                    (if (hasAfuService) "  [FC50]" else "") + "  ${rssi}dBm"
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("b1_scale", Context.MODE_PRIVATE)

    // ---------------- 蓝牙状态 ----------------

    fun stateText(): String {
        if (adapter == null) return "本机不支持蓝牙"
        // Android 12+ 未授权 BLUETOOTH_CONNECT 时 getName()/isEnabled() 会抛 SecurityException。
        // 这段在组合期被调用，一旦抛出就是「打开设置页直接闪退」，必须兜住。
        return try {
            if (!adapter.isEnabled) "蓝牙未开启"
            else "蓝牙已开启（${adapter.name ?: "?"}）"
        } catch (e: SecurityException) {
            "蓝牙权限待授权"
        }
    }

    fun isBluetoothReady(): Boolean = try {
        adapter != null && adapter.isEnabled
    } catch (e: SecurityException) {
        false
    }

    fun enableBluetoothIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    fun hasPermission(): Boolean = missingPermissions().isEmpty()

    /** 缺哪个权限（给界面显示用），全都有则返回空列表 */
    fun missingPermissions(): List<String> {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED
        ) need.add("附近的设备(扫描)")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) need.add("附近的设备(连接)")
        return need
    }

    /** 诊断当前能不能扫描，返回一句人话；能扫则返回 null。 */
    fun diagnose(): String? {
        if (adapter == null) return "本机不支持蓝牙（适配器为空）"
        // 权限没给时 isEnabled()/bluetoothLeScanner 都可能抛 SecurityException。
        // 这是启动自动监听的入口，抛出去就是「一开 App 就崩」，先查权限再碰适配器。
        val miss = missingPermissions()
        if (miss.isNotEmpty()) {
            return "缺少蓝牙权限：${miss.joinToString()}，请点「授权蓝牙权限」（若已拒绝需去系统设置里手动开）"
        }
        return try {
            if (!adapter.isEnabled) return "手机蓝牙未开启，请先打开蓝牙"
            if (adapter.bluetoothLeScanner == null) {
                "蓝牙扫描器不可用：系统限制（部分华为/荣耀机型需在「设置-应用-权限」里允许「附近的设备」）"
            } else null
        } catch (e: SecurityException) {
            "蓝牙权限被系统拒绝，请去系统设置里手动允许「附近的设备」"
        }
    }

    fun lastDeviceAddress(): String? =
        prefs.getString("last_addr", null)?.takeIf { !it.startsWith("MOCK:") }

    fun remember(d: Device) {
        if (d.address.startsWith("MOCK:")) return   // 测试期假秤不写进「上次设备」
        prefs.edit().putString("last_addr", d.address).putString("last_name", d.name).apply()
    }

    // ---------------- 绑定秤（型号随 MAC 固定下来，不再每次靠名字猜） ----------------

    /** 已绑定设备：MAC + 名字 + 型号(A1/B1)。没绑定过返回 null。 */
    data class BoundScale(val address: String, val name: String, val model: String) {
        val isA1: Boolean get() = model.equals("A1", ignoreCase = true)
        val display: String get() = (name.ifBlank { "（无名秤）" }) + "  ·  $model"
    }

    /** 按广播名自动判定型号：命中 A1 关键字即 A1，否则 B1。绑定页用它给设备打标。 */
    fun detectModel(name: String): String =
        if (A1Protocol.NAME_HINTS.any { name.contains(it, ignoreCase = true) }) "A1" else "B1"

    fun boundScale(): BoundScale? {
        val addr = prefs.getString("bound_addr", null) ?: return null
        val model = prefs.getString("bound_model", null) ?: return null
        // 正式版没有假设备；若 prefs 里还留着测试版绑定的模拟秤（地址以 MOCK: 开头），
        // 必须当成「未绑定」，否则会一直等一台根本不存在的秤，真实秤再也连不上。
        if (addr.startsWith("MOCK:")) return null
        return BoundScale(addr, prefs.getString("bound_name", "").orEmpty(), model)
    }

    /** 绑定：记住这台秤的 MAC 和型号，并顺带设为「上次设备」让 findScale 优先连它。 */
    fun bind(d: Device, model: String) {
        prefs.edit()
            .putString("bound_addr", d.address)
            .putString("bound_name", d.name)
            .putString("bound_model", model)
            .putString("last_addr", d.address)
            .putString("last_name", d.name)
            .apply()
    }

    fun unbind() {
        prefs.edit()
            .remove("bound_addr")
            .remove("bound_name")
            .remove("bound_model")
            .apply()
    }

    // ---------------- 扫描 ----------------

    private fun toDevice(result: ScanResult): Device? {
        val dev = result.device ?: return null
        val addr = dev.address ?: return null
        val recName = result.scanRecord?.deviceName
        val name = try {
            recName ?: dev.name
        } catch (e: SecurityException) {
            recName
        } ?: ""
        val uuids = result.scanRecord?.serviceUuids?.mapNotNull { it.uuid } ?: emptyList()
        return Device(addr, name, result.rssi, uuids.contains(SERVICE))
    }

    private fun looksLikeScale(d: Device): Boolean =
        d.hasAfuService || d.address == lastDeviceAddress() ||
                NAME_HINTS.any { d.name.contains(it, ignoreCase = true) }

    /**
     * 扫一轮；一旦 [stopOn] 命中就立刻停并返回该设备。
     * 超时仍未命中返回 null。
     */
    private suspend fun scanOnce(
        timeoutMs: Long,
        filterService: UUID?,
        onSeen: (Device) -> Unit,
        stopOn: (Device) -> Boolean,
    ): Device? {
        diagnose()?.let { throw IllegalStateException(it) }
        val scanner = adapter?.bluetoothLeScanner ?: return null

        return suspendCancellableCoroutine { cont ->
            var done = false

            fun stopNow(result: Device?) {
                if (done) return
                done = true
                if (cont.isActive) cont.resume(result)
            }

            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (done) return
                    val d = toDevice(result) ?: return
                    onSeen(d)
                    if (stopOn(d)) {
                        try { scanner.stopScan(this) } catch (e: Exception) {}
                        Log.d(TAG, "命中 ${d.display}，立刻结束扫描")
                        stopNow(d)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "onScanFailed $errorCode")
                    stopNow(null)
                }
            }

            val filters = filterService?.let {
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build())
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                scanner.startScan(filters, settings, cb)
            } catch (e: Exception) {
                Log.e(TAG, "startScan 失败", e)
                stopNow(null)
                return@suspendCancellableCoroutine
            }

            val timer = Runnable {
                if (done) return@Runnable
                done = true
                try { scanner.stopScan(cb) } catch (e: Exception) {}
                if (cont.isActive) cont.resume(null)
            }
            handler.postDelayed(timer, timeoutMs)
            cont.invokeOnCancellation {
                handler.removeCallbacks(timer)
                try { scanner.stopScan(cb) } catch (e: Exception) {}
            }
        }
    }

    /**
     * 找秤（主流程用，尽量快）。
     * 1) 先按 FC50 服务过滤扫一轮，命中立刻返回（通常 1 秒内）
     * 2) 没命中再退化为无过滤扫一轮（某些固件广播里不带服务 UUID）
     */
    suspend fun findScale(
        timeoutMs: Long = 4000,
        onSeen: (Device) -> Unit = {},
    ): Device? {
        // 第一轮按 FC50 过滤，命中就停（实测 B1 广播里带 FC50，通常 1 秒内命中）
        scanOnce(timeoutMs, SERVICE, onSeen) { looksLikeScale(it) }?.let { return it }
        // 兜底：不过滤再扫一轮，但只多等 2 秒，避免整体拖太久
        return scanOnce(timeoutMs + 2000, null, onSeen) { looksLikeScale(it) }
    }

    /** 无过滤固定时长扫描（诊断用，会列出附近所有设备） */
    suspend fun scanAll(
        timeoutMs: Long = 15000,
        onSeen: (Device) -> Unit = {},
    ): List<Device> {
        diagnose()?.let { throw IllegalStateException(it) }
        val scanner = adapter?.bluetoothLeScanner
            ?: throw IllegalStateException("蓝牙扫描器不可用（未知原因）")

        val found = LinkedHashMap<String, Device>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = toDevice(result) ?: return
                val old = found[d.address]
                if (old == null || (old.name.isBlank() && d.name.isNotBlank())) {
                    found[d.address] = d
                    onSeen(d)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "onScanFailed $errorCode")
            }
        }

        return suspendCancellableCoroutine { cont ->
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            try {
                scanner.startScan(null, settings, cb)
            } catch (e: Exception) {
                Log.e(TAG, "startScan 失败", e)
                cont.resumeWith(Result.failure(e))
                return@suspendCancellableCoroutine
            }

            val stop = Runnable {
                try { scanner.stopScan(cb) } catch (e: Exception) {}
                if (cont.isActive) cont.resume(rank(found.values.toList()))
            }
            handler.postDelayed(stop, timeoutMs)
            cont.invokeOnCancellation {
                handler.removeCallbacks(stop)
                try { scanner.stopScan(cb) } catch (e: Exception) {}
            }
        }
    }

    private fun rank(list: List<Device>): List<Device> = list.sortedWith(
        compareByDescending<Device> { it.hasAfuService }
            .thenByDescending { d -> NAME_HINTS.any { d.name.contains(it, true) } }
            .thenByDescending { it.rssi }
    )
}
