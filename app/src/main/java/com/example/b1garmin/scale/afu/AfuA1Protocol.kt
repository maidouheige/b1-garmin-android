package com.example.b1garmin.scale.afu

import android.content.Context
import com.example.b1garmin.ble.A1Session
import com.example.b1garmin.scale.ScaleMeasurement
import com.example.b1garmin.scale.ScaleProtocol
import com.example.b1garmin.scale.ScaleUser

/**
 * 阿福 A1（沃莱/ICOMON FG2508WB）通道。
 *
 * 与 B1 完全隔离：A1 是 FFB0 服务 + AC 帧，连上写一次用户参数就等秤推锁定帧。
 */
object AfuA1Protocol : ScaleProtocol {
    override val id = "A1"

    override suspend fun measure(
        context: Context,
        address: String,
        name: String,
        user: ScaleUser,
        standby: Boolean,
        onLog: (String) -> Unit,
        onLive: (kg: Double, locked: Boolean) -> Unit,
        onImpedance: (ohm: Double) -> Unit,
    ): List<ScaleMeasurement> =
        A1Session(context, address, onLog)
            .run(
                age = user.age,
                male = user.male,
                heightCm = user.heightCm,
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
                    heartRateBpm = it.heartRateBpm,
                    scaleFatPercent = it.scaleFatPercent,
                )
            }
}
