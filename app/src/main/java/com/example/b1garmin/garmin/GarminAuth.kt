package com.example.b1garmin.garmin

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Garmin Connect 认证 —— 对应 garminconnect 0.2.x（新版）的实现。
 *
 * ⚠️ 关键：Garmin 已经废弃了 OAuth1 的 /oauth-service/oauth/preauthorized 端点
 *    （调用会返回 400 Bad Request）。现在的正确流程是：
 *
 *   1. SSO 登录拿到 CAS service ticket（service = 移动端 iOS 服务地址）
 *   2. POST https://diauth.garmin.com/di-oauth2-service/oauth/token
 *      form: client_id / service_ticket / grant_type / service_url
 *      header: Authorization: Basic base64("<client_id>:")
 *      → 直接返回 access_token + refresh_token，无需 OAuth1 签名
 *
 * 注意 ticket 是一次性的，且 service_url 必须与 SSO 登录时的 service 完全一致。
 */
class GarminAuth(private val context: Context, val region: Region = Region.GLOBAL) {
    /** 佳明两个服务器完全独立、数据不互通，接口结构相同，只是域名不同。 */
    enum class Region(val label: String, val domain: String) {
        GLOBAL("国际版", "garmin.com"),
        CHINA("国内版", "garmin.cn"),
    }

    companion object {
        private const val TAG = "GarminAuth"
        private const val PREFS = "garmin_tokens"

        // ---- SSO（移动端 iOS 流程，实测最稳） ----
        const val IOS_CLIENT_ID = "GCM_IOS_DARK"
        private const val SIGNIN_PATH = "/mobile/sso/en_US/sign-in"
        private const val LOGIN_PATH = "/mobile/api/login"
        private const val IOS_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

        // ---- DI OAuth2 token 端点 ----
        private val DI_CLIENT_IDS = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
            "GARMIN_CONNECT_MOBILE_IOS_DI",
        )

        // ---- 原生 App 身份头（API 请求必须带，否则可能被拒） ----
        private const val NATIVE_UA = "GCM-Android-5.23"
        private const val X_GARMIN_UA =
            "com.garmin.android.apps.connectmobile/5.23; ; Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0"

        private val JSON_MEDIA = "application/json".toMediaType()

        /**
         * grant_type 是 Garmin DI OAuth2 的「授权类型标识」协议常量，全球统一用 .com 那个 URI，
         * 不随账号区域变化。实测国区端点 diauth.garmin.cn 也只认这个值：
         *   - 传 .com 常量 → 服务端继续校验 ticket（invalid service ticket provided）= 已接受；
         *   - 传 .cn 版本   → 直接 400 unsupported_grant_type。
         * token 端点、service_url 才按区域走各自域名；这个常量永远固定。
         */
        const val DI_GRANT_TYPE =
            "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"

        fun isGarminMobileLoginUrl(url: String): Boolean =
            url.contains(LOGIN_PATH) || url.contains("/mobile/api/mfa")
    }

    // ---- 按区域动态生成的域名（国际版 .com / 国内版 .cn，接口路径完全一致） ----
    private val domain = region.domain
    private val ssoUrl = "https://sso.$domain"
    private val iosServiceUrl = "https://mobile.integration.$domain/gcm/ios"
    private val diTokenUrl = "https://diauth.$domain/di-oauth2-service/oauth/token"
    private val connectApi = "https://connectapi.$domain"

    /** 两个区的 token 分开存，切换登录不会串号；国际版沿用旧 key，老用户不丢登录。 */
    private val prefsName = if (region == Region.GLOBAL) PREFS else "${PREFS}_${region.name}"

    /** SSO 登录页（WebView 用），service 必须与下面换 token 时一致 */
    fun ssoSigninUrl(): String =
        "$ssoUrl$SIGNIN_PATH?clientId=$IOS_CLIENT_ID&service=" +
                java.net.URLEncoder.encode(iosServiceUrl, "UTF-8")

    data class Tokens(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val refreshExpiresAt: Long,
        val clientId: String = "",
    )

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** 必须带 CookieJar，否则 SSO 登录页设的 cookie 丢失 → 403/429。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .cookieJar(MemCookieJar())
        .followRedirects(true)
        .build()

    /** 内存 cookie 存储（SSO 会话必需）。 */
    private class MemCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) {
                val list = store.getOrPut(url.host) { mutableListOf() }
                cookies.forEach { c ->
                    list.removeAll { it.name == c.name && it.path == c.path }
                    list.add(c)
                }
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
            store[url.host]?.filter { it.matches(url) } ?: emptyList()
        }
    }

    private var logSink: ((String) -> Unit)? = null
    fun setLogSink(f: (String) -> Unit) { logSink = f }
    private fun log(msg: String) {
        Log.d(TAG, msg)
        logSink?.invoke(msg)
    }

    // ====================================================================
    // 1. 密码登录
    // ====================================================================

    /**
     * 密码登录（iOS 移动端 API 流程，与 Python garminconnect 完全一致）。
     *
     * 必须先 GET 登录页拿 cookie 再 POST，否则会被识别为机器人（403/429）。
     * 页面加载后停留 2~5 秒模拟真人操作。
     */
    suspend fun loginWithPassword(email: String, password: String): Result<Tokens> =
        withContext(Dispatchers.IO) {
            try {
                val signinUrl = ssoSigninUrl()
                log("① 请求登录页…")
                val signinResp = client.newCall(
                    Request.Builder().url(signinUrl)
                        .header("User-Agent", IOS_UA)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .get().build()
                ).execute()
                val signinCode = signinResp.code
                signinResp.close()
                log("   登录页 HTTP $signinCode")
                if (signinCode == 429 || signinCode == 403) {
                    return@withContext Result.failure(
                        Exception("Garmin 拒绝访问（HTTP $signinCode），请稍后再试或换网络")
                    )
                }

                val waitMs = (2000L..5000L).random()
                log("② 等待 ${waitMs / 1000} 秒后提交账号…")
                kotlinx.coroutines.delay(waitMs)

                val payload = JSONObject().apply {
                    put("username", email)
                    put("password", password)
                    put("rememberMe", true)
                    put("captchaToken", "")
                }.toString()

                val loginUrl = "$ssoUrl$LOGIN_PATH?clientId=$IOS_CLIENT_ID&locale=en-US&service=" +
                        java.net.URLEncoder.encode(iosServiceUrl, "UTF-8")
                val loginResp = client.newCall(
                    Request.Builder().url(loginUrl)
                        .header("User-Agent", IOS_UA)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Content-Type", "application/json")
                        .header("Origin", ssoUrl)
                        .header("Referer", signinUrl)
                        .post(payload.toRequestBody(JSON_MEDIA))
                        .build()
                ).execute()

                val code = loginResp.code
                val body = loginResp.body?.string().orEmpty()
                loginResp.close()
                log("③ 登录响应 HTTP $code")

                if (code == 429) {
                    return@withContext Result.failure(Exception("登录被限流（429），请等几分钟再试"))
                }

                val json = try { JSONObject(body) } catch (e: Exception) {
                    return@withContext Result.failure(Exception("登录响应不是 JSON（HTTP $code）"))
                }
                val status = json.optJSONObject("responseStatus")?.optString("type").orEmpty()
                log("   responseStatus = $status")

                when (status) {
                    "SUCCESSFUL" -> {
                        val ticket = json.optString("serviceTicketId")
                        if (ticket.isBlank()) {
                            return@withContext Result.failure(Exception("登录成功但没拿到 ticket"))
                        }
                        log("④ 拿到 ticket（长度 ${ticket.length}），换取 token…")
                        exchangeToDiToken(ticket, iosServiceUrl)
                    }
                    "MFA_REQUIRED" -> Result.failure(
                        Exception("该账号开启了两步验证，请用上面的「网页登录」方式")
                    )
                    "INVALID_USERNAME_PASSWORD" -> Result.failure(
                        Exception("账号或密码不对")
                    )
                    "CAPTCHA_REQUIRED" -> Result.failure(
                        Exception("Garmin 要求验证码，请改用「网页登录」")
                    )
                    else -> Result.failure(
                        Exception("登录失败：${status.ifBlank { "未知状态" }}")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "login error", e)
                Result.failure(Exception("登录出错：${e.message}"))
            }
        }

    // ====================================================================
    // 2. WebView 抓到 ticket 后换 token
    // ====================================================================

    /**
     * 用 WebView 抓到的 ticket 换 token。
     *
     * WebView 用的是 ssoSigninUrl()，service 固定为 IOS_SERVICE_URL，
     * 所以这里 service_url 也必须是它，否则会返回 "invalid service ticket"。
     */
    suspend fun exchangeTicket(ticket: String): Result<Tokens> = withContext(Dispatchers.IO) {
        log("用 ticket 换 token（长度 ${ticket.length}）…")
        exchangeToDiToken(ticket, iosServiceUrl)
    }

    // ====================================================================
    // 3. DI OAuth2 token 交换（核心，替代已废弃的 OAuth1）
    // ====================================================================

    private suspend fun exchangeToDiToken(ticket: String, serviceUrl: String): Result<Tokens> =
        withContext(Dispatchers.IO) {
            var lastError = "未知错误"
            for (cid in DI_CLIENT_IDS) {
                try {
                    val form = FormBody.Builder()
                        .add("client_id", cid)
                        .add("service_ticket", ticket)
                        .add("grant_type", DI_GRANT_TYPE)
                        .add("service_url", serviceUrl)
                        .build()

                    val req = Request.Builder()
                        .url(diTokenUrl)
                        .headers(nativeHeaders(mapOf(
                            "Authorization" to basicAuth(cid),
                            "Accept" to "application/json,text/html;q=0.9,*/*;q=0.8",
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Cache-Control" to "no-cache",
                        )))
                        .post(form)
                        .build()

                    client.newCall(req).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            lastError = "HTTP ${resp.code} ${text.take(120)}"
                            Log.w(TAG, "DI $cid -> $lastError")
                            return@use
                        }
                        val j = JSONObject(text)
                        val access = j.optString("access_token")
                        if (access.isBlank()) {
                            lastError = "响应里没有 access_token"
                            return@use
                        }
                        val now = System.currentTimeMillis() / 1000
                        val exp = jwtExp(access) ?: (now + j.optLong("expires_in", 86400))
                        val tokens = Tokens(
                            accessToken = access,
                            refreshToken = j.optString("refresh_token"),
                            expiresAt = exp,
                            refreshExpiresAt = now + 31536000L,
                            clientId = cid,
                        )
                        save(tokens)
                        log("✓ token 获取成功（有效期至 ${java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(exp * 1000))}）")
                        return@withContext Result.success(tokens)
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "DI $cid 异常: $lastError")
                }
            }
            Result.failure(Exception("换取 token 失败：$lastError"))
        }

    // ====================================================================
    // 4. 刷新
    // ====================================================================

    suspend fun refresh(old: Tokens): Result<Tokens> = withContext(Dispatchers.IO) {
        if (old.refreshToken.isBlank()) return@withContext Result.failure(Exception("没有 refresh_token"))
        val cid = old.clientId.ifBlank { DI_CLIENT_IDS[0] }
        try {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", cid)
                .add("refresh_token", old.refreshToken)
                .build()
            val req = Request.Builder().url(diTokenUrl)
                .headers(nativeHeaders(mapOf(
                    "Authorization" to basicAuth(cid),
                    "Accept" to "application/json",
                    "Content-Type" to "application/x-www-form-urlencoded",
                )))
                .post(form).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("刷新失败 HTTP ${resp.code}"))
                }
                val j = JSONObject(text)
                val access = j.getString("access_token")
                val now = System.currentTimeMillis() / 1000
                val tokens = Tokens(
                    accessToken = access,
                    refreshToken = j.optString("refresh_token", old.refreshToken),
                    expiresAt = jwtExp(access) ?: (now + j.optLong("expires_in", 86400)),
                    refreshExpiresAt = old.refreshExpiresAt,
                    clientId = cid,
                )
                save(tokens)
                Result.success(tokens)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ====================================================================
    // 5. 本地存取
    // ====================================================================

    fun load(): Tokens? {
        val a = prefs.getString("access", null) ?: return null
        return Tokens(
            accessToken = a,
            refreshToken = prefs.getString("refresh", "").orEmpty(),
            expiresAt = prefs.getLong("expires", 0),
            refreshExpiresAt = prefs.getLong("refresh_expires", 0),
            clientId = prefs.getString("client_id", "").orEmpty(),
        )
    }

    private fun save(t: Tokens) {
        prefs.edit()
            .putString("access", t.accessToken)
            .putString("refresh", t.refreshToken)
            .putLong("expires", t.expiresAt)
            .putLong("refresh_expires", t.refreshExpiresAt)
            .putString("client_id", t.clientId)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    suspend fun validToken(): Result<Tokens> {
        val t = load() ?: return Result.failure(Exception("未登录"))
        val now = System.currentTimeMillis() / 1000
        return if (t.expiresAt - 120 > now) Result.success(t) else refresh(t)
    }

    // ====================================================================
    // 6. 上传 FIT
    // ====================================================================

    /** 上传 FIT 到 Garmin Connect（体重/身体成分会出现在体重记录里）。 */
    suspend fun uploadFit(fitBytes: ByteArray, fileName: String): Result<String> =
        withContext(Dispatchers.IO) {
            val token = validToken().getOrElse { return@withContext Result.failure(it) }
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", fileName,
                        fitBytes.toRequestBody("application/octet-stream".toMediaType())
                    )
                    .build()
                val req = Request.Builder()
                    .url("$connectApi/upload-service/upload")
                    .headers(nativeHeaders(mapOf("Authorization" to "Bearer ${token.accessToken}")))
                    .post(body).build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) Result.success(text)
                    else Result.failure(Exception("上传失败 HTTP ${resp.code}: ${text.take(200)}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ====================================================================
    // 6.5 体重记录：查 / 删（校准后修正已上传的那条，避免重复堆积）
    // ====================================================================

    /**
     * 查某天已上传的体重记录里，与这次测量对应那条的结果。
     *
     * 必须区分「没找到」和「查询失败」—— 两种情况都当成「没找到」的话，
     * 失败时会跳过删除直接重传，Garmin 里就多出一条同时间的重复体重。
     */
    sealed interface WeighInLookup {
        data class Found(val samplePk: Long) : WeighInLookup
        object NotFound : WeighInLookup
        data class Failed(val message: String) : WeighInLookup
    }

    /**
     * 找出某天已上传的体重记录里，与这次测量对应那条的 `samplePk`（毫秒主键，删除时要用）。
     *
     * 双重匹配：[tsSec] 时间接近（±15 分钟，容住"上传时间≠测量时间"的偏差）
     * 且体重相差不超过 100g，避免误认成同一天的另一条记录。
     */
    suspend fun findWeighInPk(dateStr: String, tsSec: Long, weightKg: Double): WeighInLookup =
        withContext(Dispatchers.IO) {
            val token = validToken().getOrElse {
                return@withContext WeighInLookup.Failed(it.message ?: "未登录")
            }
            try {
                val url = "$connectApi/weight-service/weight/dayview/$dateStr" +
                        "?includeAll=true"
                val req = Request.Builder()
                    .url(url)
                    .headers(nativeHeaders(mapOf("Authorization" to "Bearer ${token.accessToken}")))
                    .get().build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        log("查体重记录失败 HTTP ${resp.code}")
                        return@withContext WeighInLookup.Failed("HTTP ${resp.code}")
                    }
                    val arr = JSONObject(text).optJSONArray("dateWeightList")
                        ?: return@withContext WeighInLookup.NotFound
                    var best: Long? = null
                    var bestGap = Long.MAX_VALUE
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val pk = o.optLong("samplePk", 0L)
                        if (pk <= 0L) continue
                        // 体重要对得上（dayview 里单位是克）
                        val grams = o.optDouble("weight", -1.0)
                        if (grams > 0 && Math.abs(grams - weightKg * 1000) > 100) continue
                        val gap = Math.abs(pk / 1000 - tsSec)
                        if (gap <= 900 && gap < bestGap) { best = pk; bestGap = gap }
                    }
                    best?.let { WeighInLookup.Found(it) } ?: WeighInLookup.NotFound
                }
            } catch (e: Exception) {
                log("查体重记录出错：${e.message}")
                WeighInLookup.Failed(e.message ?: "网络错误")
            }
        }

    /** 删除一条体重记录（按 samplePk 精确定位，不会动同一天的其他记录）。 */
    suspend fun deleteWeighIn(dateStr: String, samplePk: Long): Result<Unit> =
        withContext(Dispatchers.IO) {
            val token = validToken().getOrElse { return@withContext Result.failure(it) }
            try {
                val url = "$connectApi/weight-service/weight/$dateStr" +
                        "/byversion/$samplePk"
                val req = Request.Builder()
                    .url(url).delete()
                    .headers(nativeHeaders(mapOf("Authorization" to "Bearer ${token.accessToken}")))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Result.success(Unit)
                    else Result.failure(Exception("删除失败 HTTP ${resp.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ====================================================================
    // 6.6 账号资料：显示名 + 头像
    // ====================================================================

    data class SocialProfile(
        val displayName: String,
        val fullName: String,
        /** 头像图片地址（相对路径已补全域名）；没有头像为 null */
        val imageUrl: String?,
    )

    /** 拉 Garmin 账号资料（socialProfile），用于首页显示登录账号的头像和名字。 */
    suspend fun fetchSocialProfile(): Result<SocialProfile> = withContext(Dispatchers.IO) {
        val token = validToken().getOrElse { return@withContext Result.failure(it) }
        try {
            val req = Request.Builder()
                .url("$connectApi/userprofile-service/socialProfile")
                .headers(nativeHeaders(mapOf(
                    "Authorization" to "Bearer ${token.accessToken}",
                    "Accept" to "application/json",
                )))
                .get().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("读取账号资料失败 HTTP ${resp.code}"))
                }
                val j = JSONObject(text)
                // 佳明 socialProfile 字段说明：
                //  - fullName 是账号真实姓名 —— 用户明确要显示这个，优先用
                //  - displayName 常是给接口用的 GUID/标识，未必可读
                //  - userName 是佳明用户名，有时是邮箱
                // 目标：优先显示真实姓名 fullName；读不到再退回可读的 displayName/userName；都没有才中性兜底。
                val rawFull = j.optString("fullName").trim()
                val rawDisplay = j.optString("displayName").trim()
                val rawUserName = j.optString("userName").trim()

                fun looksLikeId(s: String): Boolean {
                    if (s.isBlank()) return true
                    if (Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(s)) return true
                    if (Regex("(?i)^[0-9a-f]{32}$").matches(s)) return true
                    if (s.length >= 6 && s.all { it.isDigit() }) return true  // 纯数字账号 ID
                    return false
                }
                fun readableNick(s: String): String? {
                    if (looksLikeId(s)) return null
                    val nick = if (s.contains("@")) s.substringBefore("@") else s  // 邮箱取 @ 前
                    return nick.trim().takeIf { it.isNotBlank() }
                }
                val name = rawFull.takeIf { it.isNotBlank() }
                    ?: readableNick(rawDisplay)
                    ?: readableNick(rawUserName)
                    ?: "Garmin 用户"
                val avatarRaw = listOf(
                    "profileImageUrlLarge", "profileImageUrlMedium", "profileImageUrlSmall"
                ).map { j.optString(it) }
                    .firstOrNull { it.isNotBlank() && !it.equals("null", true) }
                val avatar = avatarRaw?.let { if (it.startsWith("http")) it else connectApi + it }
                log("账号资料：fullName=[${if (rawFull.isBlank()) "空" else "有值"}] displayName=[${rawDisplay.ifBlank { "空" }}] userName=[${rawUserName.ifBlank { "空" }}] → 界面显示=[$name]" +
                    "${if (avatar != null) " · 已获取头像" else " · 未设置头像"}")
                Result.success(
                    SocialProfile(
                        displayName = name,
                        fullName = j.optString("fullName"),
                        imageUrl = avatar,
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "socialProfile error", e)
            Result.failure(e)
        }
    }

    /** 下载头像图片（CDN 地址通常不需要鉴权）。 */
    suspend fun fetchImage(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", NATIVE_UA)
                .get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Result.failure(Exception("下载头像失败 HTTP ${resp.code}"))
                } else {
                    Result.success(resp.body?.bytes() ?: ByteArray(0))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ====================================================================
    // 工具
    // ====================================================================

    private fun nativeHeaders(extra: Map<String, String>): Headers {
        val b = Headers.Builder()
            .add("User-Agent", NATIVE_UA)
            .add("X-Garmin-User-Agent", X_GARMIN_UA)
            .add("X-Garmin-Paired-App-Version", "10861")
            .add("X-Garmin-Client-Platform", "Android")
            .add("X-App-Ver", "10861")
            .add("X-Lang", "en")
            .add("X-GCExperience", "GC5")
            .add("Accept-Language", "en-US,en;q=0.9")
        extra.forEach { (k, v) -> b.set(k, v) }
        return b.build()
    }

    /** OAuth2 Basic 认证：base64("<client_id>:") */
    private fun basicAuth(clientId: String): String =
        "Basic " + Base64.encodeToString("$clientId:".toByteArray(), Base64.NO_WRAP)

    /** 从 JWT 里取 exp（秒）。失败返回 null。 */
    private fun jwtExp(jwt: String): Long? = try {
        val parts = jwt.split(".")
        if (parts.size < 2) null else {
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP))
            val exp = JSONObject(payload).optLong("exp", 0)
            if (exp > 0) exp else null
        }
    } catch (e: Exception) {
        null
    }
}
