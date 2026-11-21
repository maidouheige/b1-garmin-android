package com.example.b1garmin.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.b1garmin.protocol.A1Protocol
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * A1（沃莱/ICOMON FG2508WB）体脂秤的一次独立会话。
 *
 * 与 B1 的 ScaleSession 完全隔离——A1 协议简单：连上→订阅 FFB2→写一次用户参数，
 * 然后秤连续推 20 字节 AC 帧，锁定（type=0x02）即完成。不需要 B1 那套 ACK/分块状态机。
 *
 * ⚠️ 实验性：真机未验证。所有收到的帧都原样打进日志，方便测试网友回传后对照调试。
 */
@SuppressLint("MissingPermission")
class A1Session(
    private val context: Context,
    private val address: String,
    private val onLog: (String) -> Unit,
) {
    companion object {
        private const val TAG = "A1Session"
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val WRITE_UUID = UUID.fromString(A1Protocol.WRITE_UUID)
    }

    data class Measurement(
        val timestampSec: Long,
        val weightKg: Double,
        val resistanceOhm: Double?,
        val fromHistory: Boolean = false,
        /** 荣耀秤会一并推心率，阿福没有这个字段 */
        val heartRateBpm: Int? = null,
        /** 荣耀秤自己算好的体脂率（偏移 2，/10），华为 App 直接用它，不再重算 */
        val scaleFatPercent: Double? = null,
    )

    /**
     * 跑一次 A1 称重会话。
     * @param onLive 实时体重回调（Binder 线程），locked=true 表示收到锁定帧。
     * @param onImpedance 拿到阻抗时回调一次（Binder 线程），可能为 null（A1 阻抗含义待校准）。
     * @param standby 待机模式：连上后长时间保持连接等人上秤。
     */
    suspend fun run(
        age: Int, male: Boolean, heightCm: Int, targetKg: Double,
        onLive: (kg: Double, locked: Boolean) -> Unit = { _, _ -> },
        onImpedance: (ohm: Double) -> Unit = { _ -> },
        standby: Boolean = true,
    ): List<Measurement> = suspendCancellableCoroutine { cont ->
        val result = LinkedHashMap<String, Measurement>()
        val handler = Handler(Looper.getMainLooper())
        var gatt: BluetoothGatt? = null
        var writeChar: BluetoothGattCharacteristic? = null
        var finished = false
        var subscribed = false
        var sawFrame = false
        var lastFrameAt = System.currentTimeMillis()
        val start = System.currentTimeMillis()
        var lockedAt = 0L
        var committed = false
        var bestWeight = 0.0
        var bestImp: Double? = null

        fun out(msg: String) { Log.d(TAG, msg); onLog("[A1] $msg") }

        fun finish(reason: String) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) {}
            out(reason)
            if (cont.isActive) cont.resume(result.values.toList())
        }

        fun commitIfNeeded() {
            if (committed || bestWeight <= 0) return
            committed = true
            val imp = bestImp
            val m = Measurement(
                timestampSec = System.currentTimeMillis() / 1000,
                weightKg = bestWeight,
                resistanceOhm = imp,
            )
            result[m.timestampSec.toString()] = m
            out("✓ 生成记录：体重 %.2fkg，阻抗 ${imp ?: "无"}".format(bestWeight))
            imp?.let { onImpedance(it) }
        }

        fun onFrame(data: ByteArray) {
            lastFrameAt = System.currentTimeMillis()
            sawFrame = true
            val p = A1Protocol.parse(data) ?: run {
                out("未知帧(非AC): ${A1Protocol.toHex(data.take(20).toByteArray())}")
                return
            }
            out("帧 type=%02X w=${p.weightKg ?: "-"} Z=${p.impedance1}/${p.impedance2}".format(p.type))
            p.weightKg?.let { kg ->
                bestWeight = kg
                onLive(kg, p.locked)
                // 阻抗取第一个频率；数值为 0 或明显异常时视为无
                p.impedance1?.takeIf { it in 100..1500 }?.toDouble()?.let { bestImp = it }
            }
            if (p.locked) {
                lockedAt = System.currentTimeMillis()
                commitIfNeeded()
            }
        }

        val ticker = object : Runnable {
            override fun run() {
                if (finished) return
                val now = System.currentTimeMillis()
                if (committed && now - lastFrameAt > 2000) { finish("✓ A1 会话完成"); return }
                if (lockedAt > 0 && !committed && now - lockedAt > 1500) { commitIfNeeded(); return }
                if (!subscribed && now - start > 12_000) { finish("✗ A1 订阅超时"); return }
                if (standby) {
                    if (!sawFrame && now - start > 60_000) { finish("✗ A1 60 秒无数据"); return }
                    if (now - start > 600_000) { finish("A1 待机超时结束"); return }
                } else {
                    if (sawFrame && now - start > 25_000) { commitIfNeeded(); finish("A1 会话结束"); return }
                    if (!sawFrame && now - start > 25_000) { finish("✗ A1 25 秒无数据"); return }
                }
                handler.postDelayed(this, 300)
            }
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    out("✓ A1 已连接，发现服务…")
                    out("⚠ A1 为实验性支持：阻抗含义待真机校准，体脂仅供参考，请把日志回传")
                    try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (e: Exception) {}
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!finished) finish("A1 断开（status=$status）")
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) { finish("A1 发现服务失败 status=$status"); return }
                val all = g.services.flatMap { it.characteristics }
                val notify = all.firstOrNull {
                    it.uuid.toString().equals(A1Protocol.NOTIFY_UUID, ignoreCase = true)
                } ?: all.firstOrNull {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                }
                writeChar = all.firstOrNull {
                    it.uuid.toString().equals(A1Protocol.WRITE_UUID, ignoreCase = true)
                } ?: all.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }
                if (notify == null || writeChar == null) {
                    finish("✗ A1 没找到通知/写入特征值（可能不是 A1 秤）")
                    return
                }
                out("订阅 FFB2，写特征值 ${writeChar!!.uuid}")
                g.setCharacteristicNotification(notify, true)
                val d = notify.getDescriptor(CCCD)
                if (d == null) { finish("✗ A1 通知没有 CCCD"); return }
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                subscribed = true
                out("A1 订阅完成(status=$status)，写用户参数…")
                val cmd = A1Protocol.buildUserCommand(male, age, heightCm)
                out("用户参数: ${A1Protocol.toHex(cmd)}")
                @Suppress("DEPRECATION")
                writeChar?.value = cmd
                @Suppress("DEPRECATION")
                g.writeCharacteristic(writeChar)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                onFrame(c.value?.copyOf() ?: return)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
            ) { onFrame(value.copyOf()) }
        }

        try {
            val ad = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (ad == null) { finish("✗ 本机蓝牙不可用"); return@suspendCancellableCoroutine }
            out("A1 连接 $address …")
            gatt = ad.getRemoteDevice(address).connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            handler.postDelayed(ticker, 300)
        } catch (e: Exception) {
            Log.e(TAG, "A1 connect error", e)
            finish("✗ A1 连接异常：${e.message}")
        }

        cont.invokeOnCancellation { handler.removeCallbacksAndMessages(null); try { gatt?.close() } catch (e: Exception) {} }
    }
}
