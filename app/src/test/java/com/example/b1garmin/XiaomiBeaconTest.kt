package com.example.b1garmin

import com.example.b1garmin.scale.xiaomi.XiaomiBeacon
import org.junit.Assert.*
import org.junit.Test

/**
 * 小米 MiBeacon 解析测试。
 *
 * AES-CCM 解密由 Android 运行时 Cipher 完成（ESPHome 源码逐字节确认参数），
 * 这里锁定容易写错的部分：nonce 字节序、明文 object 位域、产品 ID、边界。
 * 构造明文时按 ESPHome 文档：[0..1]objectId LE [2]size=9 [3]profileId [4..7]packed LE [8..11]ts。
 */
class XiaomiBeaconTest {
    private val mac = "11:22:33:44:55:66"

    /** 构造 12 字节明文 object */
    private fun makePlain(objectId: Int, profileId: Int, packed: Long): ByteArray {
        val p = ByteArray(12)
        p[0] = (objectId and 0xFF).toByte()
        p[1] = ((objectId shr 8) and 0xFF).toByte()
        p[2] = 9
        p[3] = profileId.toByte()
        p[4] = (packed and 0xFF).toByte()
        p[5] = ((packed shr 8) and 0xFF).toByte()
        p[6] = ((packed shr 16) and 0xFF).toByte()
        p[7] = ((packed shr 24) and 0xFF).toByte()
        return p
    }

    /** 构造原帧的头部（供 frameCount 读取） */
    private fun makeFrame(frameCount: Int): ByteArray {
        val f = ByteArray(24)
        f[0] = 0x48; f[1] = 0x59
        f[2] = 0xD5.toByte(); f[3] = 0x3B.toByte() // product 0x3BD5
        f[4] = frameCount.toByte()
        f[17] = 1; f[18] = 2; f[19] = 3
        return f
    }

    @Test
    fun s400DecodesWeightHeartRateAndImpedance() {
        // weight 72.3kg -> 723; heart rate 82 -> 32; impedance 558.0Ω -> 5580
        val packed = 723L or (32L shl 11) or (5580L shl 18)
        val plain = makePlain(XiaomiBeacon.OBJECT_S400, 1, packed)

        val parsed = XiaomiBeacon.parsePlaintext(plain, makeFrame(7))
        assertNotNull(parsed)
        assertEquals(XiaomiBeacon.OBJECT_S400, parsed!!.objectId)
        assertEquals(1, parsed.profileId)
        assertEquals(72.3, parsed.weightKg!!, 0.05)
        assertEquals(82, parsed.heartRateBpm)
        assertEquals(558.0, parsed.impedanceOhm!!, 0.05)
        assertFalse(parsed.impedanceOnly)
        assertEquals(7, parsed.frameCount)
    }

    @Test
    fun s400ImpedanceOnlyFrame() {
        // 只有高频阻抗 503.0Ω -> 5030，weight=0 hr=0
        val packed = 5030L shl 18
        val plain = makePlain(XiaomiBeacon.OBJECT_S400, 2, packed)

        val parsed = XiaomiBeacon.parsePlaintext(plain, makeFrame(9))
        assertNotNull(parsed)
        assertNull(parsed!!.weightKg)
        assertNull(parsed.heartRateBpm)
        assertEquals(503.0, parsed.impedanceOhm!!, 0.05)
        assertTrue(parsed.impedanceOnly)
    }

    @Test
    fun s400ProNoWeightUsesAllOnes() {
        // S400 Pro 用全 1 (0x7FF) 表示无体重，其余字段：hr=60, imp=500.0
        val packed = 0x7FFL or (10L shl 11) or (5000L shl 18) // hr=60, imp=500.0
        val plain = makePlain(XiaomiBeacon.OBJECT_S400, 1, packed)

        val parsed = XiaomiBeacon.parsePlaintext(plain, makeFrame(4))!!
        assertNull(parsed.weightKg) // 全 1 被当成 0
        assertEquals(60, parsed.heartRateBpm)
        assertEquals(500.0, parsed.impedanceOhm!!, 0.05)
    }

    @Test
    fun s200DecodesWeightOnly() {
        // S200: packed = weight*100 = 65.4kg -> 6540
        val plain = makePlain(XiaomiBeacon.OBJECT_S200, 1, 6540L)

        val parsed = XiaomiBeacon.parsePlaintext(plain, makeFrame(3))
        assertNotNull(parsed)
        assertEquals(65.4, parsed!!.weightKg!!, 0.01)
        assertNull(parsed.heartRateBpm)
        assertNull(parsed.impedanceOhm)
    }

    @Test
    fun nonceByteOrderMatchesEspHome() {
        // MAC 11:22:33:44:55:66 反转后应是 66 55 44 33 22 11
        val f = makeFrame(7)
        val n = XiaomiBeacon.buildNonce(f, mac)
        assertEquals(0x66.toByte(), n[0])
        assertEquals(0x55.toByte(), n[1])
        assertEquals(0x44.toByte(), n[2])
        assertEquals(0x33.toByte(), n[3])
        assertEquals(0x22.toByte(), n[4])
        assertEquals(0x11.toByte(), n[5])
        // frame[2],[3],[4]
        assertEquals(0xD5.toByte(), n[6])
        assertEquals(0x3B.toByte(), n[7])
        assertEquals(7.toByte(), n[8])
        // frame[17],[18],[19]
        assertEquals(1.toByte(), n[9])
        assertEquals(2.toByte(), n[10])
        assertEquals(3.toByte(), n[11])
    }

    @Test
    fun heartRateOutOfRangeIgnored() {
        // hr 字段 0 或 127 都视为无效
        val packed0 = 723L or (0L shl 11) or (5580L shl 18)
        assertNull(XiaomiBeacon.parsePlaintext(makePlain(XiaomiBeacon.OBJECT_S400, 1, packed0), makeFrame(1))!!.heartRateBpm)
        val packed127 = 723L or (127L shl 11) or (5580L shl 18)
        assertNull(XiaomiBeacon.parsePlaintext(makePlain(XiaomiBeacon.OBJECT_S400, 1, packed127), makeFrame(1))!!.heartRateBpm)
    }

    @Test
    fun productIdRecognition() {
        assertTrue(XiaomiBeacon.isXiaomiScale(0x30D9))  // S400
        assertTrue(XiaomiBeacon.isXiaomiScale(0x3BD5))
        assertTrue(XiaomiBeacon.isXiaomiScale(0x4B05))  // S400 Pro
        assertTrue(XiaomiBeacon.isXiaomiScale(0x51E2))  // S800
        assertTrue(XiaomiBeacon.isXiaomiScale(0x45C9))  // S200
        assertTrue(XiaomiBeacon.isS800(0x51E2))
        assertFalse(XiaomiBeacon.isS800(0x3BD5))
        assertFalse(XiaomiBeacon.isXiaomiScale(0x1234))
    }

    @Test
    fun macAndHexHelpers() {
        assertEquals(0x112233445566L, XiaomiBeacon.macToLong("11:22:33:44:55:66"))
    }
}
