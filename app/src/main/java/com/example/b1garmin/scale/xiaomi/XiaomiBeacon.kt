package com.example.b1garmin.scale.xiaomi

import android.util.Log
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 小米体脂秤 MiBeacon 广播解析（S400 / S800 通用的加密广播帧）。
 *
 * 协议来源：ESPHome `xiaomi_body_scale` 组件源码（2026.11 版）逐字节移植，
 * 与 Gadgetbridge 的 MiScaleS400 解密器、mnm-matin/miscale 三套独立实现互相印证。
 *
 * 关键点（全部已由 ESPHome 源码确认，不是猜测）：
 * - 24 字节广播走 0xFE95 服务；frame control 必须含 0x48（has_data + encrypted）
 * - 布局：[0..1]控制 [2..3]设备ID(LE) [4]帧计数 [5..16]密文(12B) [17..19]payload计数 [20..23]MIC(4B)
 * - AES-CCM：key=bindkey(16B)，tag=4B(32bit)，AAD={0x11}(1B)
 * - nonce(12B) = MAC 反转6B + frame[2..4](3B) + frame[17..19](3B)
 * - 明文：[0..1]object id(LE) [2]长度(9) [3]profile id [4..7]压缩uint32(LE) [8..]时间戳
 * - 压缩 uint32：weight=低11位(/10 kg)，心率=(>>11)&0x7F(+50)，阻抗=(>>18)(/10 Ω)
 *
 * 说明：小米秤是「被动广播」，不建立 GATT 连接；每台秤需要用户从米家云提取的
 * 32 位 hex bindkey 才能解密。S800 的广播只能拿到体重，阻抗走加密 GATT（无公开方案），
 * 因此 S800 只能给体重 + 按 BMI 估算的体脂，界面会明确标注实验性。
 */
object XiaomiBeacon {
    private const val TAG = "XiaomiBeacon"

    const val FE95_SERVICE_UUID = "0000fe95-0000-1000-8000-00805f9b34fb"

    const val OBJECT_S400 = 0x6E16
    const val OBJECT_S200 = 0x4E16
    const val OBJECT_SIZE = 9

    // 加密帧固定 24 字节
    private const val FRAME_SIZE = 24
    private const val DEVICE_ID_POS = 2
    private const val FRAME_COUNT_POS = 4
    private const val CIPHER_POS = 5
    private const val CIPHER_SIZE = 12
    private const val COUNTER_POS = CIPHER_POS + CIPHER_SIZE   // 17
    private const val TAG_POS = COUNTER_POS + 3                 // 20
    private const val TAG_SIZE = 4
    private const val FRAME_ENCRYPTED_DATA = 0x48
    private val AUTH_DATA = byteArrayOf(0x11)

    /** 一次解析出来的原始测量（体重/心率/阻抗可为空，取决于这一帧带了什么） */
    data class Parsed(
        val objectId: Int,
        val profileId: Int,
        val weightKg: Double?,
        val heartRateBpm: Int?,
        val impedanceOhm: Double?,
        /** true = 这帧只有阻抗（250kHz 高频帧，无体重无心率） */
        val impedanceOnly: Boolean,
        val frameCount: Int,
    )

    /** S400 各变体的产品 ID（小端广播里的 uint16）。命中即认为是小米体脂秤。 */
    val S400_PRODUCT_IDS = setOf(
        0x30D9, 0x3BD5, 0x48CF, 0x4B05,   // ESPHome 文档：S400 / S400 白 / 蓝 / Pro
        0x30D8, 0x4B06,                    // 常见邻近变体，宽松纳入
    )
    /** S800（xiaomi.scales.ms116）产品 ID */
    val S800_PRODUCT_IDS = setOf(0x51E2)
    /** S200（只有体重）产品 ID */
    val S200_PRODUCT_IDS = setOf(0x45C9, 0x4C04, 0x4DCB)

    fun isXiaomiScale(productId: Int): Boolean =
        productId in S400_PRODUCT_IDS || productId in S800_PRODUCT_IDS || productId in S200_PRODUCT_IDS

    fun isS800(productId: Int): Boolean = productId in S800_PRODUCT_IDS

    /** 从 6 字节 MAC 字符串解析成 64 位整数（用于 nonce 的反转字节） */
    fun macToLong(mac: String): Long =
        mac.split(":", "-", ".").filter { it.length == 2 }
            .fold(0L) { acc, s -> (acc shl 8) or (s.toLong(16) and 0xFF) }

    /**
     * 解析一帧 24 字节加密广播。bindkey 为 32 位 hex（16 字节）。
     * 返回 null 表示这帧无效/不是加密帧/解密失败（密钥错或 MAC 错）。
     */
    fun parse(frame: ByteArray, mac: String, bindkeyHex: String): Parsed? {
        if (frame.size != FRAME_SIZE) return null
        if (frame[0].toInt() and FRAME_ENCRYPTED_DATA != FRAME_ENCRYPTED_DATA) return null

        val key = hexToBytes(bindkeyHex) ?: return null
        if (key.size != 16) return null

        val plaintext = ccmDecrypt(frame, key, mac) ?: return null
        return parsePlaintext(plaintext, frame)
    }

    /**
     * 解析解密后的明文（12 字节 object）+ 原帧。纯逻辑，不依赖 Android Cipher，便于单测。
     */
    fun parsePlaintext(plaintext: ByteArray, frame: ByteArray): Parsed? {
        // 明文布局：object id(2 LE) + size(1) + data(9)
        val objectId = u16(plaintext[0], plaintext[1])
        val size = plaintext[2].toInt() and 0xFF
        if (size != OBJECT_SIZE || plaintext.size < 3 + OBJECT_SIZE) return null

        val data = plaintext.copyOfRange(3, 3 + OBJECT_SIZE)
        val profileId = data[0].toInt() and 0xFF
        val packed = u32(data[1], data[2], data[3], data[4])

        return when (objectId) {
            OBJECT_S400 -> decodeS400(packed, frame[FRAME_COUNT_POS].toInt() and 0xFF, profileId)
            OBJECT_S200 -> Parsed(
                objectId = objectId,
                profileId = profileId,
                weightKg = if (packed != 0L) packed / 100.0 else null,
                heartRateBpm = null,
                impedanceOhm = null,
                impedanceOnly = false,
                frameCount = frame[FRAME_COUNT_POS].toInt() and 0xFF,
            )
            else -> {
                Log.d(TAG, "未知 object 0x%04X".format(Locale.US, objectId))
                null
            }
        }
    }

    /** S800 复用 S400 的 0x6E16 测量对象，其广播同样只有体重 + 单阻抗 */
    private fun decodeS400(packed: Long, frameCount: Int, profileId: Int): Parsed {
        val rawWeight = (packed and 0x7FF).toInt()
        // S400 Pro 用「全 1」表示「无体重」，不是 0
        val weight = if (rawWeight == 0x7FF) 0 else rawWeight
        val hr = ((packed shr 11) and 0x7F).toInt()
        val imp = (packed shr 18).toInt()

        val weightKg = if (weight != 0) weight / 10.0 else null
        val heartRate = if (hr in 1..126) hr + 50 else null
        val impedance = if (imp != 0) imp / 10.0 else null
        val impedanceOnly = weight == 0 && hr == 0

        return Parsed(
            objectId = OBJECT_S400,
            profileId = profileId,
            weightKg = weightKg,
            heartRateBpm = heartRate,
            impedanceOhm = impedance,
            impedanceOnly = impedanceOnly,
            frameCount = frameCount,
        )
    }

    /**
     * 构造 AES-CCM 的 12 字节 nonce。ESPHome 逐字节确认：
     * [0..5] = MAC 反转（低位在前），[6..8] = frame[2..4]，[9..11] = frame[17..19]。
     * 公开以便单测锁定字节序。
     */
    fun buildNonce(frame: ByteArray, mac: String): ByteArray {
        val macLong = macToLong(mac)
        val nonce = ByteArray(12)
        for (i in 0..5) nonce[i] = ((macLong shr (i * 8)) and 0xFF).toByte()
        nonce[6] = frame[DEVICE_ID_POS]
        nonce[7] = frame[DEVICE_ID_POS + 1]
        nonce[8] = frame[FRAME_COUNT_POS]
        nonce[9] = frame[COUNTER_POS]
        nonce[10] = frame[COUNTER_POS + 1]
        nonce[11] = frame[COUNTER_POS + 2]
        return nonce
    }

    /**
     * AES-CCM 解密。Android 的 "AES/CCM/NoPadding" 用 GCMParameterSpec 提供 tag 长度 + nonce，
     * 密文与 tag 需拼接传入，AAD 用 updateAAD。
     */
    private fun ccmDecrypt(frame: ByteArray, key: ByteArray, mac: String): ByteArray? {
        return try {
            val nonce = buildNonce(frame, mac)

            val cipherText = frame.copyOfRange(CIPHER_POS, CIPHER_POS + CIPHER_SIZE)
            val tag = frame.copyOfRange(TAG_POS, TAG_POS + TAG_SIZE)
            val input = cipherText + tag

            val cipher = Cipher.getInstance("AES/CCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(32, nonce))
            cipher.updateAAD(AUTH_DATA)
            cipher.doFinal(input)
        } catch (e: Exception) {
            Log.d(TAG, "CCM 解密失败（bindkey 或 MAC 不对？）：${e.message}")
            null
        }
    }

    private fun u16(lo: Byte, hi: Byte): Int =
        (lo.toInt() and 0xFF) or ((hi.toInt() and 0xFF) shl 8)

    private fun u32(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Long =
        (b0.toInt() and 0xFF).toLong() or
            ((b1.toInt() and 0xFF).toLong() shl 8) or
            ((b2.toInt() and 0xFF).toLong() shl 16) or
            ((b3.toInt() and 0xFF).toLong() shl 24)

    private fun hexToBytes(hex: String): ByteArray? {
        val s = hex.trim().replace(" ", "").replace(":", "")
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            val hi = Character.digit(s[i], 16)
            val lo = Character.digit(s[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }
}
