package com.example.b1garmin.scale.xiaomi

import android.content.Context
import com.example.b1garmin.ble.ScaleReader
import com.example.b1garmin.scale.ScaleMeasurement
import com.example.b1garmin.scale.ScaleProtocol
import com.example.b1garmin.scale.ScaleUser

/**
 * 小米体脂秤（S400 / S800）通道。
 *
 * 与 GATT 类秤（B1/A1/荣耀）不同：小米秤被动广播加密 MiBeacon，不建立连接。
 * 协议实现只做一件事——监听广播，把「体重帧 + 高频阻抗帧」拼成一条测量记录。
 *
 * bindkey 由用户在「绑定秤」页填入（32 位 hex，从米家云提取），按 MAC 分别保存。
 * S800 广播没有阻抗，只能读体重；体成分按 BMI 兜底，界面标注实验性。
 */
object XiaomiProtocol : ScaleProtocol {
    override val id = "XIAOMI"

    const val PREF_BINDKEY_PREFIX = "xiaomi_bindkey_"

    /** 按 MAC 取该秤的 bindkey（绑定页保存） */
    fun bindkeyOf(ctx: Context, address: String): String? =
        ctx.getSharedPreferences("b1_scale", Context.MODE_PRIVATE)
            .getString(PREF_BINDKEY_PREFIX + address.uppercase(), null)?.takeIf { it.length >= 32 }

    override suspend fun measure(
        context: Context,
        address: String,
        name: String,
        user: ScaleUser,
        standby: Boolean,
        onLog: (String) -> Unit,
        onLive: (kg: Double, locked: Boolean) -> Unit,
        onImpedance: (ohm: Double) -> Unit,
    ): List<ScaleMeasurement> {
        val bindkey = bindkeyOf(context, address)
        if (bindkey == null) {
            onLog("✗ 这台小米秤没填 bindkey，请在「设置 → 绑定秤」里重新绑定并填写 32 位 BLE KEY")
            return emptyList()
        }

        val reader = ScaleReader(context)
        var weight: Double? = null
        var heartRate: Int? = null
        var lowImp: Double? = null   // 50kHz，随体重帧
        var highImp: Double? = null  // 250kHz，独立帧
        var gotWeightFrame = false

        // 收齐条件：体重帧到了，且要么高频阻抗帧到了（赤足完整），要么短暂等待后确认只有体重（穿袜/S800）
        fun ready(): Boolean = gotWeightFrame && highImp != null

        try {
            // 监听 40 秒；watchMiBeacon 内部在 ready() 满足后会提前结束
            reader.watchMiBeacon(
                address = address,
                bindkeyHex = bindkey,
                timeoutMs = 40000L,
                onLog = onLog,
                onFrame = { p ->
                    p.weightKg?.let {
                        weight = it
                        gotWeightFrame = true
                        onLive(it, true)
                    }
                    p.heartRateBpm?.let { heartRate = it }
                    p.impedanceOhm?.let { imp ->
                        if (p.impedanceOnly) highImp = imp        // 无体重无心率 = 250kHz 高频帧
                        else { lowImp = imp; onImpedance(imp) }   // 随体重帧 = 50kHz 低频帧
                    }
                },
                shouldStop = { ready() },
            )
        } catch (e: Exception) {
            onLog("✗ 小米秤读取出错：${e.message}")
        }

        // 有体重就算一条记录；阻抗优先用高频(250kHz)，没有就用低频(50kHz)
        val imp = highImp ?: lowImp
        return if (weight == null) {
            onLog("这次没读到小米秤的体重（确认秤在广播、bindkey 正确、光脚站上去）")
            emptyList()
        } else {
            onLog("小米秤读数：体重 $weight kg${imp?.let { " · 阻抗 $it Ω" } ?: "（无阻抗，体脂按估算）"}")
            listOf(
                ScaleMeasurement(
                    timestampSec = System.currentTimeMillis() / 1000,
                    weightKg = weight!!,
                    resistanceOhm = imp,
                    fromHistory = false,
                    heartRateBpm = heartRate,
                )
            )
        }
    }
}
