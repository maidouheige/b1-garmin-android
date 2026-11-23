package com.example.b1garmin.data

import android.content.Context
import com.example.b1garmin.protocol.BodyComp
import com.example.b1garmin.scale.ScaleMeasurement
import com.example.b1garmin.scale.ScaleModel
import kotlinx.coroutines.flow.Flow

/**
 * 本地记录仓库。
 *
 * 写入策略是「旁路、只增不减」：即使这里抛异常，也不能影响称重和 Garmin 上传。
 * 调用方负责捕获异常。
 */
class MeasurementRepository private constructor(
    private val dao: MeasurementDao,
) {

    fun observeRecent(limit: Int): Flow<List<MeasurementEntity>> = dao.observeRecent(limit)

    fun observeRange(from: Long, to: Long): Flow<List<MeasurementEntity>> =
        dao.observeRange(from, to)

    fun observeByScale(mac: String): Flow<List<MeasurementEntity>> = dao.observeByScale(mac)

    suspend fun failed(): List<MeasurementEntity> = dao.failed()

    suspend fun byId(id: Long): MeasurementEntity? = dao.byId(id)

    suspend fun count(): Int = dao.count()

    suspend fun latest(): MeasurementEntity? = dao.latest()

    /** 只删本地记录，不动 Garmin */
    suspend fun delete(id: Long) {
        if (id > 0) dao.delete(id)
    }

    /**
     * 写入一条记录。相同 dedupe_key 只保留一行。
     *
     * 已 UPLOADED 的行不会被降级；如果这次是 UPLOADED，则把旧行提升为 UPLOADED。
     */
    suspend fun record(
        rec: ScaleMeasurement,
        est: BodyComp.Estimate?,
        heightCm: Int,
        age: Int,
        sex: String,
        model: ScaleModel?,
        scaleMac: String,
        status: String,
        skipReason: String? = null,
        error: String? = null,
    ): Long {
        val key = keyOf(rec)
        val now = System.currentTimeMillis()
        val existing = dao.byKey(key)
        if (existing != null) {
            when {
                status == UploadStatus.UPLOADED -> {
                    if (existing.uploadStatus != UploadStatus.UPLOADED) {
                        dao.markUploaded(existing.id, now)
                    }
                }
                status == UploadStatus.SKIPPED -> {
                    dao.updateStatusIfNotUploaded(
                        existing.id, UploadStatus.SKIPPED, null, skipReason, now,
                    )
                }
                status == UploadStatus.FAILED -> {
                    dao.updateStatusIfNotUploaded(
                        existing.id, UploadStatus.FAILED, error, null, now,
                    )
                }
            }
            return existing.id
        }

        val entity = MeasurementEntity(
            dedupeKey = key,
            measuredAt = rec.timestampSec * 1000L,
            createdAt = now,
            weightKg = rec.weightKg,
            bmi = est?.bmi,
            bodyFatPct = est?.bodyFatPercent,
            muscleKg = est?.muscleMassKg,
            waterPct = est?.percentHydration,
            boneKg = est?.boneMassKg,
            bmrKcal = est?.basalMet,
            visceralFat = est?.visceralFatRating,
            physiqueRating = est?.physiqueRating,
            metabolicAge = est?.metabolicAge,
            compModel = est?.model,
            impedanceOhm = rec.resistanceOhm ?: est?.resistanceOhm,
            heartRateBpm = rec.heartRateBpm,
            scaleFatPct = rec.scaleFatPercent,
            heightCm = heightCm,
            age = age,
            sex = sex,
            scaleMac = scaleMac,
            brand = model?.brand.orEmpty(),
            model = model?.displayName.orEmpty(),
            source = if (rec.fromHistory) MeasurementSource.HISTORY else MeasurementSource.LIVE,
            uploadStatus = status,
            skipReason = skipReason,
            error = error,
            updatedAt = now,
        )

        val id = dao.insertIgnore(entity)
        if (id != -1L) return id
        // 并发情况下可能已被别的协程插入，复用已有行
        return dao.byKey(key)?.id ?: -1L
    }

    /** 给某台秤的历史空型号记录补上型号（老版本写入时用了错误的 key 导致型号为空） */
    suspend fun backfillModel(mac: String, model: String, brand: String) {
        if (mac.isBlank() || model.isBlank()) return
        dao.backfillModel(mac, model, brand, System.currentTimeMillis())
    }

    suspend fun markUploaded(id: Long) {
        if (id > 0) dao.markUploaded(id, System.currentTimeMillis())
    }

    suspend fun markFailed(id: Long, error: String?) {
        if (id > 0) {
            dao.updateStatusIfNotUploaded(
                id, UploadStatus.FAILED, error, null, System.currentTimeMillis(),
            )
        }
    }

    companion object {
        /** 与现有 ledger 完全一致：时间戳秒-体重克数 */
        fun keyOf(rec: ScaleMeasurement): String =
            "${rec.timestampSec}-${Math.round(rec.weightKg * 1000)}"

        fun get(context: Context): MeasurementRepository =
            MeasurementRepository(AppDatabase.get(context).measurementDao())
    }
}
