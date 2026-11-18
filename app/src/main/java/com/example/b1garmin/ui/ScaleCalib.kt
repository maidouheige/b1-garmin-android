package com.example.b1garmin.ui

import android.content.Context
import org.json.JSONObject

/**
 * 一台秤对应的校准状态。
 *
 * 为什么按秤分开存：不同秤（不同型号、不同批次）的阻抗特性不一样，
 * 同一套偏移在 A 秤上是对的，换到 B 秤就偏了。
 * 所以校准值必须跟着「哪台秤」走 —— 换秤就重新校那台，
 * 之前那台的校准还在，随时切回去继续用。
 */
data class ScaleCalib(
    /** 阻抗档偏移（百分点） */
    val imp: Double? = null,
    /** 按体重估算那一档的偏移 */
    val bmi: Double? = null,
    /** 连续验证对得上的次数 */
    val streak: Int = 0,
    /** 是否已固化（跟阿福的标准对齐，不用再校准） */
    val frozen: Boolean = false,
    /** 最后一次校准/验证的时间 */
    val at: Long = 0L,
    /** 这台秤的名字（方便界面显示） */
    val name: String = "",
) {
    val calibrated: Boolean get() = imp != null || bmi != null
}

object CalibStore {
    private const val PREFS = "b1_calib"

    fun load(context: Context): MutableMap<String, ScaleCalib> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("map", null) ?: return mutableMapOf()
        return parse(raw)
    }

    fun save(context: Context, map: Map<String, ScaleCalib>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("map", dump(map)).apply()
    }

    /** JSON → 各台秤的校准状态（纯函数，方便单测） */
    fun parse(raw: String): MutableMap<String, ScaleCalib> = try {
        val root = JSONObject(raw)
        val out = mutableMapOf<String, ScaleCalib>()
        for (key in root.keys()) {
            val o = root.optJSONObject(key) ?: continue
            out[key] = ScaleCalib(
                imp = if (o.isNull("imp")) null else o.optDouble("imp"),
                bmi = if (o.isNull("bmi")) null else o.optDouble("bmi"),
                streak = o.optInt("streak"),
                frozen = o.optBoolean("frozen"),
                at = o.optLong("at"),
                name = o.optString("name"),
            )
        }
        out
    } catch (e: Exception) {
        mutableMapOf()
    }

    /** 各台秤的校准状态 → JSON（纯函数，方便单测） */
    fun dump(map: Map<String, ScaleCalib>): String {
        val root = JSONObject()
        map.forEach { (addr, c) ->
            root.put(
                addr,
                JSONObject()
                    .put("imp", c.imp ?: JSONObject.NULL)
                    .put("bmi", c.bmi ?: JSONObject.NULL)
                    .put("streak", c.streak)
                    .put("frozen", c.frozen)
                    .put("at", c.at)
                    .put("name", c.name)
            )
        }
        return root.toString()
    }

    fun of(map: Map<String, ScaleCalib>, addr: String): ScaleCalib =
        map[addr] ?: ScaleCalib()

    fun put(map: MutableMap<String, ScaleCalib>, addr: String, c: ScaleCalib) {
        if (addr.isBlank()) return
        map[addr] = c
    }
}
