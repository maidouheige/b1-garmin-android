package com.example.b1garmin.protocol

/**
 * 蚂蚁阿福 × 薄荷 B1 体脂秤 (AFU-BH-TZ-B1) 蓝牙协议编解码
 * 从 B1-Garmin-Bridge/src/afu_b1_protocol.py 逐行移植，逻辑一致。
 * 所有帧定长 20 字节，头 AF 45，校验和 = sum(byte[2..18]) & 0x1F
 */
object AfuProtocol {
    const val FRAME_SIZE = 20
    const val WIRE_LIVE_WEIGHT = 0x51
    const val WIRE_IMPEDANCE = 0x52
    const val WIRE_STORED = 0x54
    const val WIRE_CONTROL = 0x5F
    const val WIRE_PROFILE = 0x61

    const val SERVICE_UUID = "0000fc50-0000-1000-8000-00805f9b34fb"
    const val WRITE_CHAR_UUID = "af000002-9f2d-4c8a-8d6f-6a7b45f90000"

    // 用户资料变体: (code, openByte)
    private val PROFILE_VARIANTS = mapOf(
        "INITIAL" to (0x3F to 0xB6),
        "GET_WEIGH_AGAIN_1" to (0x4B to 0xB6),
        "GET_WEIGH_AGAIN_2" to (0x5B to 0xD9),
        "FINAL" to (0x68 to 0xD9),
        "HISTORY_DUMP" to (0x8E to 0xDE),
    )

    private val SYNC_1 = byteArrayOf(
        0xAF.toByte(),0x45,0x04,0x00,0x01,0xAF.toByte(),0xB6.toByte(),0x17,0x1D,
        0x01,0x00,0x02,0xB4.toByte(),0xC6.toByte(),0x1B,0x56,0x01,0x00,0x62,0x00)
    private val SYNC_2 = byteArrayOf(
        0xAF.toByte(),0x45,0x04,0x01,0x03,0xA5.toByte(),0xF8.toByte(),0x11,0x1D,
        0x02,0x00,0x04,0xB4.toByte(),0xC6.toByte(),0x1B,0x1D,0x01,0x00,0x62,0x00)

    sealed class Parsed {
        data class LiveWeight(val weightGrams: Int, val locked: Boolean, val raw: ByteArray) : Parsed()
        data class Impedance(val valid: Boolean, val resistanceOhm: Int, val raw: ByteArray) : Parsed()
        data class Stored(
            val sequence: Int, val timestampEpoch: Long, val weightGrams: Int,
            val resistanceOhm: Int, val locked: Boolean, val raw: ByteArray) : Parsed()
        data class Other(val wireType: Int, val raw: ByteArray) : Parsed()
    }

    private fun u8(b: Byte) = b.toInt() and 0xFF
    private fun le16(b: ByteArray, o: Int) = u8(b[o]) or (u8(b[o + 1]) shl 8)
    private fun le32(b: ByteArray, o: Int) =
        u8(b[o]) or (u8(b[o+1]) shl 8) or (u8(b[o+2]) shl 16) or (u8(b[o+3]) shl 24)
    private fun putLe16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o+1] = ((v shr 8) and 0xFF).toByte()
    }
    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o+1] = ((v shr 8) and 0xFF).toByte()
        b[o+2] = ((v shr 16) and 0xFF).toByte(); b[o+3] = ((v shr 24) and 0xFF).toByte()
    }
    private fun checksum(f: ByteArray): Int {
        var s = 0
        for (i in 2..18) s += u8(f[i])
        return s and 0x1F
    }
    fun isValid(f: ByteArray) = f.size == FRAME_SIZE && u8(f[0]) == 0xAF && u8(f[1]) == 0x45 &&
            checksum(f) == (u8(f[19]) and 0x1F)

    fun parse(f: ByteArray): Parsed? {
        if (!isValid(f)) return null
        return when (val wire = u8(f[18])) {
            WIRE_LIVE_WEIGHT -> {
                val flags = le32(f, 2)
                Parsed.LiveWeight(flags and 0x3FFFF, (flags and 0x80000000.toInt()) != 0, f)
            }
            WIRE_STORED -> {
                val meta = u8(f[2]); val flags = le32(f, 7)
                Parsed.Stored((meta shr 2) and 0x3F, le32(f, 3).toLong() and 0xFFFFFFFFL,
                    flags and 0x3FFFF, le16(f, 13), (flags and 0x80000000.toInt()) != 0, f)
            }
            WIRE_IMPEDANCE -> Parsed.Impedance(u8(f[2]) == 0x01, le16(f, 4), f)
            else -> Parsed.Other(wire, f)
        }
    }

    private fun frame(wireType: Int, fill: (ByteArray) -> Unit): ByteArray {
        val f = ByteArray(FRAME_SIZE)
        f[0] = 0xAF.toByte(); f[1] = 0x45.toByte(); f[18] = wireType.toByte()
        fill(f)
        f[19] = checksum(f).toByte()
        return f
    }
    private fun withChecksum(src: ByteArray): ByteArray {
        val f = src.copyOf(); f[19] = checksum(f).toByte(); return f
    }

    fun buildTimeSync(epoch: Long) = frame(WIRE_CONTROL) { f ->
        f[2] = 0x16.toByte(); putLe32(f, 3, epoch.toInt()); f[7] = 0x20.toByte()
    }
    fun buildSyncFirst() = withChecksum(SYNC_1)
    fun buildSyncSecond() = withChecksum(SYNC_2)

    fun buildProfile(variant: String, epoch: Long, userSlot: Int, age: Int,
                     male: Boolean, targetKg: Double): ByteArray {
        val (code, openByte) = PROFILE_VARIANTS[variant] ?: PROFILE_VARIANTS["INITIAL"]!!
        return frame(WIRE_PROFILE) { f ->
            f[2] = code.toByte(); putLe32(f, 3, epoch.toInt())
            f[7] = 0; f[8] = userSlot.coerceIn(0,255).toByte()
            f[9] = 0xAF.toByte(); f[10] = openByte.toByte(); f[11] = 0x17.toByte()
            f[12] = age.coerceIn(0,255).toByte(); f[13] = (if (male) 1 else 0).toByte()
            putLe16(f, 14, (targetKg * 100).toInt().coerceIn(0, 0xFFFF))
            f[16] = 0x03.toByte(); f[17] = 0
        }
    }
    fun buildAck(replyType: Int, correlation: Int = 0) = frame(WIRE_CONTROL) { f ->
        f[2] = 0x12.toByte(); f[3] = replyType.toByte(); f[4] = 0; f[5] = correlation.coerceIn(0,255).toByte()
    }
    fun buildChunkRequest() = frame(WIRE_CONTROL) { it[2] = 0x14.toByte() }

    fun toHex(b: ByteArray) = b.joinToString(" ") { "%02X".format(u8(it)) }
}
