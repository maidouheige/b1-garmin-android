package com.example.b1garmin

import com.example.b1garmin.scale.ScaleCatalog
import org.junit.Assert.*
import org.junit.Test

/**
 * 记录页「型号标签」解析测试。
 *
 * 背景：老版本写记录时用了错误的 prefs key（cur_scale_model），型号一直为空，
 * 记录页就把所有秤都显示成「未知秤」。修复后写入按绑定型号 / 广播名解析，
 * 启动时再按 MAC 回填历史空型号。这里验证解析入口本身正确。
 */
class ScaleLabelTest {

    @Test
    fun byId_resolvesEveryRegisteredModel() {
        // 5 台已注册型号都要能按 id 取到，数量与「支持的秤」显示一致
        assertEquals(5, ScaleCatalog.all.size)
        for (id in listOf("B1", "A1", "HONOR", "XIAOMI_S400", "XIAOMI_S800")) {
            val m = ScaleCatalog.byId(id)
            assertNotNull("id=$id 应能解析", m)
            assertTrue("id=$id 的 displayName 不应为空", m!!.displayName.isNotBlank())
        }
    }

    @Test
    fun byId_unknown_returnsNull() {
        assertNull(ScaleCatalog.byId(""))
        assertNull(ScaleCatalog.byId(null))
        assertNull(ScaleCatalog.byId("NOT_A_SCALE"))
    }

    @Test
    fun match_byBroadcastName_resolvesXiaomiAndAfu() {
        // 记录写入时若不是绑定秤，按 cur_scale_name 广播名推断型号
        assertEquals("B1", ScaleCatalog.match("AFU-B1-1234")?.id)
        assertEquals("XIAOMI_S400", ScaleCatalog.match("MI SCALE S400")?.id)
        assertEquals("XIAOMI_S800", ScaleCatalog.match("MS116-ABCD")?.id)
        assertEquals("HONOR", ScaleCatalog.match("HONOR Scale LUP-B19")?.id)
    }

    @Test
    fun familyName_showsBrandOnly_notConcreteModel() {
        // 用户要的是品牌级显示：阿福体脂秤 / 荣耀体脂秤 / 小米体脂秤，不区分 B1/A1/S400
        assertEquals("阿福体脂秤", ScaleCatalog.familyName("阿福体脂秤 B1"))
        assertEquals("阿福体脂秤", ScaleCatalog.familyName("阿福体脂秤 A1（FG2508WB）"))
        assertEquals("荣耀体脂秤", ScaleCatalog.familyName("荣耀体脂秤 2（LUP-B19）"))
        assertEquals("小米体脂秤", ScaleCatalog.familyName("小米体脂秤 S400（MJTZC01YM）"))
        assertEquals("小米体脂秤", ScaleCatalog.familyName("小米体脂秤 S800（MS116）"))
        // 空值兜底未知秤
        assertEquals("未知秤", ScaleCatalog.familyName(""))
        assertEquals("未知秤", ScaleCatalog.familyName(null))
        // 未知品牌原样保留
        assertEquals("别的牌子", ScaleCatalog.familyName("别的牌子"))
    }
}
