package com.example.b1garmin.protocol

/**
 * 蚂蚁阿福 × 沃莱/ICOMON A1（FG2508WB）体脂秤蓝牙协议。
 *
 * 与薄荷 B1 完全不同（B1 是 FC50 服务 + AF45 帧 + 复杂状态机），
 * A1 是 FFB0 服务 + AC 帧，来源：阿文博客逆向 + ant-afu-welland-scale。
 *
 * 帧：20 字节，byte[0]=0xAC，byte[2]=类型：
 * - 0x00 实时体重
 * - 0x80 稳定未锁定
 * - 0x02 最终结果（锁定）
 * 体重在 byte[10:13]，24 位大端，(raw - 0x680000)/1000 得 kg。
 * 阻抗：byte[4:6]、byte[6:8] 两个频率的 16 位大端（Ω），具体含义待真机校准。
 *
 * ⚠️ 实验性：协议由公开逆向资料整理，尚未在本项目真机验证，
 *    体成分仍用本项目统一的 BodyComp 四分量模型（和 B1 一致）。
 */
object A1Protocol {
    const val FRAME_SIZE = 20
    const val MAGIC: Byte = 0xAC.toByte()
    const val SERVICE_UUID = "0000ffb0-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID = "0000ffb1-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_UUID = "0000ffb2-0000-1000-8000-00805f9b34fb"

    const val TYPE_LIVE = 0x00
    const val TYPE_STABLE = 0x80
    const val TYPE_FINAL = 0x02

    /** 广播名关键字（A1 平时不广播，轻踩秤面才唤醒） */
    val NAME_HINTS = listOf("AFU-WL", "WL-TZ-A1", "UAF028", "FG2508")

    data class Parsed(
        val type: Int,
        val weightKg: Double?,
        val impedance1: Int?,
        val impedance2: Int?,
        val locked: Boolean,
        val raw: ByteArray,
    )

    private fun u16be(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u24be(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)

    fun isValid(f: ByteArray): Boolean = f.size >= FRAME_SIZE && f[0] == MAGIC

    fun parse(f: ByteArray): Parsed? {
        if (!isValid(f)) return null
        val type = f[2].toInt() and 0xFF
        // 体重：24 位大端减基准。异常值（未上秤/超范围）过滤掉。
        val rawW = u24be(f, 10)
        val kg = ((rawW - 0x680000).toDouble() / 1000.0).takeIf { it in 2.0..300.0 }
        return Parsed(
            type = type,
            weightKg = kg,
            impedance1 = if (f.size >= 6) u16be(f, 4) else null,
            impedance2 = if (f.size >= 8) u16be(f, 6) else null,
            locked = type == TYPE_FINAL,
            raw = f.copyOf(),
        )
    }

    /**
     * 触发测量的用户参数命令：10 00 sex age height
     * sex: 1=女, 2=男；height 单位 cm。
     */
    fun buildUserCommand(male: Boolean, age: Int, heightCm: Int): ByteArray = byteArrayOf(
        0x10, 0x00,
        (if (male) 2 else 1).toByte(),
        age.coerceIn(10, 100).toByte(),
        (heightCm and 0xFF).toByte(),
    )

    /**
     * 构造一帧模拟 AC 帧（仅测试版模拟称重用）：把体重/阻抗按真实字节布局写进去，
     * 再交给 parse() 走真实解析链，从而在没蓝牙的模拟器里验证 A1 协议解析本身。
     */
    fun buildFakeFrame(weightKg: Double, impedance1: Int, impedance2: Int, type: Int): ByteArray {
        val f = ByteArray(FRAME_SIZE)
        f[0] = MAGIC
        f[2] = type.toByte()
        f[4] = (impedance1 shr 8).toByte(); f[5] = impedance1.toByte()
        f[6] = (impedance2 shr 8).toByte(); f[7] = impedance2.toByte()
        val rawW = (weightKg * 1000).toInt() + 0x680000
        f[10] = (rawW shr 16).toByte(); f[11] = (rawW shr 8).toByte(); f[12] = rawW.toByte()
        return f
    }

    fun toHex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
