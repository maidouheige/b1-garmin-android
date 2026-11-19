package com.example.b1garmin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 应用内自动更新。
 *
 * 流程：请求 Gitee 上的 version.json → 对比版本号 → 有新版就下载 APK → 调起系统安装。
 * 全部放在 IO 线程，不阻塞界面。
 */
object Updater {

    /**
     * 版本清单地址。用 Gitee raw 直链读取一个很小的 JSON，国内访问很快。
     */
    private const val VERSION_URL =
        "https://gitee.com/maidouheige/b1-garmin-android/raw/master/distribution/version.json"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val notes: String,
        val size: Long,
    ) {
        /** 更新说明按分号拆成多条，弹窗里逐条显示更清楚（兼容中英文分号）。 */
        val noteLines: List<String>
            get() = notes.split('；', ';')
                .map { it.trim() }
                .filter { it.isNotBlank() }
    }

    private val client = OkHttpClient()

    /** 版本比较：接口明确关闭重定向之外的跳转也无所谓，raw 直链是稳定的 */
    suspend fun check(currentCode: Int): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(VERSION_URL)
                .header("User-Agent", "B1Garmin-Updater")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("检查更新失败：HTTP ${resp.code}"))
                }
                val body = resp.body?.string().orEmpty()
                val o = JSONObject(body)
                val vc = o.optInt("versionCode", 0)
                if (vc <= currentCode) {
                    return@withContext Result.success(null)   // 已是最新
                }
                Result.success(
                    UpdateInfo(
                        versionCode = vc,
                        versionName = o.optString("versionName", ""),
                        url = o.optString("url", ""),
                        notes = o.optString("notes", ""),
                        size = o.optLong("size", 0L),
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("B1Garmin", "检查更新异常：${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 下载 APK 到应用私有缓存目录。onProgress 回调百分比（-1 表示总大小未知）。
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (pct: Int, downloaded: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "apk").apply { mkdirs() }
            val file = File(dir, "b1-update.apk")
            if (file.exists()) file.delete()
            val req = Request.Builder().url(info.url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("下载失败：HTTP ${resp.code}"))
                }
                val body = resp.body
                    ?: return@withContext Result.failure(Exception("下载失败：空响应"))
                val total = body.contentLength()
                var downloaded = 0L
                var lastReport = 0L
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n)
                            downloaded += n
                            // 每 256KB 或到末尾报一次进度，别刷太快
                            if (downloaded - lastReport >= 256 * 1024 || downloaded == total) {
                                lastReport = downloaded
                                val pct = if (total > 0) (downloaded * 100 / total).toInt() else -1
                                onProgress(pct, downloaded)
                            }
                        }
                    }
                }
            }
            val expect = if (info.size > 0) info.size else -1L
            if (expect > 0 && file.length() != expect) {
                return@withContext Result.failure(
                    Exception("下载不完整：${file.length()} / $expect 字节，请重试")
                )
            }
            Result.success(file)
        } catch (e: Exception) {
            Log.w("B1Garmin", "下载更新异常：${e.message}")
            Result.failure(e)
        }
    }

    /** 调起系统安装界面。需要 REQUEST_INSTALL_PACKAGES 权限 + FileProvider。 */
    fun install(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
