package com.example.b1garmin.scale.afu

import android.content.Context
import com.example.b1garmin.ble.ScaleReader
import com.example.b1garmin.ble.ScaleSession
import com.example.b1garmin.scale.ScaleMeasurement
import com.example.b1garmin.scale.ScaleProtocol
import com.example.b1garmin.scale.ScaleUser

/**
 * 阿福/薄荷 B1 通道。
 *
 * 协议全部在 [ScaleSession] 里（FC50 服务 + AF45 帧 + 握手/分块状态机），
 * 这里只负责把上层统一入参翻译过去、把结果翻译成 [ScaleMeasurement]。
 */
object AfuB1Protocol : ScaleProtocol {
    override val id = "B1"

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
        val device = ScaleReader.Device(address = address, name = name)
        return ScaleSession(context, device, onLog)
            .run(
                age = user.age,
                male = user.male,
                targetKg = user.targetKg,
                onLive = onLive,
                onImpedance = onImpedance,
                standby = standby,
            )
            .map {
                ScaleMeasurement(
                    timestampSec = it.timestampSec,
                    weightKg = it.weightKg,
                    resistanceOhm = it.resistanceOhm,
                    fromHistory = it.fromHistory,
                )
            }
    }
}
