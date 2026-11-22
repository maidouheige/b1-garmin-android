package com.example.b1garmin.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 上传状态 */
object UploadStatus {
    const val PENDING = "PENDING"
    const val UPLOADED = "UPLOADED"
    const val SKIPPED = "SKIPPED"
    const val FAILED = "FAILED"
}

/** 记录来源：本次实时称重 / 秤内历史 */
object MeasurementSource {
    const val LIVE = "LIVE"
    const val HISTORY = "HISTORY"
}

/** 跳过上传的原因 */
object SkipReason {
    const val DUPLICATE_LEDGER = "DUPLICATE_LEDGER"
    const val DUPLICATE_RECENT = "DUPLICATE_RECENT"
}

/**
 * 一次称重的本地记录。
 *
 * 这是旁路镜像：Garmin 仍是上传结果的最终依据，ledger / 锚点仍是去重与校准的依据。
 * 所有体成分字段允许为空，对应「只称到体重、没有阻抗」的情况。
 */
@Entity(
    tableName = "measurements",
    indices = [
        Index(value = ["dedupe_key"], unique = true),
        Index(value = ["measured_at"]),
        Index(value = ["upload_status"]),
        Index(value = ["scale_mac"]),
    ],
)
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** 与现有 ledger 一致：时间戳秒-体重克数 */
    @ColumnInfo(name = "dedupe_key")
    val dedupeKey: String,

    @ColumnInfo(name = "measured_at")
    val measuredAt: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "weight_kg")
    val weightKg: Double,

    @ColumnInfo(name = "bmi")
    val bmi: Double? = null,

    @ColumnInfo(name = "body_fat_pct")
    val bodyFatPct: Double? = null,

    @ColumnInfo(name = "muscle_kg")
    val muscleKg: Double? = null,

    @ColumnInfo(name = "water_pct")
    val waterPct: Double? = null,

    @ColumnInfo(name = "bone_kg")
    val boneKg: Double? = null,

    @ColumnInfo(name = "bmr_kcal")
    val bmrKcal: Int? = null,

    @ColumnInfo(name = "visceral_fat")
    val visceralFat: Int? = null,

    @ColumnInfo(name = "physique_rating")
    val physiqueRating: Int? = null,

    @ColumnInfo(name = "metabolic_age")
    val metabolicAge: Int? = null,

    @ColumnInfo(name = "comp_model")
    val compModel: String? = null,

    @ColumnInfo(name = "impedance_ohm")
    val impedanceOhm: Double? = null,

    @ColumnInfo(name = "heart_rate_bpm")
    val heartRateBpm: Int? = null,

    @ColumnInfo(name = "scale_fat_pct")
    val scaleFatPct: Double? = null,

    @ColumnInfo(name = "height_cm")
    val heightCm: Int? = null,

    @ColumnInfo(name = "age")
    val age: Int? = null,

    @ColumnInfo(name = "sex")
    val sex: String? = null,

    @ColumnInfo(name = "scale_mac")
    val scaleMac: String,

    @ColumnInfo(name = "brand")
    val brand: String,

    @ColumnInfo(name = "model")
    val model: String,

    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "upload_status")
    val uploadStatus: String,

    @ColumnInfo(name = "skip_reason")
    val skipReason: String? = null,

    @ColumnInfo(name = "garmin_sample_pk")
    val garminSamplePk: String? = null,

    @ColumnInfo(name = "error")
    val error: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
