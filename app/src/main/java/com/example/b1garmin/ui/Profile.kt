package com.example.b1garmin.ui

import android.content.Context
import java.time.LocalDate

/**
 * 用户资料（一次设置，长期有效）。
 *
 * 为什么要从主界面挪走：身高/年龄/性别是算体成分的硬指标，
 * 在主界面用文本框容易被误触清空，重填一遍很烦。
 */
data class Profile(
    /** 身高 cm */
    val heightCm: Double = 174.5,
    /** 目标体重 kg（发给秤做基准）；0 = 不限，按 BMI 22 推算 */
    val targetKg: Double = 0.0,
    /** 出生日期；birthYear = 0 表示没填，改用 ageFallback */
    val birthYear: Int = 1991,
    val birthMonth: Int = 1,
    val birthDay: Int = 1,
    /** 没填出生日期时的兜底年龄 */
    val ageFallback: Int = 35,
    val male: Boolean = true,
) {
    /** 年龄：优先按出生日期算（生日没到要减一岁） */
    val age: Int
        get() = if (birthYear > 0) ageFrom(birthYear, birthMonth, birthDay) else ageFallback

    /** 内部用的性别标记（BodyComp / 秤协议认这两个值） */
    val sexText: String get() = if (male) "male" else "female"

    val sexLabel: String get() = if (male) "男" else "女"

    val hasBirthday: Boolean get() = birthYear > 0

    companion object {
        /**
         * 按出生日期算周岁。
         *
         * @param today 用于测试；默认取本机当天
         */
        fun ageFrom(y: Int, m: Int, d: Int, today: LocalDate = LocalDate.now()): Int {
            if (y <= 0) return 0
            var age = today.year - y
            // 今年的生日还没到 → 还没过生日
            if (today.monthValue < m || (today.monthValue == m && today.dayOfMonth < d)) age--
            return age.coerceIn(1, 120)
        }
    }
}

/**
 * 资料存取。
 *
 * 和旧的 "height" / "age" / "sex" 字段并存：老字段照旧写一份，
 * 这样即使以后再改代码路径，也不会读到空值。
 */
object ProfileStore {
    private const val PREFS = "b1_prefs"

    fun load(context: Context): Profile {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val height = p.getString("height_cm", null)?.toDoubleOrNull()
            ?: p.getString("height", null)?.toDoubleOrNull()
            ?: 174.5
        val male = if (p.contains("sex_male")) {
            p.getBoolean("sex_male", true)
        } else {
            !p.getString("sex", "male").orEmpty().equals("female", ignoreCase = true)
        }
        val birthYear = p.getInt("birth_y", 1991)
        val birthMonth = p.getInt("birth_m", 1)
        val birthDay = p.getInt("birth_d", 1)
        return Profile(
            heightCm = height.coerceIn(100.0, 230.0),
            targetKg = p.getFloat("target_kg", 0f).toDouble().coerceIn(0.0, 300.0),
            birthYear = if (birthYear in 1900..2100) birthYear else 1991,
            birthMonth = birthMonth.coerceIn(1, 12),
            birthDay = birthDay.coerceIn(1, 31),
            ageFallback = p.getInt("age", 35).coerceIn(1, 120),
            male = male,
        )
    }

    fun save(context: Context, profile: Profile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("height_cm", profile.heightCm.toString())
            .putString("height", profile.heightCm.toString())          // 兼容旧字段
            .putFloat("target_kg", profile.targetKg.toFloat())
            .putInt("birth_y", profile.birthYear)
            .putInt("birth_m", profile.birthMonth)
            .putInt("birth_d", profile.birthDay)
            .putInt("age", profile.age)                                 // 兼容旧字段
            .putBoolean("sex_male", profile.male)
            .putString("sex", profile.sexText)                           // 兼容旧字段
            .apply()
    }
}
