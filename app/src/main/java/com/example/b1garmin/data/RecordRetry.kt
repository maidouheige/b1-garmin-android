package com.example.b1garmin.data

import android.content.SharedPreferences
import com.example.b1garmin.garmin.GarminAuth
import com.example.b1garmin.protocol.BodyComp
import com.example.b1garmin.protocol.FitWriter
import java.text.SimpleDateFormat
import java.util.Date

/** 本地记录 → FIT 所需的体成分；关键字段缺失时返回 null */
fun MeasurementEntity.toEstimateOrNull(): BodyComp.Estimate? {
    val bmiV = bmi ?: return null
    val fat = bodyFatPct ?: return null
    val water = waterPct ?: return null
    val bone = boneKg ?: return null
    val muscle = muscleKg ?: return null
    val bmr = bmrKcal ?: return null
    return BodyComp.Estimate(
        weightKg = weightKg,
        bmi = bmiV,
        bodyFatPercent = fat,
        percentHydration = water,
        boneMassKg = bone,
        muscleMassKg = muscle,
        basalMet = bmr,
        physiqueRating = physiqueRating ?: 0,
        metabolicAge = metabolicAge ?: 0,
        visceralFatRating = visceralFat ?: 0,
        model = compModel ?: "unknown",
        resistanceOhm = impedanceOhm,
    )
}

/**
 * 补传一条 FAILED 记录。
 *
 * 手动补传和后台 Worker 共用这一份逻辑：ledger 命中时直接标记成功，
 * 否则用本地字段重新生成 FIT 上传并回写状态。
 */
object RecordRetry {

    suspend fun retry(
        row: MeasurementEntity,
        auth: GarminAuth,
        records: MeasurementRepository,
        ledger: SharedPreferences,
        onLog: (String) -> Unit = {},
    ): Result<Unit> {
        if (row.uploadStatus == UploadStatus.UPLOADED) return Result.success(Unit)

        if (ledger.getBoolean(row.dedupeKey, false)) {
            runCatching { records.markUploaded(row.id) }
            onLog("· 本地记录 #${row.id} 已在 Garmin 上，直接标记为已上传")
            return Result.success(Unit)
        }

        val est = row.toEstimateOrNull()
            ?: return Result.failure(IllegalStateException("缺少体成分数据，无法补传"))

        return try {
            val ts = row.measuredAt / 1000L
            val up = auth.uploadFit(FitWriter.build(est, ts), "weight_${ts}.fit")
            if (up.isSuccess) {
                ledger.edit().putBoolean(row.dedupeKey, true).apply()
                runCatching { records.markUploaded(row.id) }
                val whenLocal = SimpleDateFormat("MM-dd HH:mm").format(Date(row.measuredAt))
                val bf = row.bodyFatPct?.let { "%.1f%%".format(it) } ?: "-"
                onLog("✓ 补传成功：$whenLocal ${row.weightKg}kg 体脂 $bf")
                Result.success(Unit)
            } else {
                val err = up.exceptionOrNull()?.message ?: "未知错误"
                runCatching { records.markFailed(row.id, err) }
                onLog("✗ 补传失败：$err")
                Result.failure(Exception(err))
            }
        } catch (e: Exception) {
            runCatching { records.markFailed(row.id, e.message) }
            onLog("✗ 补传异常：${e.message}")
            Result.failure(e)
        }
    }
}
