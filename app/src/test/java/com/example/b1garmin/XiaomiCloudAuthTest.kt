package com.example.b1garmin

import com.example.b1garmin.xiaomi.XiaomiCloudAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 验证米家云授权里的加密原语。
 * RC4 测试向量由独立的 Python RC4 实现生成（先空转 1024 字节，与米家协议一致），
 * 不依赖 JVM 的 JCE（JVM 默认不提供 ARC4）。
 */
class XiaomiCloudAuthTest {

    private fun b64dec(s: String) = Base64.getDecoder().decode(s)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    @Test
    fun rc4_matches_known_vectors_with_skip1024() {
        // key="0123456789abcdef0123456789abcdef", payload={"did":"abc","pdid":1}
        assertEquals(
            "5WPa2XeT7NUrq3cXFaz2pDkzmuKAZA==",
            XiaomiCloudAuth.rc4Encrypt(
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "{\"did\":\"abc\",\"pdid\":1}"
            )
        )
        // key=32个f, payload=hello xiaomi
        assertEquals(
            "8vU0qWRNvhP3TEnl",
            XiaomiCloudAuth.rc4Encrypt("ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmY=", "hello xiaomi")
        )
        // key="0123456789abcdef", payload=20个x
        assertEquals(
            "5jnGyGvJro8ysWxNQfb+uCgvwKA=",
            XiaomiCloudAuth.rc4Encrypt("MDEyMzQ1Njc4OWFiY2RlZg==", "x".repeat(20))
        )
    }

    @Test
    fun rc4_decrypt_roundtrip() {
        val password = b64("AaBbCcDdEeFfGgHh0123456789ABCDEF".toByteArray())
        val plain = """{"result":{"beaconkey":"11223344556677889900aabbccddeeff"}}"""
        val cipher = XiaomiCloudAuth.rc4Encrypt(password, plain)
        assertEquals(plain, XiaomiCloudAuth.rc4Decrypt(password, cipher))
    }

    @Test
    fun rc4_known_vector_decrypts_back() {
        assertEquals(
            "hello xiaomi",
            XiaomiCloudAuth.rc4Decrypt("ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmY=", "8vU0qWRNvhP3TEnl")
        )
    }

    @Test
    fun signed_nonce_uses_sha256_of_decoded_bytes() {
        val nonce = b64("12345678".toByteArray() + byteArrayOf(0, 0, 0, 42))
        val ssecurity = b64("ssecurity-bytes-here".toByteArray())
        val raw = b64dec(ssecurity) + b64dec(nonce)
        val expected = b64(java.security.MessageDigest.getInstance("SHA-256").digest(raw))
        assertEquals(expected, XiaomiCloudAuth.signedNonce(nonce, ssecurity))
    }

    @Test
    fun nonce_has_12_bytes_and_correct_minutes() {
        val nonce = XiaomiCloudAuth.generateNonce(1_700_000_000_000L)
        val raw = b64dec(nonce)
        assertEquals(12, raw.size)
        val minutes = (1_700_000_000_000L / 60000L).toInt()
        assertEquals((minutes ushr 24).toByte(), raw[8])
        assertEquals((minutes ushr 16).toByte(), raw[9])
        assertEquals((minutes ushr 8).toByte(), raw[10])
        assertEquals(minutes.toByte(), raw[11])
    }

    @Test
    fun enc_signature_is_sha1_base64_with_expected_order() {
        val url = "https://api.io.mi.com/app/v2/device/blt_get_beaconkey"
        val signed = "SIGNED_NONCE_PLACEHOLDER"
        val params = linkedMapOf("data" to "{\"did\":\"x\",\"pdid\":1}")
        // 与生产代码一致：url.split("com")[1].replace("/app/", "/")
        val path = url.substringAfter("com", "").replace("/app/", "/")
        val parts = mutableListOf("POST", path)
        params.forEach { (k, v) -> parts += "$k=$v" }
        parts += signed
        val expected = b64(java.security.MessageDigest.getInstance("SHA-1")
            .digest(parts.joinToString("&").toByteArray(Charsets.UTF_8)))
        assertEquals(expected, XiaomiCloudAuth.encSignature(url, "POST", signed, params))
        assertEquals("/v2/device/blt_get_beaconkey", path)
        assertTrue(expected.isNotBlank())
    }
}
