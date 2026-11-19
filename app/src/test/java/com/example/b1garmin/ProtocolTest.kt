package com.example.b1garmin

import com.example.b1garmin.protocol.AfuProtocol
import com.example.b1garmin.protocol.BodyComp
import com.example.b1garmin.protocol.FitWriter
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test
    fun protocolFramesHaveValidChecksum() {
        assertTrue(AfuProtocol.isValid(AfuProtocol.buildTimeSync(1700000000)))
        assertTrue(AfuProtocol.isValid(AfuProtocol.buildSyncFirst()))
        assertTrue(AfuProtocol.isValid(AfuProtocol.buildSyncSecond()))
        for (v in listOf("INITIAL", "FINAL", "HISTORY_DUMP", "GET_WEIGH_AGAIN_1", "GET_WEIGH_AGAIN_2"))
            assertTrue(v, AfuProtocol.isValid(AfuProtocol.buildProfile(v, 1700000000, 0, 35, true, 70.0)))
        assertTrue(AfuProtocol.isValid(AfuProtocol.buildAck(0x51)))
    }

    @Test
    fun parseLiveWeight() {
        val f = ByteArray(20)
        f[0] = 0xAF.toByte(); f[1] = 0x45.toByte(); f[18] = AfuProtocol.WIRE_LIVE_WEIGHT.toByte()
        // le32(f,2): 体重低18位 + 最高位锁定。73750 = 0x12016
        val flags = (73750 or (1 shl 31)).toLong()
        f[2] = (flags and 0xFF).toByte()
        f[3] = ((flags shr 8) and 0xFF).toByte()
        f[4] = ((flags shr 16) and 0xFF).toByte()
        f[5] = ((flags shr 24) and 0xFF).toByte()
        var sum = 0; for (i in 2..18) sum += f[i].toInt() and 0xFF
        f[19] = (sum and 0x1F).toByte()
        val p = AfuProtocol.parse(f) as AfuProtocol.Parsed.LiveWeight
        assertEquals(73750, p.weightGrams)
        assertTrue(p.locked)
    }

    @Test
    fun bodyCompositionMatchesExpected() {
        val e = BodyComp.estimate(73.75, 173.0, 35, "male", 624.0)!!
        assertEquals(24.6, e.bmi, 0.2)
        assertEquals(30.4, e.bodyFatPercent, 1.0)
        // 阿福四分量模型：基础代谢 = 30 × 去脂体重（去脂体重 51.4kg → 约 1541 kcal）
        val ffm = 73.75 * (1 - e.bodyFatPercent / 100.0)
        assertEquals(30 * ffm, e.basalMet.toDouble(), 30.0)
        assertTrue(e.muscleMassKg > 45 && e.muscleMassKg < 50)
        // 内脏脂肪等级 = 体脂率/3，上限 9（30.4% → 10.1 → 收到 9）
        assertEquals(Math.min(9.0, Math.round(e.bodyFatPercent / 3.0).toDouble()), e.visceralFatRating.toDouble(), 0.1)
    }

    /**
     * 没有阻抗时必须退回 BMI 估算模型，且**不能**套用阻抗档的偏移。
     *
     * 这条是校准的核心安全网：两套模型的系统偏差方向相反（实测 +3.0 / -0.6），
     * 串档会让体脂率差 3 个多百分点，比不校准还糟。
     */
    @Test
    fun offsetsAreAppliedPerModelOnly() {
        val impOffsets = BodyComp.Offsets(imp = 3.0, bmi = -0.6)
        assertEquals(3.0, impOffsets.forModel("segal_3b_male_ge20"), 1e-9)
        assertEquals(-0.6, impOffsets.forModel("deurenberg_bmi_fallback"), 1e-9)

        // 有阻抗 → 用 imp 偏移
        val withImp = BodyComp.estimate(67.75, 174.5, 35, "male", 512.0, impOffsets)!!
        assertTrue(withImp.model.startsWith("segal"))
        val rawImp = BodyComp.rawBf(67.75, 174.5, 35, "male", 512.0).first
        assertEquals(rawImp + 3.0, withImp.bodyFatPercent, 0.11)

        // 没阻抗 → 用 bmi 偏移（而不是 imp 的 +3.0）
        val withoutImp = BodyComp.estimate(67.75, 174.5, 35, "male", null, impOffsets)!!
        assertEquals("deurenberg_bmi_fallback", withoutImp.model)
        val rawBmi = BodyComp.rawBf(67.75, 174.5, 35, "male", null).first
        assertEquals(rawBmi - 0.6, withoutImp.bodyFatPercent, 0.11)
    }

    /** 某一档没校准时，另一档的偏移不能串过来（宁可不准，也不给错值）。 */
    @Test
    fun uncalibratedTrackUsesZeroOffset() {
        val onlyBmi = BodyComp.Offsets(bmi = -0.6)
        assertEquals(0.0, onlyBmi.forModel("segal_3b_male_ge20"), 1e-9)
        val e = BodyComp.estimate(67.75, 174.5, 35, "male", 512.0, onlyBmi)!!
        val raw = BodyComp.rawBf(67.75, 174.5, 35, "male", 512.0).first
        assertEquals(raw, e.bodyFatPercent, 0.11)
    }

    /**
     * 出厂内置阿福公式：新装/重装/换手机（没有任何校准记录）的用户，
     * 用出厂默认偏移兜底后，体脂率应直接对齐阿福，无需手动校准。
     */
    @Test
    fun factoryDefaultAlignsWithAfuWithoutCalibration() {
        // 空偏移（= 从来没校准过的秤）经出厂兜底后，两档都应拿到内置值
        val effective = BodyComp.Offsets().withFactoryDefault()
        assertEquals(3.8, effective.forModel("segal_3a_male_lt20"), 1e-9)
        assertEquals(-0.8, effective.forModel("deurenberg_bmi_fallback"), 1e-9)

        // 用这条默认标准估算：阻抗档算出 15.9% + 3.8 ≈ 19.7%，落在合理区间，
        // 且明显区别于「完全不校准」的原始值（约 15.9%）—— 证明开箱即已对齐阿福量级
        val raw = BodyComp.rawBf(67.75, 174.5, 35, "male", 512.0).first
        val e = BodyComp.estimate(67.75, 174.5, 35, "male", 512.0, effective)!!
        assertTrue(e.model.startsWith("segal"))
        assertEquals(raw + 3.8, e.bodyFatPercent, 0.11)
        assertTrue(e.bodyFatPercent > raw + 3.0)
    }

    /** 真机已经校准过的值优先级高于出厂默认，绝不能被默认值覆盖。 */
    @Test
    fun factoryDefaultNeverOverridesUserCalibration() {
        // 用户真机只校了阻抗档（比如 +3.2），BMI 档没校
        val userCalib = BodyComp.Offsets(imp = 3.2)
        val effective = userCalib.withFactoryDefault()
        assertEquals(3.2, effective.imp!!, 1e-9)          // 用户校准值保留
        assertEquals(-0.8, effective.bmi!!, 1e-9)         // 没校的档才用出厂默认

        // 用户校准值哪怕和默认很接近，也要用用户的（比如 0.0 是用户有意设的）
        val zeroed = BodyComp.Offsets(imp = 0.0, bmi = 0.0).withFactoryDefault()
        assertEquals(0.0, zeroed.forModel("segal_3b_male_ge20"), 1e-9)
        assertEquals(0.0, zeroed.forModel("deurenberg_bmi_fallback"), 1e-9)
    }

    /** 阿福那次实测：67.75kg / 18.0% → 反推出的四分量应当闭合。 */
    @Test
    fun fourComponentModelCloses() {
        val e = BodyComp.estimate(67.75, 174.5, 35, "male", null,
            BodyComp.Offsets(bmi = 18.0 - BodyComp.rawBf(67.75, 174.5, 35, "male", null).first))!!
        assertEquals(18.0, e.bodyFatPercent, 0.11)
        val fat = 67.75 * 0.18
        val lean = 67.75 - fat
        assertEquals(lean * 0.0522, e.boneMassKg, 0.2)
        assertEquals(lean * 0.6966 / 67.75 * 100.0, e.percentHydration, 0.3)
        assertEquals(lean * 0.2520, lean - lean * 0.0522 - lean * 0.6966, 0.5)
    }

    /** 年龄按出生日期算：生日当天的算满岁，生日没到少一岁。 */
    @Test
    fun ageIsComputedFromBirthday() {
        val today = java.time.LocalDate.of(2026, 11, 17)
        assertEquals(35, com.example.b1garmin.ui.Profile.ageFrom(1991, 1, 1, today))
        assertEquals(35, com.example.b1garmin.ui.Profile.ageFrom(1991, 11, 17, today))
        assertEquals(34, com.example.b1garmin.ui.Profile.ageFrom(1991, 11, 18, today))
        assertEquals(35, com.example.b1garmin.ui.Profile.ageFrom(1990, 12, 31, today))
        assertEquals(36, com.example.b1garmin.ui.Profile.ageFrom(1990, 1, 1, today))
    }

    /**
     * 验证台账：连续对得上的次数要按时间倒着数，中间断一次就重新开始。
     *
     * 这条决定「校准什么时候算固化」，逻辑错了会误判。
     */
    @Test
    fun verificationStreakStopsAtFirstMismatch() {
        val e = { ts: Long, calc: Double, afu: Double ->
            com.example.b1garmin.ui.VerifyEntry(ts, 67.0, 500.0, 15.0, calc, afu)
        }
        // 三次都对得上 → 连续 3 次
        val good = listOf(e(1, 18.0, 18.1), e(2, 18.1, 18.0), e(3, 18.2, 18.2))
        val (s1, max1) = com.example.b1garmin.ui.VerifyStore.streak(good)
        assertEquals(3, s1)
        assertEquals(0.1, max1, 0.001)

        // 最近一次差得远 → 连续次数归零重数
        val mixed = listOf(e(1, 18.0, 18.1), e(2, 18.1, 18.0), e(3, 18.2, 19.5))
        assertEquals(0, com.example.b1garmin.ui.VerifyStore.streak(mixed).first)

        // 中间断一次 → 只算断之后的那一段
        val broken = listOf(e(1, 18.0, 18.0), e(2, 18.1, 19.5), e(3, 18.2, 18.2))
        assertEquals(1, com.example.b1garmin.ui.VerifyStore.streak(broken).first)

        // 没填阿福数值的记录不参与统计
        val pending = listOf(
            com.example.b1garmin.ui.VerifyEntry(1, 67.0, 500.0, 15.0, 18.0, null),
            e(2, 18.1, 18.0)
        )
        assertEquals(1, com.example.b1garmin.ui.VerifyStore.streak(pending).first)
    }

    /**
     * 校准值必须按秤分开存：换新秤不能把旧秤的偏移套过去。
     */
    @Test
    fun calibrationIsStoredPerScale() {
        val map = mutableMapOf(
            "AA:BB:CC:DD:EE:FF" to com.example.b1garmin.ui.ScaleCalib(
                imp = 3.0, streak = 2, frozen = true, name = "旧秤"
            ),
            "11:22:33:44:55:66" to com.example.b1garmin.ui.ScaleCalib(
                imp = 1.5, name = "新秤"
            )
        )
        val back = com.example.b1garmin.ui.CalibStore.parse(
            com.example.b1garmin.ui.CalibStore.dump(map)
        )
        assertEquals(2, back.size)
        assertEquals(3.0, back["AA:BB:CC:DD:EE:FF"]!!.imp!!, 1e-6)
        assertEquals(1.5, back["11:22:33:44:55:66"]!!.imp!!, 1e-6)
        // 两台秤互不影响：一台固化了，另一台仍然是未固化
        assertTrue(back["AA:BB:CC:DD:EE:FF"]!!.frozen)
        assertFalse(back["11:22:33:44:55:66"]!!.frozen)
        assertEquals(2, back["AA:BB:CC:DD:EE:FF"]!!.streak)
        assertEquals("新秤", back["11:22:33:44:55:66"]!!.name)
        // 没校准过的那一档要保持 null，不能变成 0
        assertNull(back["11:22:33:44:55:66"]!!.bmi)
    }

    @Test
    fun fitFileHasValidStructure() {        val e = BodyComp.estimate(73.75, 173.0, 35, "male", 624.0)!!
        val fit = FitWriter.build(e, System.currentTimeMillis() / 1000)
        assertEquals(".FIT", String(fit, 8, 4))
        // 文件尾 CRC 校验
        val body = fit.copyOfRange(0, fit.size - 2)
        val storedCrc = (fit[fit.size-2].toInt() and 0xFF) or ((fit[fit.size-1].toInt() and 0xFF) shl 8)
        assertEquals(FitWriter.crc16(body), storedCrc)
        assertTrue(fit.size > 100)
    }

    // ---------- A1（实验性）：用阿文博客公开的真实帧验证 ----------

    @Test
    fun a1ParsesRealLockedFrame() {
        // 博客实测：真人 67.30kg 的锁定帧，bytes[10:13]=69 06 b2 → (0x6906b2-0x680000)/1000≈67.25
        val hex = "ac 29 02 00 02 3f 02 07 01 80 69 06 b2 00 00 00 00 00 29 d6 0d"
        val f = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        val p = com.example.b1garmin.protocol.A1Protocol.parse(f)!!
        assertEquals(0x02, p.type)
        assertTrue(p.locked)
        assertEquals(67.25, p.weightKg!!, 0.05)
        assertEquals(575, p.impedance1)   // 02 3f
        assertEquals(519, p.impedance2)   // 02 07
    }

    @Test
    fun a1RejectsNonAcFrameAndBuildsUserCommand() {
        val junk = ByteArray(20)
        assertNull(com.example.b1garmin.protocol.A1Protocol.parse(junk))   // 非 AC 帧
        val cmd = com.example.b1garmin.protocol.A1Protocol.buildUserCommand(true, 35, 172)
        assertArrayEquals(byteArrayOf(0x10, 0x00, 0x02, 35, 172.toByte()), cmd)
    }

    /**
     * DI OAuth2 的 grant_type 是全球统一协议常量，绝不随账号区域(.com/.cn)变化。
     * 国区端点 diauth.garmin.cn 实测只认 .com 那个 URI：换成 .cn 会 400 unsupported_grant_type，
     * 导致抓到 ticket 却换不到 token。这里锁死常量，防止以后又被"顺手"按域名拼回去。
     */
    @Test
    fun diGrantTypeIsRegionIndependentConstant() {
        val gt = com.example.b1garmin.garmin.GarminAuth.DI_GRANT_TYPE
        assertEquals("https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket", gt)
        assertFalse("grant_type 不能带 .cn", gt.contains("garmin.cn"))
    }
}
