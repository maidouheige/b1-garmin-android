package com.example.b1garmin.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次测量的「验证台账」。
 *
 * 每条记三件事：
 * - rawBf：通用 Segal 方程算出来的原始体脂率（没校准时的样子）
 * - calcBf：套用当前校准偏移之后的体脂率（= 实际上传进 Garmin 的那个值）
 * - afuBf：用户事后填进来的阿福数值（null = 还没对比过）
 *
 * 关键：对比时拿的是 **calcBf（测量当时那套标准）** 和阿福值比，
 * 不是拿校准后的新值重算一遍再比——后者必然是 0，等于自己骗自己。
 * 这样比出来的差值才是「离样本」的真差值。
 */
data class VerifyEntry(
    val ts: Long,
    val kg: Double,
    val res: Double,
    val rawBf: Double,
    val calcBf: Double,
    val afuBf: Double? = null,
    /** 这条是哪台秤测的（换秤后校准标准不同，不能混着比） */
    val addr: String = "",
)

object VerifyStore {
    private const val PREFS = "b1_verify"
    private const val MAX = 40

    /** 容差：|我们 − 阿福| ≤ 0.5 个百分点算「对得上」 */
    const val TOLERANCE = 0.5

    /**
     * 连续对得上的次数 + 这些次的最大差值。
     *
     * 按时间倒着数：最近一次对不上就断（中间断了就不算连续）。
     */
    fun streak(list: List<VerifyEntry>): Pair<Int, Double> {
        val compared = list.filter { it.afuBf != null }.sortedBy { it.ts }
        var streak = 0
        var maxDiff = 0.0
        for (i in compared.indices.reversed()) {
            val d = Math.abs(compared[i].afuBf!! - compared[i].calcBf)
            if (d <= TOLERANCE) {
                streak++
                maxDiff = maxOf(maxDiff, d)
            } else break
        }
        return streak to maxDiff
    }

    fun load(context: Context): MutableList<VerifyEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("list", null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<VerifyEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) continue
                list.add(
                    VerifyEntry(
                        ts = o.optLong("ts"),
                        kg = o.optDouble("kg"),
                        res = o.optDouble("res"),
                        rawBf = o.optDouble("raw"),
                        calcBf = o.optDouble("calc"),
                        afuBf = if (o.isNull("afu")) null else o.optDouble("afu"),
                        addr = o.optString("addr")
                    )
                )
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context, list: List<VerifyEntry>) {
        val arr = JSONArray()
        list.takeLast(MAX).forEach {
            arr.put(
                JSONObject()
                    .put("ts", it.ts).put("kg", it.kg).put("res", it.res)
                    .put("raw", it.rawBf).put("calc", it.calcBf)
                    .put("afu", it.afuBf ?: JSONObject.NULL)
                    .put("addr", it.addr)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("list", arr.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("list").apply()
    }
}
