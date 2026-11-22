package com.example.b1garmin.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FIT 文件生成 —— 与电脑版 sync_tool/fit.py 逐字段保持一致。
 *
 * 那个 Python 版本已经实测能正常上传 Garmin Connect 并正确显示体成分，
 * 所以这里只做等价移植：同样的消息、字段顺序、缩放系数、序列号/厂商号，
 * 同样的 half-up 量化，并且和它一样**不写** metabolic_age（Garmin 现在会把它
 * 渲染成「年龄 × 一年的毫秒数」，属于服务端显示回归）。
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
    private val UINT32Z = BT(4, 0x8C, 0L)

    private const val FIT_EPOCH_OFFSET = 631065600L
    private const val SERIAL_NUMBER = 0x5A17C0DEL
    private const val MANUFACTURER = 255L          // 255 = development，和电脑版一致
    private const val FILE_TYPE_WEIGHT = 9L
    private fun fitTimestamp(epochSec: Long): Long = epochSec - FIT_EPOCH_OFFSET

    private data class Field(val num: Int, val bt: BT, val value: Long?, val scale: Double = 1.0)

    private class Encoder {
        /** 只存消息数据（不含文件头），finish 时再拼 头+数据+CRC */
        private val dataBuf = ByteArrayOutputStream()
        private var weightScaleDefined = false

        private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        private fun le32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())

        /** FIT 分辨率量化用 half-up（Python 侧是 floor(x + 0.5)），保证两边逐字节一致 */
        private fun quantize(v: Double, scale: Double) = Math.floor(v * scale + 0.5).toLong()

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

        fun writeFileInfo(ts: Long) = writeMessage(0, 0, listOf(
            Field(3, UINT32Z, SERIAL_NUMBER),
            Field(4, UINT32, ts),
            Field(1, UINT16, MANUFACTURER),
            Field(2, UINT16, 0L),
            Field(5, UINT16, null),
            Field(0, ENUM, FILE_TYPE_WEIGHT),
        ), true)

        fun writeFileCreator() = writeMessage(49, 1, listOf(
            Field(0, UINT16, 100L), Field(1, UINT8, null)), true)

        fun writeDeviceInfo(ts: Long) = writeMessage(23, 2, listOf(
            Field(253, UINT32, ts),
            Field(3, UINT32Z, SERIAL_NUMBER),
            Field(7, UINT32, null),
            Field(8, UINT32, null),
            Field(2, UINT16, MANUFACTURER),
            Field(4, UINT16, null),
            Field(5, UINT16, null),
            Field(10, UINT16, null),
            Field(0, UINT8, 0L),
            Field(1, UINT8, null),
            Field(6, UINT8, null),
            Field(11, UINT8, null),
        ), true)

        fun writeWeightScale(e: BodyComp.Estimate, timestampSec: Long) {
            fun scaled(v: Double?, s: Double) = v?.let { quantize(it, s) }
            val content = listOf(
                Field(253, UINT32, fitTimestamp(timestampSec)),
                Field(0, UINT16, scaled(e.weightKg, 100.0)),
                Field(1, UINT16, scaled(e.bodyFatPercent, 100.0)),
                Field(2, UINT16, scaled(e.percentHydration, 100.0)),
                Field(3, UINT16, null),
                Field(4, UINT16, scaled(e.boneMassKg, 100.0)),
                Field(5, UINT16, scaled(e.muscleMassKg, 100.0)),
                Field(7, UINT16, e.basalMet.toLong() * 4),
                Field(9, UINT16, null),
                Field(8, UINT8, null),
                Field(11, UINT8, e.visceralFatRating.toLong()),
                Field(13, UINT16, scaled(e.bmi, 10.0)),
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
    fun build(e: BodyComp.Estimate, timestampSec: Long): ByteArray =
        buildMany(listOf(e to timestampSec))

    /** 一次生成多条 weight_scale 记录（导出历史用），时间戳按各自测量时刻写入。 */
    fun buildMany(items: List<Pair<BodyComp.Estimate, Long>>): ByteArray {
        require(items.isNotEmpty()) { "至少需要一条记录" }
        val enc = Encoder()
        val firstTs = items.first().second
        enc.writeFileInfo(fitTimestamp(firstTs))
        enc.writeFileCreator()
        enc.writeDeviceInfo(fitTimestamp(firstTs))
        items.forEach { (e, ts) -> enc.writeWeightScale(e, ts) }
        return enc.finish()
    }
}
