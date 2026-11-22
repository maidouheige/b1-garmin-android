package com.example.b1garmin.ui.records

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.b1garmin.data.MeasurementEntity
import com.example.b1garmin.data.toEstimateOrNull
import com.example.b1garmin.protocol.FitWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出本地记录：
 * - garmin_import.csv：Garmin 网页「导入数据」接受的 Body 格式（体重/BMI/体脂）
 * - b1garmin.fit：全指标 FIT，一次写入多条 weight_scale 记录
 *
 * 只负责生成字节和分享；保存到本地由界面走系统文件选择器。
 */
object RecordsExport {

    enum class Format(val label: String) {
        CSV("CSV"),
        FIT("FIT"),
        BOTH("两者"),
    }

    data class FilePart(
        val name: String,
        val mime: String,
        val bytes: ByteArray,
    )

    data class Result(
        val files: List<FilePart>,
        val csvCount: Int,
        val fitCount: Int,
        val skipped: Int,
    ) {
        val summary: String
            get() = buildString {
                if (csvCount > 0) append("CSV $csvCount 条")
                if (csvCount > 0 && fitCount > 0) append(" · ")
                if (fitCount > 0) append("FIT $fitCount 条")
                if (skipped > 0) append("（$skipped 条缺体成分未进 FIT）")
            }
    }

    /** 按格式生成文件内容；没有可导出的数据时抛异常。 */
    fun build(rows: List<MeasurementEntity>, format: Format): Result {
        val sorted = rows.filter { it.weightKg > 0 }.sortedBy { it.measuredAt }
        if (sorted.isEmpty()) throw IllegalStateException("没有可导出的记录")

        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val files = ArrayList<FilePart>()
        var csvCount = 0
        var fitCount = 0

        if (format == Format.CSV || format == Format.BOTH) {
            val (text, count) = buildCsv(sorted)
            if (count > 0) {
                files.add(
                    FilePart(
                        "garmin_import_$stamp.csv",
                        "text/csv",
                        text.toByteArray(Charsets.UTF_8),
                    )
                )
                csvCount = count
            }
        }

        if (format == Format.FIT || format == Format.BOTH) {
            val items = sorted.mapNotNull { row ->
                row.toEstimateOrNull()?.let { it to row.measuredAt / 1000L }
            }
            if (items.isNotEmpty()) {
                files.add(
                    FilePart(
                        "b1garmin_$stamp.fit",
                        "application/octet-stream",
                        FitWriter.buildMany(items),
                    )
                )
                fitCount = items.size
            }
        }

        if (files.isEmpty()) throw IllegalStateException("没有可导出的数据")
        return Result(files, csvCount, fitCount, sorted.size - fitCount)
    }

    /** 把生成的文件写到 cache，再调起系统分享面板。 */
    fun share(context: Context, result: Result) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val uris = ArrayList<Uri>()
        result.files.forEach { part ->
            val file = File(dir, part.name).apply { writeBytes(part.bytes) }
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享导出文件"))
    }

    /** Garmin 导入格式：第一行 Body，第二行表头，之后每行一条。 */
    private fun buildCsv(rows: List<MeasurementEntity>): Pair<String, Int> {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sb = StringBuilder("Body\r\nDate,Weight,BMI,Fat\r\n")
        var count = 0
        rows.forEach { row ->
            val bmi = row.bmi ?: run {
                val h = row.heightCm?.takeIf { it > 0 } ?: return@forEach
                row.weightKg * 10000.0 / (h.toDouble() * h)
            }
            sb.append(dateFmt.format(Date(row.measuredAt))).append(',')
                .append(fmt(row.weightKg, 2)).append(',')
                .append(fmt(bmi, 2)).append(',')
                .append(row.bodyFatPct?.let { fmt(it, 1) } ?: "")
                .append("\r\n")
            count++
        }
        return sb.toString() to count
    }

    private fun fmt(value: Double, digits: Int): String =
        String.format(Locale.US, "%.${digits}f", value).trimEnd('0').trimEnd('.')
}
