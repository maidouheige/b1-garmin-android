package com.example.b1garmin.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {

    @Query("SELECT * FROM measurements ORDER BY measured_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MeasurementEntity>>

    @Query(
        "SELECT * FROM measurements WHERE measured_at BETWEEN :from AND :to " +
            "ORDER BY measured_at ASC"
    )
    fun observeRange(from: Long, to: Long): Flow<List<MeasurementEntity>>

    @Query(
        "SELECT * FROM measurements WHERE (:mac = '' OR scale_mac = :mac) " +
            "ORDER BY measured_at DESC"
    )
    fun observeByScale(mac: String): Flow<List<MeasurementEntity>>

    @Query(
        "SELECT * FROM measurements WHERE upload_status = 'FAILED' " +
            "ORDER BY measured_at ASC"
    )
    suspend fun failed(): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): MeasurementEntity?

    @Query("SELECT * FROM measurements WHERE dedupe_key = :key LIMIT 1")
    suspend fun byKey(key: String): MeasurementEntity?

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun count(): Int

    @Query("SELECT * FROM measurements ORDER BY measured_at DESC LIMIT 1")
    suspend fun latest(): MeasurementEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: MeasurementEntity): Long

    @Query(
        "UPDATE measurements SET upload_status = 'UPLOADED', error = NULL, " +
            "updated_at = :now WHERE id = :id"
    )
    suspend fun markUploaded(id: Long, now: Long)

    @Query(
        "UPDATE measurements SET upload_status = :status, error = :error, " +
            "skip_reason = COALESCE(:skipReason, skip_reason), updated_at = :now " +
            "WHERE id = :id AND upload_status != 'UPLOADED'"
    )
    suspend fun updateStatusIfNotUploaded(
        id: Long,
        status: String,
        error: String?,
        skipReason: String?,
        now: Long,
    )

    /** 给旧记录补型号：同一台秤（MAC）里 model/brand 为空的，按绑定型号回填 */
    @Query(
        "UPDATE measurements SET model = :model, brand = :brand, updated_at = :now " +
            "WHERE scale_mac = :mac AND (model = '' OR model IS NULL)"
    )
    suspend fun backfillModel(mac: String, model: String, brand: String, now: Long)

    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun delete(id: Long)
}
