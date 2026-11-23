package com.example.b1garmin.scale

import com.example.b1garmin.protocol.A1Protocol
import com.example.b1garmin.protocol.AfuProtocol
import com.example.b1garmin.scale.afu.AfuA1Protocol
import com.example.b1garmin.scale.afu.AfuB1Protocol
import com.example.b1garmin.scale.honor.HonorProtocol
import com.example.b1garmin.scale.xiaomi.XiaomiProtocol
import java.util.UUID

/**
 * 一个可接入的秤型号。新增品牌只改这里 + 新建一个协议实现，其它代码不用动。
 *
 * @param id 持久化用的型号标识（旧版本已存 "A1"/"B1"/"HONOR"，保持不变）
 * @param brand 品牌名，界面展示用
 * @param displayName 型号全名
 * @param protocol 该型号的协议实现（品牌之间完全隔离）
 * @param bodyComp 体成分口径，跟型号绑定
 * @param nameHints 广播名关键字（忽略大小写）
 * @param serviceUuids 广播里出现即认为是该型号
 * @param singleShot true = 称一次就结束、不循环重连（华为/荣耀这类官方逻辑）
 * @param experimental true = 实验性协议，界面会提示仅供参考
 * @param note 界面上的说明文案
 */
data class ScaleModel(
    val id: String,
    val brand: String,
    val displayName: String,
    val protocol: ScaleProtocol,
    val bodyComp: BodyCompStandard,
    val nameHints: List<String> = emptyList(),
    val serviceUuids: List<UUID> = emptyList(),
    val singleShot: Boolean = false,
    val experimental: Boolean = false,
    val note: String = "",
)

/**
 * 支持的秤清单（唯一权威）。
 *
 * 扫描识别、绑定打标、自动称重选协议，三处都只查这张表 —— 之前散落在
 * ScaleReader.detectModel / autoWeighLoop / 绑定页里的品牌判断全部收敛到这里。
 */
object ScaleCatalog {
    private val AFU_SERVICE = UUID.fromString(AfuProtocol.SERVICE_UUID)
    private val A1_SERVICE = UUID.fromString(A1Protocol.SERVICE_UUID)

    // ---------------- 已支持的型号 ----------------

    val B1 = ScaleModel(
        id = "B1",
        brand = "阿福 / 薄荷",
        displayName = "阿福体脂秤 B1",
        protocol = AfuB1Protocol,
        bodyComp = BodyCompStandard.AFU,
        nameHints = listOf(
            "AFU", "B1", "BH-TZ", "薄荷", "MINT", "SCALE", "TZ-B1",
        ),
        serviceUuids = listOf(AFU_SERVICE),
        note = "FC50 服务 · 完整握手/分块状态机",
    )

    val A1 = ScaleModel(
        id = "A1",
        brand = "沃莱 / ICOMON",
        displayName = "阿福体脂秤 A1（FG2508WB）",
        protocol = AfuA1Protocol,
        bodyComp = BodyCompStandard.AFU,
        nameHints = A1Protocol.NAME_HINTS,
        serviceUuids = listOf(A1_SERVICE),
        experimental = true,
        note = "FFB0 服务 · 实验性协议，体脂仅供参考",
    )

    val HONOR = ScaleModel(
        id = "HONOR",
        brand = "华为 / 荣耀",
        displayName = "荣耀体脂秤 2（LUP-B19）",
        protocol = HonorProtocol,
        bodyComp = BodyCompStandard.HUAWEI,
        nameHints = listOf("HONOR Scale", "LUP-"),
        singleShot = true,
        note = "华为私协议 · 体重/体脂/心率一次测完即结束",
    )

    /** 小米 S400：加密广播 MiBeacon，需用户填 32 位 BLE bindkey */
    val XIAOMI_S400 = ScaleModel(
        id = "XIAOMI_S400",
        brand = "小米 / 云米",
        displayName = "小米体脂秤 S400（MJTZC01YM）",
        protocol = XiaomiProtocol,
        bodyComp = BodyCompStandard.XIAOMI,
        nameHints = listOf("S400", "MI SCALE", "YUNMAI", "MJTZC01"),
        singleShot = true,
        experimental = true,
        note = "加密广播 · 需在绑定页填米家 BLE KEY；赤足可测双频阻抗",
    )

    /** 小米 S800：广播只能读体重，阻抗走加密 GATT（无公开方案），体脂按估算 */
    val XIAOMI_S800 = ScaleModel(
        id = "XIAOMI_S800",
        brand = "小米",
        displayName = "小米体脂秤 S800（MS116）",
        protocol = XiaomiProtocol,
        bodyComp = BodyCompStandard.XIAOMI,
        nameHints = listOf("S800", "MS116"),
        singleShot = true,
        experimental = true,
        note = "加密广播 · 仅能读体重，体脂按 BMI 估算（阻抗无公开方案）",
    )

    /** 匹配顺序：具体型号在前（A1 的 "AFU-WL" 含 "AFU"，小米要在通用之前） */
    val all: List<ScaleModel> = listOf(HONOR, XIAOMI_S800, XIAOMI_S400, A1, B1)

    /** 按绑定/存盘里的型号标识取型号；未知型号返回 null */
    fun byId(id: String?): ScaleModel? =
        id?.takeIf { it.isNotBlank() }?.let { v -> all.firstOrNull { it.id.equals(v, true) } }

    /**
     * 记录/列表里显示的品牌级名称：阿福体脂秤 / 荣耀体脂秤 / 小米体脂秤。
     * 不区分 B1/A1/S400/S800 这些具体型号——用户只关心是哪个牌子的秤。
     * @param modelOrBrand 记录里存的 model（displayName）或 brand，或型号 id
     */
    fun familyName(modelOrBrand: String?): String {
        val s = modelOrBrand.orEmpty()
        if (s.isBlank()) return "未知秤"
        return when {
            s.contains("小米", true) || s.contains("云米", true) || s.equals("XIAOMI_S400", true) ||
                    s.equals("XIAOMI_S800", true) -> "小米体脂秤"
            s.contains("荣耀", true) || s.contains("华为", true) || s.equals("HONOR", true) -> "荣耀体脂秤"
            s.contains("阿福", true) || s.contains("薄荷", true) || s.contains("沃莱", true) ||
                    s.contains("ICOMON", true) || s.equals("B1", true) || s.equals("A1", true) -> "阿福体脂秤"
            else -> s  // 真是别的牌子就原样显示
        }
    }

    /**
     * 按广播名和服务自动识别型号。
     * 返回 null = 这台设备不像我们支持的秤。
     */
    fun match(
        name: String,
        hasAfuService: Boolean = false,
        hasA1Service: Boolean = false,
    ): ScaleModel? {
        val services = buildList {
            if (hasAfuService) add(AFU_SERVICE)
            if (hasA1Service) add(A1_SERVICE)
        }
        return all.firstOrNull { m ->
            m.serviceUuids.any { it in services } ||
                    m.nameHints.any { name.contains(it, ignoreCase = true) }
        }
    }
}
