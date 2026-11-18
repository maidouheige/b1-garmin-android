package com.example.b1garmin.protocol

import kotlin.math.roundToInt

/**
 * BIA 体成分计算。
 *
 * ## 体脂率（唯一需要阻抗的量）
 * 用 Segal 阻抗方程（分性别/体脂档位），拿不到阻抗时退回 Deurenberg 的 BMI 公式。
 * 这些通用方程和厂家算法存在系统偏差（实测：Segal 算出 15.0%，阿福显示 18.0%），
 * 所以支持一次性**校准偏移** [Offsets]：用户把阿福显示的体脂率填进来，
 * 我们记下差值，之后同类测量都加上这个偏移。这样既保留阻抗对变化趋势的敏感性，
 * 又能和阿福的绝对值对齐。偏移按模型分档保存（阻抗模型 / BMI 兜底各一套）。
 *
 * ## 其余指标（全部由体脂率推导）
 * 比例取自 2026-11-17 用阿福 App 实测数据反推的四分量模型，
 * 反推结果闭合得很好（67.75kg / 18.0% → 水 38.70 + 蛋白 14.00 + 骨 2.90 + 脂肪 12.20 = 67.80kg）：
 * - 体水分  = 0.6966 × 去脂体重
 * - 蛋白质  = 0.2520 × 去脂体重
 * - 骨量    = 0.0522 × 去脂体重
 * - 肌肉量  = 去脂体重 − 骨量（对应阿福的 52.7kg，**不是**骨骼肌）
 * - 基础代谢 = 30 × 去脂体重 kcal（阿福 1666 kcal，验算 30×55.56=1666.7 ✓）
 * - 内脏脂肪等级 = 体脂率 / 3（阿福 18.0% → 6 级 ✓）
 */
object BodyComp {

    /**
     * 校准偏移（百分点），**按模型分开存**。
     *
     * 为什么必须分开：两种模型的系统偏差方向完全不同。实测同一人（67.75kg/174.5cm/35岁/男）：
     * - 有阻抗（Segal）算出 14.4~15.0%，阿福 18.0% → 偏移约 +3.0
     * - 无阻抗（Deurenberg/BMI）算出 18.6%，阿福 18.0% → 偏移约 -0.6
     * 如果混用，一个值套到另一个模型上会差 3 个多百分点，比不校准还糟。
     */
    data class Offsets(val imp: Double? = null, val bmi: Double? = null) {
        fun forModel(model: String): Double =
            (if (model.startsWith("segal")) imp else bmi) ?: 0.0

        /**
         * 用出厂默认填补「没校准过的档」，已校准的档原样保留。
         * 等价于 MainActivity 里的 `imp ?: 默认.imp, bmi ?: 默认.bmi`，
         * 抽成纯函数是为了能单测这条开箱即用的关键路径。
         */
        fun withFactoryDefault(factory: Offsets = AFU_FACTORY): Offsets = Offsets(
            imp = imp ?: factory.imp,
            bmi = bmi ?: factory.bmi,
        )

        companion object {
            /** 完全不校准（仅单测/对比原始值时用） */
            val NONE = Offsets()

            /**
             * 出厂默认偏移：已内置阿福的正确算法，开箱即用。
             *
             * 这两个值是 2026-11-17 用阿福 App 实测、连续多次验证固化下来的：
             * - 阻抗档 +3.8：Segal 算出约 14.4~15.0%，阿福显示 18.0~18.6%
             * - BMI 档 -0.8：Deurenberg 算出约 18.6%，阿福显示 18.0%
             *
             * 应用在 [com.example.b1garmin.MainActivity.savedOffsets] 里对「该秤没校准过的档」
             * 用这里的值兜底：新装/重装/换手机的用户不用再走一遍校准，直接和阿福对齐。
             * 用户真机已经校准过的值优先级更高，不会被这里的默认值覆盖。
             */
            val AFU_FACTORY = Offsets(imp = 3.8, bmi = -0.8)
        }
    }

    data class Estimate(
        val weightKg: Double, val bmi: Double, val bodyFatPercent: Double,
        val percentHydration: Double, val boneMassKg: Double, val muscleMassKg: Double,
        val basalMet: Int, val physiqueRating: Int, val metabolicAge: Int,
        val visceralFatRating: Int, val model: String, val resistanceOhm: Double?,
    ) {
        fun report(): String = buildString {
            appendLine("体重: %.2f kg   BMI: %.1f".format(weightKg, bmi))
            appendLine("体脂率: %.1f%%  模型: $model".format(bodyFatPercent))
            appendLine("体水分: %.1f%%  肌肉量: %.1f kg  骨量: %.1f kg".format(percentHydration, muscleMassKg, boneMassKg))
            appendLine("基础代谢: $basalMet kcal  体型评分: $physiqueRating  内脏脂肪: $visceralFatRating")
            appendLine("阻抗: ${resistanceOhm ?: "无"} Ω")
        }
    }

    // 阿福 App 的四分量比例（由实测数据反推）
    private const val WATER_PER_FFM = 0.6966
    private const val MINERAL_PER_FFM = 0.0522
    private const val KCAL_PER_KG_FFM = 30.0

    private fun isMale(sex: String) = sex.lowercase() in setOf("male", "m", "man", "男", "1")
    private fun clamp(v: Double, lo: Double, hi: Double) = v.coerceIn(lo, hi)

    private fun segalFfm(w: Double, h: Double, age: Int, male: Boolean, r: Double): Pair<Double, String> {
        val h2 = h * h
        if (male) {
            val ffm = 9.33285 + 0.00066360 * h2 - 0.02117 * r + 0.62854 * w - 0.12380 * age
            if (100.0 * (w - ffm) / w < 20.0) return ffm to "segal_3a_male_lt20"
            val ffm2 = 14.52435 + 0.00088580 * h2 - 0.02999 * r + 0.42688 * w - 0.07002 * age
            return ffm2 to "segal_3b_male_ge20"
        } else {
            val ffm = 10.43485 + 0.00064602 * h2 - 0.01397 * r + 0.42087 * w
            if (100.0 * (w - ffm) / w < 30.0) return ffm to "segal_3c_female_lt30"
            val ffm2 = 9.37938 + 0.00091186 * h2 - 0.01466 * r + 0.29990 * w - 0.07012 * age
            return ffm2 to "segal_3d_female_ge30"
        }
    }

    private fun deurenbergBf(w: Double, h: Double, age: Int, male: Boolean): Double {
        val bmi = w / Math.pow(h / 100.0, 2.0)
        return 1.20 * bmi + 0.23 * age - 10.8 * (if (male) 1 else 0) - 5.4
    }

    /** 未校准的原始体脂率（用于「校准」时和用户填的阿福数值对比） */
    fun rawBf(weightKg: Double, heightCm: Double, age: Int, sex: String,
              resistanceOhm: Double?): Pair<Double, String> {
        val male = isMale(sex)
        return if (resistanceOhm != null && resistanceOhm in 100.0..1500.0) {
            val (ffm, m) = segalFfm(weightKg, heightCm, age, male, resistanceOhm)
            val f = clamp(ffm, weightKg * 0.35, weightKg * 0.97)
            (100.0 * (weightKg - f) / weightKg) to m
        } else {
            deurenbergBf(weightKg, heightCm, age, male) to "deurenberg_bmi_fallback"
        }
    }

    /**
     * @param offsets 校准偏移，按模型自动选取（见 [Offsets]）
     */
    fun estimate(weightKg: Double, heightCm: Double, age: Int, sex: String,
                 resistanceOhm: Double?, offsets: Offsets = Offsets.NONE): Estimate? {
        if (weightKg <= 0 || heightCm <= 0) return null
        val male = isMale(sex)
        val (baseBf, model) = rawBf(weightKg, heightCm, age, sex, resistanceOhm)

        // 偏差是跟模型走的，所以只取同模型那一档；未校准时为 0（宁可不校，不串档）
        val bf = clamp(baseBf + offsets.forModel(model), if (male) 3.0 else 8.0, 60.0)
        val r1 = { x: Double -> (x * 10).roundToInt() / 10.0 }

        // 四分量模型：脂肪 + 去脂体重，去脂体重再拆水/蛋白/骨
        val fatMass = weightKg * bf / 100.0
        val lean = weightKg - fatMass
        val bone = lean * MINERAL_PER_FFM
        val muscle = lean - bone          // 肌肉量 = 去脂体重 − 骨量
        val water = lean * WATER_PER_FFM

        val physique = if (male) {
            when { bf < 10 -> 9; bf < 15 -> 8; bf < 20 -> 7; bf < 25 -> 6; bf < 30 -> 5; bf < 35 -> 4; bf < 40 -> 3; else -> 2 }
        } else {
            when { bf < 16 -> 9; bf < 20 -> 8; bf < 25 -> 7; bf < 30 -> 6; bf < 35 -> 5; bf < 40 -> 4; else -> 3 }
        }

        return Estimate(
            weightKg = r1(weightKg),
            bmi = r1(weightKg / Math.pow(heightCm / 100.0, 2.0)),
            bodyFatPercent = r1(bf),
            percentHydration = r1(water / weightKg * 100.0),
            boneMassKg = r1(bone),
            muscleMassKg = r1(muscle),
            basalMet = (KCAL_PER_KG_FFM * lean).toInt(),
            physiqueRating = physique,
            metabolicAge = age,
            visceralFatRating = (bf / 3.0).roundToInt().coerceIn(1, 9),
            model = model,
            resistanceOhm = resistanceOhm,
        )
    }
}
