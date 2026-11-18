package com.example.b1garmin.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date

/**
 * FIT 文件生成 —— 从 fit_writer.py 逐行移植，其实现忠实于 python-garminconnect 的
 * FitEncoderWeight（已验证可上传 Garmin Connect）。
 * 生成含 weight_scale 消息的 FIT，等价于真 Index S2 上传的体重记录。
 */
object FitWriter {
    private val CRC_TABLE = intArrayOf(
        0x0000,0xCC01,0xD801,0x1400,0xF001,0x3C00,0x2800,0xE401,
        0xA001,0x6C00,0x7800,0xB401,0x5000,0x9C01,0x8801,0x4400)

    private fun calcCrc(crcIn: Int, byte: Int): Int {
        var crc = crcIn
        var tmp = CRC_TABLE[crc and 0xF]
        crc = (crc shr 4) and 0x0FFF
        crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
        tmp = CRC_TABLE[crc and 0xF]
        crc = (crc shr 4) and 0x0FFF
        return crc xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
    }
    fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = calcCrc(crc, b.toInt() and 0xFF)
        return crc and 0xFFFF
    }

    // 基础类型: size / field 编码(含 endian 位) / 无效值
    private data class BT(val size: Int, val field: Int, val invalid: Long)
    private val ENUM = BT(1, 0x00, 0xFF)
    private val UINT8 = BT(1, 0x02, 0xFF)
    private val UINT16 = BT(2, 0x84, 0xFFFF)
    private val UINT32 = BT(4, 0x86, 0xFFFFFFFFL)

    private const val FIT_EPOCH_OFFSET = 631065600L
    private fun fitTimestamp(epochSec: Long) = (epochSec - FIT_EPOCH_OFFSET).toInt()

    private data class Field(val num: Int, val bt: BT, val value: Long?, val scale: Double)

    private class Encoder {
        /** 只存消息数据（不含文件头），finish 时再拼 头+数据+CRC */
        private val dataBuf = ByteArrayOutputStream()
        private var weightScaleDefined = false

        private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        private fun le32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

        private fun buildContent(content: List<Field>): Pair<ByteArray, ByteArray> {
            val defs = ByteArrayOutputStream(); val vals = ByteArrayOutputStream()
            for (f in content) {
                defs.write(byteArrayOf(f.num.toByte(), f.bt.size.toByte(), f.bt.field.toByte()))
                val raw = f.value ?: f.bt.invalid
                when (f.bt.size) {
                    1 -> vals.write(raw.toInt() and 0xFF)
                    2 -> vals.write(le16((raw and 0xFFFF).toInt()))
                    4 -> vals.write(le32((raw and 0xFFFFFFFFL).toInt()))
                }
            }
            return defs.toByteArray() to vals.toByteArray()
        }

        private fun writeMessage(gmsg: Int, lmsg: Int, content: List<Field>, define: Boolean) {
            val (defs, vals) = buildContent(content)
            val fixed = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                .put(0.toByte()).put(0.toByte()).putShort(gmsg.toShort()).put(content.size.toByte()).array()
            if (define) {
                dataBuf.write(((1 shl 6) + lmsg))
                dataBuf.write(fixed); dataBuf.write(defs)
            }
            dataBuf.write(lmsg)
            dataBuf.write(vals)
        }

        fun writeFileInfo() = writeMessage(0, 0, listOf(
            Field(0, ENUM, 9L, 1.0), Field(1, UINT16, 15L, 1.0), Field(2, UINT16, 2853L, 1.0)), true)

        fun writeFileCreator() = writeMessage(49, 1, listOf(
            Field(0, UINT16, null, 1.0), Field(1, UINT8, null, 1.0)), true)

        fun writeDeviceInfo(ts: Int) = writeMessage(23, 2, listOf(
            Field(253, UINT32, ts.toLong() and 0xFFFFFFFFL, 1.0), Field(2, UINT16, 15L, 1.0),
            Field(4, UINT16, 2853L, 1.0), Field(0, UINT8, 0L, 1.0), Field(1, UINT8, 119L, 1.0)), true)

        fun writeWeightScale(e: BodyComp.Estimate, timestampSec: Long) {
            fun scaled(v: Double?, s: Double) = v?.let { (it * s).toLong() }
            val content = listOf(
                Field(253, UINT32, fitTimestamp(timestampSec).toLong() and 0xFFFFFFFFL, 1.0),
                Field(0, UINT16, scaled(e.weightKg, 100.0), 1.0),
                Field(1, UINT16, scaled(e.bodyFatPercent, 100.0), 1.0),
                Field(2, UINT16, scaled(e.percentHydration, 100.0), 1.0),
                Field(3, UINT16, null, 1.0),
                Field(4, UINT16, scaled(e.boneMassKg, 100.0), 1.0),
                Field(5, UINT16, scaled(e.muscleMassKg, 100.0), 1.0),
                Field(7, UINT16, e.basalMet.toLong() * 4, 1.0),
                Field(9, UINT16, null, 1.0),
                Field(8, UINT8, e.physiqueRating.toLong(), 1.0),
                Field(10, UINT8, e.metabolicAge.toLong(), 1.0),
                Field(11, UINT8, e.visceralFatRating.toLong(), 1.0),
                Field(13, UINT16, scaled(e.bmi, 10.0), 1.0),
            )
            writeMessage(30, 3, content, !weightScaleDefined)
            weightScaleDefined = true
        }

        fun finish(): ByteArray {
            val msgData = dataBuf.toByteArray()
            // 12 字节文件头
            val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .put(12.toByte()).put(16.toByte()).putShort(108.toShort())
                .putInt(msgData.size).put(".FIT".toByteArray()).array()
            val withoutCrc = header + msgData
            val crc = crc16(withoutCrc)
            return ByteBuffer.allocate(withoutCrc.size + 2).order(ByteOrder.LITTLE_ENDIAN)
                .put(withoutCrc).putShort(crc.toShort()).array()
        }
    }

    /** 生成 weight_scale FIT 字节。timestampSec = 称重时刻的 Unix 秒。 */
    fun build(e: BodyComp.Estimate, timestampSec: Long): ByteArray {
        val enc = Encoder()
        enc.writeFileInfo()
        enc.writeFileCreator()
        enc.writeDeviceInfo(fitTimestamp(timestampSec))
        enc.writeWeightScale(e, timestampSec)
        return enc.finish()
    }
}
