package com.example.b1garmin.xiaomi

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 米家云授权：扫码登录 → 拿到 serviceToken → 调用米家云 API 拉取体脂秤的 BLE beaconkey (bindkey)。
 *
 * 说明：这不是官方 OAuth，而是模拟米家 App 客户端登录（sid=xiaomiio），
 * 与开源工具 Xiaomi-cloud-tokens-extractor 完全同一套协议。接口随时可能变动，
 * 属于实验性功能；失败时会抛出带原因的异常，由 UI 层提示用户改用手动填 KEY。
 *
 * 全部网络与加密逻辑放在这里，不依赖 Android UI，方便单元测试。
 */
object XiaomiCloudAuth {
    private const val TAG = "XiaomiCloudAuth"
    private const val ACCOUNT = "https://account.xiaomi.com"

    // 模拟米家 App 身份（与 extractor 一致）
    private const val SID = "xiaomiio"
    private const val CALLBACK = "https://sts.api.io.mi.com/sts"
    private const val QS = "%3Fsid%3Dxiaomiio%26_json%3Dtrue"
    private const val APP_UA = "APP/com.xiaomi.mihome APPV/10.5.201"

    private val client = OkHttpClient()

    /** 登录中间态，扫码成功后拿到 ssecurity / serviceToken，才能调云 API。 */
    data class Session(
        val userId: String?,
        val ssecurity: String,
        val serviceToken: String,
    )

    data class ScaleDevice(
        val did: String,
        val name: String,
        val mac: String,
        val model: String,
    )

    data class LoginTicket(
        val qrImageUrl: String,
        val loginUrl: String,
        val longPollingUrl: String,
        val timeoutSec: Long,
    )

    /** 每个客户端实例随机生成一次身份（避免硬编码设备指纹被风控）。 */
    private class Identity {
        val agent: String
        val deviceId: String

        init {
            val letters = ('a'..'z')
            val caps = ('A'..'E')
            val randText = (1..18).map { letters.random() }.joinToString("")
            val agentId = (1..13).map { caps.random() }.joinToString("")
            agent = "$randText-$agentId $APP_UA"
            deviceId = (1..6).map { letters.random() }.joinToString("")
        }

        fun accountHeaders(): Map<String, String> = mapOf(
            "User-Agent" to agent,
            "Content-Type" to "application/x-www-form-urlencoded",
            "Cookie" to "sdkVersion=accountsdk-18.8.15; deviceId=$deviceId",
        )
    }

    // ------------------------------------------------------------------
    // 密码登录（主力方式）
    // ------------------------------------------------------------------

    /**
     * 密码登录：用用户名 + MD5 密码调 serviceLoginAuth2，拿到 ssecurity / location，再取 serviceToken。
     * 与扫码登录最终产出的 Session 结构一致，后续 API 调用共用同一套。
     * 注意：小米已对密码登录加图形验证码，本方法遇到 captcha 会返回带原因的错误提示。
     */
    suspend fun loginWithPassword(
        username: String,
        password: String,
    ): Result<Session> = withContext(Dispatchers.IO) {
        try {
            val identity = Identity()
            // Step 1: serviceLogin → 拿 _sign
            val signUrl = "$ACCOUNT/pass/serviceLogin?sid=$SID&_json=true"
            val signReq = Request.Builder().url(signUrl)
                .headers(identity.accountHeaders().toHeaders())
                .header("Cookie", "userId=${username}")
                .get().build()
            val signText = client.newCall(signReq).execute().use { it.body?.string().orEmpty() }
            val signJ = JSONObject(stripPrefix(signText))
            val sign = signJ.getString("_sign")
            // Step 2: serviceLoginAuth2 → 拿 ssecurity / location
            val authUrl = "$ACCOUNT/pass/serviceLoginAuth2"
            val passwordHash = password.toByteArray(Charsets.UTF_8).run {
                java.security.MessageDigest.getInstance("MD5").digest(this).map { "%02x".format(it) }.joinToString("").uppercase()
            }
            val authForm = FormBody.Builder()
                .add("sid", SID)
                .add("hash", passwordHash)
                .add("callback", CALLBACK)
                .add("qs", QS)
                .add("user", username)
                .add("_sign", sign)
                .add("_json", "true")
                .build()
            val authResp = client.newCall(Request.Builder().url(authUrl)
                .headers(identity.accountHeaders().toHeaders()).post(authForm).build())
                .execute()
            val authText = authResp.body?.string().orEmpty()
            val authJ = JSONObject(stripPrefix(authText))
            if (authJ.has("captchaUrl")) {
                return@withContext Result.failure(Exception("需要图形验证码（${authJ.optString("captchaUrl")}），请改用「扫码登录」或手动填 KEY。"))
            }
            if (authJ.has("notificationUrl")) {
                return@withContext Result.failure(Exception("需要 2FA 邮箱验证码（${authJ.optString("notificationUrl")}），请改用「扫码登录」或手动填 KEY。"))
            }
            val ssecurity = authJ.optString("ssecurity", "").trim()
            if (ssecurity.isEmpty() || ssecurity.length < 5) {
                return@withContext Result.failure(Exception("密码登录失败，请检查账号密码是否正确。"))
            }
            val userId = authJ.optString("userId", null)
            val location = authJ.optString("location", "").trim()
            if (location.isEmpty()) {
                return@withContext Result.failure(Exception("登录未返回跳转地址，服务异常。"))
            }
            val token = fetchServiceToken(location)
            Result.success(Session(userId, ssecurity, token))
        } catch (e: Exception) {
            Log.w(TAG, "loginWithPassword failed", e)
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    // 二维码登录
    // ------------------------------------------------------------------

    /** 第 1 步：请求登录二维码，返回二维码图片地址与长轮询地址。 */
    suspend fun requestQrLogin(): Result<LoginTicket> = withContext(Dispatchers.IO) {
        try {
            val ts = System.currentTimeMillis()
            val url = "$ACCOUNT/longPolling/loginUrl?" +
                "_qrsize=480&qs=$QS&callback=$CALLBACK&_hasLogo=false&" +
                "sid=$SID&serviceParam=&_locale=zh_CN&_dc=$ts"
            val req = Request.Builder().url(url).get().build()
            val text = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val j = JSONObject(stripPrefix(text))
            val qr = j.getString("qr")
            val ticket = LoginTicket(
                qrImageUrl = if (qr.startsWith("http")) qr else ACCOUNT + qr,
                loginUrl = j.getString("loginUrl"),
                longPollingUrl = j.getString("lp"),
                timeoutSec = j.optLong("timeout", 180L),
            )
            Result.success(ticket)
        } catch (e: Exception) {
            Log.w(TAG, "requestQrLogin failed", e)
            Result.failure(e)
        }
    }

    /** 下载二维码图片字节（给 UI 的 ImageView 显示）。 */
    suspend fun downloadQr(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.failure(Exception("下载二维码 HTTP ${resp.code}"))
                Result.success(resp.body?.bytes() ?: byteArrayOf())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 第 2 步：长轮询等待用户用米家 App 扫码确认。
     * onTick 每轮回调一次，用于 UI 更新倒计时/状态；shouldStop 返回 true 可主动取消。
     */
    suspend fun waitForScan(
        ticket: LoginTicket,
        onTick: (waitedSec: Long) -> Unit = {},
        shouldStop: () -> Boolean = { false },
    ): Result<Session> = withContext(Dispatchers.IO) {
        val identity = Identity()
        try {
            val start = System.currentTimeMillis()
            var lastResp: okhttp3.Response? = null
            while (!shouldStop()) {
                val waited = (System.currentTimeMillis() - start) / 1000
                if (waited > ticket.timeoutSec) {
                    return@withContext Result.failure(Exception("扫码超时（${ticket.timeoutSec}秒），请重试"))
                }
                onTick(waited)
                try {
                    val req = Request.Builder().url(ticket.longPollingUrl)
                        .headers(identity.accountHeaders().toHeaders()).get().build()
                    val resp = client.newCall(req).execute()
                    lastResp?.close()
                    lastResp = resp
                    if (resp.isSuccessful) {
                        val text = resp.body?.string().orEmpty()
                        val j = JSONObject(stripPrefix(text))
                        // 扫码成功后：userId / ssecurity / location 都在 JSON 里
                        val ssecurity = j.getString("ssecurity")
                        val location = j.getString("location")
                        val userId = j.optString("userId", null)
                        val token = fetchServiceToken(location)
                        return@withContext Result.success(Session(userId, ssecurity, token))
                    }
                } catch (e: Exception) {
                    // 长轮询未扫码时会 4xx/超时，忽略继续轮询
                    if (shouldStop()) break
                }
                kotlinx.coroutines.delay(1000)
            }
            Result.failure(Exception("已取消扫码"))
        } catch (e: Exception) {
            Log.w(TAG, "waitForScan failed", e)
            Result.failure(e)
        }
    }

    /** 第 3 步：访问 location 跳转，从响应 Cookie 中取 serviceToken。 */
    private fun fetchServiceToken(location: String): String {
        val url = if (location.startsWith("http")) location else ACCOUNT + location
        // 与官方工具一致：只带 content-type，让服务器通过 Set-Cookie 下发 serviceToken
        val req = Request.Builder().url(url)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .get().build()
        client.newCall(req).execute().use { resp ->
            val token = resp.headers("Set-Cookie")
                .flatMap { it.split(";") }
                .firstOrNull { it.trim().startsWith("serviceToken=") }
                ?.substringAfter("=")
                ?.trim()
            if (!token.isNullOrBlank()) return token
            // 有些账号 token 在响应体 JSON 里，兜底再找一次
            val text = resp.body?.string().orEmpty()
            try {
                val j = JSONObject(stripPrefix(text))
                if (j.has("serviceToken")) return j.getString("serviceToken")
            } catch (_: Exception) { }
        }
        throw Exception("无法获取 serviceToken，请重试或用手动方式填 KEY")
    }

    // ------------------------------------------------------------------
    // 米家云 API（RC4 加密）
    // ------------------------------------------------------------------

    /**
     * 小米体脂秤常见型号关键词（用于过滤米家设备列表）。
     * 注意：米家云端 S400 并不叫 S400，而是 yunmai.scales.ms103/ms104，
     * S800 是 xiaomi.scales.ms116；一代/二代是 xiaomi.scale / mibcs 等。
     * 参考 Xiaomi-cloud-tokens-extractor 与 OHealthDeviceBridge 的设备表。
     */
    private val SCALE_MODEL_KEYWORDS = listOf(
        "scale",              // 覆盖 xiaomi.scale / yunmai.scales.* / smart scale
        "ms103", "ms104", "ms116", "ms110", "ms107",
        "s400", "s800", "mibcs", "mibfs",
        "体脂秤", "体重秤",
    )

    /** 拉取账号下的体脂秤设备（过滤掉其他米家设备）。 */
    suspend fun listDevices(session: Session, region: String = "cn"): Result<List<ScaleDevice>> =
        withContext(Dispatchers.IO) {
            try {
                val homes = apiCall(session, region, "/v2/homeroom/gethome",
                    """{"fg": true, "fetch_share": true, "fetch_share_dev": true, "limit": 300, "app_ver": 7}""")
                val out = mutableListOf<ScaleDevice>()
                val homeList = homes.optJSONObject("result")?.optJSONArray("homelist")
                if (homeList != null) {
                    for (i in 0 until homeList.length()) {
                        val home = homeList.getJSONObject(i)
                        val homeId = home.optLong("id")
                        val owner = home.optLong("uid", session.userId?.toLongOrNull() ?: 0L)
                        val data = """{"home_owner": $owner,"home_id": $homeId,  "limit": 200,  "get_split_device": true, "support_smart_home": true}"""
                        val devResp = apiCall(session, region, "/v2/home/home_device_list", data)
                        val infos = devResp.optJSONObject("result")?.optJSONArray("device_info") ?: continue
                        for (j in 0 until infos.length()) {
                            val d = infos.getJSONObject(j)
                            val did = d.optString("did", "")
                            val name = d.optString("name", "")
                            val model = d.optString("model", "")
                            // 只保留体脂秤型号
                            val isScale = SCALE_MODEL_KEYWORDS.any { keyword ->
                                name.contains(keyword, ignoreCase = true) ||
                                    model.contains(keyword, ignoreCase = true)
                            }
                            if (!isScale) continue
                            out += ScaleDevice(
                                did = did,
                                name = name,
                                mac = d.optString("mac", ""),
                                model = model,
                            )
                        }
                    }
                }
                Result.success(out.distinctBy { it.did })
            } catch (e: Exception) {
                Log.w(TAG, "listDevices failed", e)
                Result.failure(e)
            }
        }

    /** 按设备 did 取 BLE beaconkey（32 位 hex）。 */
    suspend fun getBeaconKey(session: Session, did: String, region: String = "cn"): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val data = """{"did":"$did","pdid":1}"""
                val resp = apiCall(session, region, "/v2/device/blt_get_beaconkey", data)
                val key = resp.optJSONObject("result")?.optString("beaconkey", "").orEmpty()
                if (key.length >= 32) Result.success(key) else Result.failure(Exception("该设备未返回 BLE KEY"))
            } catch (e: Exception) {
                Log.w(TAG, "getBeaconKey failed", e)
                Result.failure(e)
            }
        }

    /** 执行一次 RC4 加密的米家云 API 调用。 */
    private fun apiCall(session: Session, region: String, path: String, data: String): JSONObject {
        val host = if (region == "cn") "https://api.io.mi.com/app" else "https://$region.api.io.mi.com/app"
        val url = host + path
        val nonce = generateNonce(System.currentTimeMillis())
        val signedNonce = signedNonce(nonce, session.ssecurity)

        val params = linkedMapOf("data" to data)
        params["rc4_hash__"] = encSignature(url, "POST", signedNonce, params)
        for ((k, v) in params.entries.toList()) params[k] = rc4Encrypt(signedNonce, v)
        params["signature"] = encSignature(url, "POST", signedNonce, params)
        params["ssecurity"] = session.ssecurity
        params["_nonce"] = nonce

        // 关键：对照 Xiaomi-cloud-tokens-extractor，加密参数必须放在 URL query string，
        // 而不是 POST body（requests.post(url, params=fields)）。放 body 会被服务器判 401。
        val urlBuilder = url.toHttpUrlOrNull()?.newBuilder()
            ?: throw Exception("云 API URL 非法")
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val finalUrl = urlBuilder.build()

        val identity = Identity()
        // extractor 用空 body 的 POST（参数全在 query 里）
        val emptyBody = FormBody.Builder().build()
        val req = Request.Builder().url(finalUrl)
            .headers(mapOf(
                "Accept-Encoding" to "identity",
                "User-Agent" to identity.agent,
                "Content-Type" to "application/x-www-form-urlencoded",
                "x-xiaomi-protocal-flag-cli" to "PROTOCAL-HTTP2",
                "MIOT-ENCRYPT-ALGORITHM" to "ENCRYPT-RC4",
                "Cookie" to (
                    "userId=${session.userId}; serviceToken=${session.serviceToken}; " +
                    "yetAnotherServiceToken=${session.serviceToken}; locale=en_GB; " +
                    "timezone=GMT+02:00; is_daylight=1; dst_offset=3600000; channel=MI_APP_STORE"
                    )
            ).toHeaders())
            .post(emptyBody)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = try { resp.body?.string().orEmpty().take(300) } catch (_: Exception) { "" }
                Log.w(TAG, "apiCall ${resp.code} $path body=$errBody")
                throw Exception("云 API HTTP ${resp.code}")
            }
            val text = resp.body?.string().orEmpty()
            val decrypted = rc4Decrypt(signedNonce, text)
            return JSONObject(decrypted)
        }
    }

    // ------------------------------------------------------------------
    // 加密原语（严格对照 Xiaomi-cloud-tokens-extractor，便于单测）
    // ------------------------------------------------------------------

    /** nonce = 8 随机字节 + 4 字节分钟时间戳(big endian)，再 base64。 */
    internal fun generateNonce(millis: Long): String {
        val buf = ByteArray(12)
        Random.nextBytes(buf, 0, 8)
        val minutes = (millis / 60000L).toInt()
        buf[8] = (minutes ushr 24).toByte()
        buf[9] = (minutes ushr 16).toByte()
        buf[10] = (minutes ushr 8).toByte()
        buf[11] = minutes.toByte()
        return b64(buf)
    }

    /** signed_nonce = base64(sha256(base64decode(ssecurity) + base64decode(nonce))) */
    internal fun signedNonce(nonce: String, ssecurity: String): String {
        val raw = b64dec(ssecurity) + b64dec(nonce)
        return b64(sha256(raw))
    }

    /** 加密参数签名：SHA1(METHOD & path & k=v... & signed_nonce)，base64。 */
    internal fun encSignature(url: String, method: String, signedNonce: String, params: Map<String, String>): String {
        val path = url.substringAfter("com", "").replace("/app/", "/")
        val parts = mutableListOf(method.uppercase(), path)
        params.forEach { (k, v) -> parts += "$k=$v" }
        parts += signedNonce
        return b64(sha1(parts.joinToString("&").toByteArray(Charsets.UTF_8)))
    }

    /** RC4：以 base64decode(password) 为 key，先空转 1024 字节，再加密 payload。 */
    internal fun rc4Encrypt(password: String, payload: String): String {
        val rc4 = Rc4(b64dec(password))
        rc4.crypt(ByteArray(1024)) // 丢弃前 1024 字节
        return b64(rc4.crypt(payload.toByteArray(Charsets.UTF_8)))
    }

    internal fun rc4Decrypt(password: String, payload: String): String {
        val rc4 = Rc4(b64dec(password))
        rc4.crypt(ByteArray(1024))
        return String(rc4.crypt(b64dec(payload)), Charsets.UTF_8)
    }

    /** 标准 RC4 (KSA + PRGA)。 */
    internal class Rc4(key: ByteArray) {
        private val s = IntArray(256)
        private var i = 0
        private var j = 0

        init {
            for (n in 0..255) s[n] = n
            var j0 = 0
            for (n in 0..255) {
                j0 = (j0 + s[n] + (key[n % key.size].toInt() and 0xFF)) and 0xFF
                val tmp = s[n]; s[n] = s[j0]; s[j0] = tmp
            }
        }

        fun crypt(input: ByteArray): ByteArray {
            val out = ByteArray(input.size)
            for (n in input.indices) {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val tmp = s[i]; s[i] = s[j]; s[j] = tmp
                out[n] = (input[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
            }
            return out
        }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun b64dec(s: String): ByteArray = Base64.getDecoder().decode(s)
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b)
    private fun stripPrefix(s: String) = s.removePrefix("&&&START&&&").trim()
    private fun Map<String, String>.toHeaders() = okhttp3.Headers.Builder().apply {
        forEach { (k, v) -> add(k, v) }
    }.build()
}
