package com.example.b1garmin.scale

import android.content.Context

/**
 * 所有品牌体脂秤的统一契约。
 *
 * 每接入一个新品牌，只需要：
 *  1. 在本包下新建一个实现 [ScaleProtocol] 的类（协议细节全部关在里面，互不影响）；
 *  2. 在 [ScaleCatalog] 里登记一条 [ScaleModel]（名字/服务匹配规则 + 体成分口径）。
 *
 * 界面和上传流程只认这里的两样东西：统一记录 [ScaleMeasurement] 和体成分口径
 * [BodyCompStandard]，不再出现 `if (是荣耀) ... else if (是A1) ...` 这种品牌分支。
 */
interface ScaleProtocol {
    /** 与 [ScaleModel.id] 对应，仅用于日志 */
    val id: String

    /**
     * 跑一次称重会话，返回本次拿到的记录（可能包含秤内历史记录）。
     *
     * @param address 秤的 MAC，已由扫描/绑定确定
     * @param name 广播名（部分协议用来识别）
     * @param standby true = 连上后长时间待命等用户上秤；false = 一次称完就断开
     * @param onLog 协议层日志（调用方负责切主线程）
     * @param onLive 实时体重回调（Binder 线程）
     * @param onImpedance 拿到有效阻抗时回调（Binder 线程）
     */
    suspend fun measure(
        context: Context,
        address: String,
        name: String,
        user: ScaleUser,
        standby: Boolean = true,
        onLog: (String) -> Unit = {},
        onLive: (kg: Double, locked: Boolean) -> Unit = { _, _ -> },
        onImpedance: (ohm: Double) -> Unit = { _ -> },
    ): List<ScaleMeasurement>
}

/** 喂给秤/算法的用户资料 */
data class ScaleUser(
    val age: Int,
    val male: Boolean,
    val heightCm: Int,
    val targetKg: Double,
)

/**
 * 统一称重记录。
 *
 * 各品牌字段差异（心率、秤端体脂率）保留为可空字段，没有该能力的品牌给 null，
 * 上层不用关心是哪台秤。
 */
data class ScaleMeasurement(
    val timestampSec: Long,
    val weightKg: Double,
    val resistanceOhm: Double? = null,
    /** true = 秤内历史记录；false = 本次实时称重 */
    val fromHistory: Boolean = false,
    /** 荣耀/华为秤会一并推心率，其它品牌没有 */
    val heartRateBpm: Int? = null,
    /** 荣耀/华为秤自己算好的体脂率，其它品牌没有 */
    val scaleFatPercent: Double? = null,
)

/** 体成分口径：不同品牌用不同公式，跟品牌绑定，绝不交叉 */
enum class BodyCompStandard {
    /** 阿福/薄荷等：Segal + 本项目校准偏移 */
    AFU,

    /** 华为/荣耀：秤端体脂优先，其余按华为实测比例拆 */
    HUAWEI,

    /** 小米：用秤端阻抗，体成分按米家口径估（分辨率比阿福粗） */
    XIAOMI,
}
