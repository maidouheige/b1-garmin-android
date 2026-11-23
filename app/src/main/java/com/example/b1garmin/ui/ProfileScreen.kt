package com.example.b1garmin.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.example.b1garmin.data.MeasurementEntity
import com.example.b1garmin.data.UploadStatus
import com.example.b1garmin.ui.theme.GColor
import com.example.b1garmin.ui.theme.GChevron
import com.example.b1garmin.ui.theme.GGroupTitle
import com.example.b1garmin.ui.theme.GIconKind
import com.example.b1garmin.ui.theme.GLead
import com.example.b1garmin.ui.theme.GPageBar
import com.example.b1garmin.ui.theme.GPanel
import com.example.b1garmin.ui.theme.GRow
import com.example.b1garmin.ui.theme.GTag
import com.example.b1garmin.ui.theme.ThemeStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 我的页：账户概览 + 身体资料 + 同步设置 + 蓝牙/设备 + 维护工具。
 * 所有数据都来自现有 Profile / 本地记录，不新增业务逻辑。
 */
@Composable
fun ProfileScreen(
    version: String,
    loggedIn: Boolean,
    regionLabel: String,
    avatar: ImageBitmap?,
    garminName: String,
    profile: Profile,
    recent: List<MeasurementEntity>?,
    autoListen: Boolean,
    boundLabel: String?,
    btState: () -> String,
    btReady: () -> Boolean,
    btDiag: () -> String?,
    onOpenLogin: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenCalib: () -> Unit,
    onOpenBind: () -> Unit,
    onToggleListen: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onRequestPermission: () -> Unit,
    onCheckUpdate: () -> Unit,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(GColor.Bg)) {
        GPageBar(kicker = "ACCOUNT", title = "我的")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            AccountCard(
                loggedIn = loggedIn,
                regionLabel = regionLabel,
                avatar = avatar,
                garminName = garminName,
                recent = recent,
                onOpenLogin = onOpenLogin,
            )

            GGroupTitle("身体资料")
            GPanel {
                GRow(
                    title = "身高",
                    subtitle = "用于 BMI 与体成分推算",
                    lead = { GLead(GIconKind.Height) },
                    onClick = onOpenProfile,
                    trailing = {
                        Text(
                            "%.0f".format(profile.heightCm),
                            style = MaterialTheme.typography.titleSmall,
                            color = GColor.Text,
                        )
                        Text(
                            " cm",
                            style = MaterialTheme.typography.labelSmall,
                            color = GColor.Muted,
                        )
                        Spacer(Modifier.width(6.dp))
                        GChevron()
                    },
                )
                GRow(
                    title = "出生日期",
                    subtitle = if (profile.hasBirthday) "自动换算为 ${profile.age} 岁" else "未设置",
                    lead = {
                        GLead {
                            Icon(
                                Icons.Default.DateRange,
                                contentDescription = null,
                                tint = GColor.Blue,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    },
                    topDivider = true,
                    onClick = onOpenProfile,
                    trailing = {
                        if (profile.hasBirthday) {
                            Text(
                                "%d-%02d".format(profile.birthYear, profile.birthMonth),
                                style = MaterialTheme.typography.titleSmall,
                                color = GColor.Text,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        GChevron()
                    },
                )
                GRow(
                    title = "性别",
                    subtitle = "用于基础代谢计算",
                    lead = { GLead(GIconKind.Sex) },
                    topDivider = true,
                    onClick = onOpenProfile,
                    trailing = {
                        Text(
                            profile.sexLabel,
                            style = MaterialTheme.typography.titleSmall,
                            color = GColor.Text,
                        )
                        Spacer(Modifier.width(6.dp))
                        GChevron()
                    },
                )
                GRow(
                    title = "参考体重",
                    subtitle = "用于校准对照",
                    lead = { GLead(GIconKind.Target) },
                    topDivider = true,
                    onClick = onOpenProfile,
                    trailing = {
                        Text(
                            if (profile.targetKg > 0) "%.1f".format(profile.targetKg) else "未设置",
                            style = MaterialTheme.typography.titleSmall,
                            color = GColor.Text,
                        )
                        if (profile.targetKg > 0) {
                            Text(
                                " kg",
                                style = MaterialTheme.typography.labelSmall,
                                color = GColor.Muted,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        GChevron()
                    },
                )
            }

            GGroupTitle("同步与上传")
            GPanel {
                GRow(
                    title = "自动称重",
                    subtitle = if (autoListen) "打开 App 就等秤，站上去即称" else "已暂停，点右侧恢复",
                    lead = { GLead(GIconKind.Tune) },
                    trailing = { GSwitch(checked = autoListen, onChange = { onToggleListen() }) },
                )
                GRow(
                    title = "称重后自动上传",
                    subtitle = "生成 FIT 并写入 Garmin",
                    lead = {
                        GLead {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = GColor.Blue,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    },
                    topDivider = true,
                    trailing = { GTag("已开启", fg = GColor.Green, bg = GColor.GreenSoft) },
                )
                GRow(
                    title = "体成分口径",
                    subtitle = "按当前秤的型号自动选择",
                    lead = { GLead(GIconKind.Shield) },
                    topDivider = true,
                    trailing = {
                        Text(
                            "自动",
                            style = MaterialTheme.typography.titleSmall,
                            color = GColor.Text,
                        )
                    },
                )
                GRow(
                    title = "去重窗口",
                    subtitle = "避免蓝牙重连造成重复记录",
                    lead = { GLead(GIconKind.Bars) },
                    topDivider = true,
                    trailing = {
                        Text(
                            "5 分钟",
                            style = MaterialTheme.typography.titleSmall,
                            color = GColor.Text,
                        )
                    },
                )
            }

            GGroupTitle("设备与蓝牙")
            GPanel {
                GRow(
                    title = "当前秤",
                    subtitle = boundLabel ?: "未绑定（按广播名自动识别）",
                    lead = { GLead(GIconKind.Scale) },
                    onClick = onOpenBind,
                    trailing = { GChevron() },
                )
                GRow(
                    title = "蓝牙",
                    subtitle = btState(),
                    lead = { GLead(GIconKind.Bluetooth) },
                    topDivider = true,
                    onClick = onEnableBluetooth,
                    trailing = {
                        GTag(
                            if (btReady()) "已开启" else "未开启",
                            fg = if (btReady()) GColor.Green else GColor.Red,
                            bg = if (btReady()) GColor.GreenSoft else GColor.RedSoft,
                        )
                    },
                )
                GRow(
                    title = "蓝牙权限",
                    subtitle = btDiag() ?: "权限正常",
                    lead = { GLead(GIconKind.Shield) },
                    topDivider = true,
                    onClick = onRequestPermission,
                    trailing = { GChevron() },
                )
            }

            GGroupTitle("外观")
            GPanel {
                ThemeOptionRow(
                    title = "跟随系统",
                    subtitle = "随手机的深色模式自动切换",
                    mode = ThemeStore.SYSTEM,
                    current = themeMode,
                    onSelect = onThemeModeChange,
                )
                ThemeOptionRow(
                    title = "浅色",
                    subtitle = "始终使用浅色界面",
                    mode = ThemeStore.LIGHT,
                    current = themeMode,
                    onSelect = onThemeModeChange,
                    topDivider = true,
                )
                ThemeOptionRow(
                    title = "深色",
                    subtitle = "始终使用深色界面",
                    mode = ThemeStore.DARK,
                    current = themeMode,
                    onSelect = onThemeModeChange,
                    topDivider = true,
                )
            }

            GGroupTitle("维护")
            GPanel {
                GRow(
                    title = "体脂率校准",
                    subtitle = "第一次使用、换秤或与阿福差得多时才需要",
                    lead = { GLead(GIconKind.Tune, bg = GColor.AmberSoft, fg = GColor.Amber) },
                    onClick = onOpenCalib,
                    trailing = { GChevron() },
                )
            }

            GGroupTitle("关于")
            GPanel {
                GRow(
                    title = "检查更新",
                    subtitle = "当前版本 v$version",
                    lead = {
                        GLead {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = GColor.Blue,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    },
                    onClick = onCheckUpdate,
                    trailing = { GChevron() },
                )
                GRow(
                    title = "支持的秤",
                    // 从 ScaleCatalog 动态取，新增型号不会再漏（之前硬编码成 3 台漏了小米）
                    subtitle = listOf(
                        "阿福 B1/A1",
                        "荣耀体脂秤 2",
                        "小米 S400/S800",
                    ).joinToString(" · "),
                    lead = { GLead(GIconKind.Scale) },
                    topDivider = true,
                    onClick = onOpenBind,
                    trailing = {
                        Text(
                            "${com.example.b1garmin.scale.ScaleCatalog.all.size} 台",
                            style = MaterialTheme.typography.titleSmall,
                            color = GColor.Text,
                        )
                        Spacer(Modifier.width(6.dp))
                        GChevron()
                    },
                )
            }

            Text(
                "非官方个人项目，与 Garmin / 蚂蚁集团 / 薄荷健康无关联。体脂为 BIA 估算值，仅供日常参考。",
                style = MaterialTheme.typography.labelSmall,
                color = GColor.Muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 18.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AccountCard(
    loggedIn: Boolean,
    regionLabel: String,
    avatar: ImageBitmap?,
    garminName: String,
    recent: List<MeasurementEntity>?,
    onOpenLogin: () -> Unit,
) {
    val uploaded = recent.orEmpty().filter { it.uploadStatus == UploadStatus.UPLOADED }
    val latest = uploaded.maxByOrNull { it.measuredAt }
    GPanel {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GAvatar(avatar, garminName, 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        garminName.ifBlank { if (loggedIn) "Garmin 已登录" else "Garmin 未登录" },
                        style = MaterialTheme.typography.titleLarge,
                        color = GColor.Text,
                        maxLines = 1,
                    )
                    Text(
                        "Garmin Connect · $regionLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = GColor.Muted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                GTag(
                    if (loggedIn) "已登录" else "未登录",
                    fg = if (loggedIn) GColor.Green else GColor.Red,
                    bg = if (loggedIn) GColor.GreenSoft else GColor.RedSoft,
                )
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = GColor.LineSoft)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                AccountStat("累计同步", "${uploaded.size} 次", Modifier.weight(1f), first = true)
                AccountStat("本地记录", "${recent?.size ?: 0} 条", Modifier.weight(1f))
                AccountStat(
                    "最近同步",
                    latest?.let { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it.measuredAt)) }
                        ?: "—",
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(GColor.Bg)
                    .clickable(onClick = onOpenLogin)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (loggedIn) "管理账户 / 退出登录" else "登录 Garmin",
                    style = MaterialTheme.typography.labelMedium,
                    color = GColor.Blue,
                )
                Spacer(Modifier.weight(1f))
                GChevron()
            }
        }
    }
}

@Composable
private fun AccountStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    first: Boolean = false,
) {
    Column(
        modifier.then(
            if (first) Modifier
            else Modifier.padding(start = 12.dp)
        )
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = GColor.Text,
            modifier = Modifier.padding(top = 3.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun ThemeOptionRow(
    title: String,
    subtitle: String,
    mode: String,
    current: String,
    onSelect: (String) -> Unit,
    topDivider: Boolean = false,
) {
    val selected = current == mode
    GRow(
        title = title,
        subtitle = subtitle,
        topDivider = topDivider,
        onClick = { onSelect(mode) },
        trailing = {
            Box(
                Modifier.size(20.dp).clip(CircleShape)
                    .background(if (selected) GColor.Blue else GColor.Track),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(GColor.OnBlue))
                }
            }
        },
    )
}

@Composable
private fun GSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(if (checked) 21.dp else 3.dp, label = "switch")
    Box(
        Modifier.size(42.dp, 24.dp)
            .clip(CircleShape)
            .background(if (checked) GColor.Blue else GColor.ToggleOff)
            .clickable { onChange(!checked) },
    ) {
        Box(
            Modifier.offset(x = x, y = 3.dp).size(18.dp)
                .clip(CircleShape).background(GColor.OnBlue)
        )
    }
}
