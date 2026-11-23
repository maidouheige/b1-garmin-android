package com.example.b1garmin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.b1garmin.data.MeasurementEntity
import com.example.b1garmin.data.UploadStatus
import com.example.b1garmin.protocol.BodyComp
import com.example.b1garmin.ui.theme.GColor
import com.example.b1garmin.ui.theme.GCard
import com.example.b1garmin.ui.theme.GChevron
import com.example.b1garmin.ui.theme.GCircleIconButton
import com.example.b1garmin.ui.theme.GIcon
import com.example.b1garmin.ui.theme.GIconKind
import com.example.b1garmin.ui.theme.GLead
import com.example.b1garmin.ui.theme.GPanel
import com.example.b1garmin.ui.theme.GPrimaryButton
import com.example.b1garmin.ui.theme.GRow
import com.example.b1garmin.ui.theme.GSectionHeader
import com.example.b1garmin.ui.theme.GTag
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Garmin 头像：有图显示图，没有就用名字首字。 */
@Composable
fun GAvatar(avatar: ImageBitmap?, name: String, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(GColor.Blue),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            Image(
                bitmap = avatar,
                contentDescription = "Garmin 头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                name.trim().take(1).uppercase().ifBlank { "G" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

/**
 * 首页：Garmin Connect 概览风。
 * 只负责展示与交互回调，称重状态仍由 MainActivity 持有。
 */
/**
 * 首页称重卡的阶段，参照华为运动健康「开始测量 → 连接 → 上秤 → 测量 → 完成」的流程。
 * 按钮只反映阶段，只有暂停后恢复、出错重连时才能点。
 */
enum class WeighStage {
    PAUSED,
    CONNECTING,
    CONNECTED,
    WAITING,
    MEASURING,
    FINALIZING,
    DONE,
    ERROR,
    UNBOUND,
}

@Composable
fun HomeScreen(
    avatar: ImageBitmap?,
    garminName: String,
    boundLabel: String?,
    autoListen: Boolean,
    liveKg: Double,
    liveLocked: Boolean,
    livePhase: String,
    weighStage: WeighStage,
    estimate: BodyComp.Estimate?,
    summary: String,
    logs: List<String>,
    showDebug: Boolean,
    lastCrash: String,
    recent: List<MeasurementEntity>?,
    onWeigh: () -> Unit,
    onToggleListen: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenRecords: () -> Unit,
    onToggleDebug: () -> Unit,
    onCopyLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onClearCrash: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(GColor.Bg)) {
        HomeAppBar(avatar, garminName, boundLabel)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            WeighHero(
                boundLabel = boundLabel,
                autoListen = autoListen,
                liveKg = liveKg,
                liveLocked = liveLocked,
                livePhase = livePhase,
                weighStage = weighStage,
                summary = summary,
                estimate = estimate,
                onWeigh = onWeigh,
                onToggleListen = onToggleListen,
                onOpenProfile = onOpenProfile,
            )
            if (estimate != null) {
                Spacer(Modifier.height(12.dp))
                MetricsGrid(estimate)
            }
            if (summary.startsWith("✓") || summary.startsWith("✗") || summary.startsWith("⚠")) {
                Spacer(Modifier.height(10.dp))
                SyncBar(summary)
            }
            Spacer(Modifier.height(20.dp))
            GSectionHeader("最近同步") {
                Text(
                    "查看全部",
                    style = MaterialTheme.typography.labelMedium,
                    color = GColor.Blue,
                    modifier = Modifier.clickable(onClick = onOpenRecords),
                )
            }
            RecentList(recent, onOpenRecords)
            if (lastCrash.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                GCard {
                    Text(
                        "上次打开时崩了，把下面这段发给我：",
                        style = MaterialTheme.typography.titleSmall,
                        color = GColor.Red,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        lastCrash,
                        style = MaterialTheme.typography.labelSmall,
                        color = GColor.Sub,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "清掉这段",
                        style = MaterialTheme.typography.labelMedium,
                        color = GColor.Blue,
                        modifier = Modifier.clickable(onClick = onClearCrash),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            ResultCard(
                summary = summary,
                logs = logs,
                showDebug = showDebug,
                onToggleDebug = onToggleDebug,
                onCopyLogs = onCopyLogs,
                onClearLogs = onClearLogs,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HomeAppBar(avatar: ImageBitmap?, garminName: String, boundLabel: String?) {
    // 顶栏恢复成原先样式：头像 + 标题 + 账号/秤信息，不加铃铛（账户入口已在「我的」tab）
    Column(Modifier.fillMaxWidth().background(GColor.Surface).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GAvatar(avatar, garminName, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "阿福体脂秤 → Garmin",
                    style = MaterialTheme.typography.titleLarge,
                    color = GColor.Text,
                    maxLines = 1,
                )
                Text(
                    buildString {
                        append(if (garminName.isNotBlank()) garminName else "未登录 Garmin")
                        boundLabel?.let { append(" · ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = GColor.Muted,
                    maxLines = 1,
                )
            }
        }
        HorizontalDivider(color = GColor.Line)
    }
}

@Composable
private fun WeighHero(
    boundLabel: String?,
    autoListen: Boolean,
    liveKg: Double,
    liveLocked: Boolean,
    livePhase: String,
    weighStage: WeighStage,
    summary: String,
    estimate: BodyComp.Estimate?,
    onWeigh: () -> Unit,
    onToggleListen: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    GCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GTag(
                text = boundLabel ?: "未绑定秤",
                fg = GColor.Blue,
                bg = GColor.BlueSoft,
            )
            Spacer(Modifier.weight(1f))
            // 阶段徽标（只读提示，按钮统一放卡片底部，避免两个按钮重复）
            GTag(
                text = when (weighStage) {
                    WeighStage.PAUSED -> "已暂停"
                    WeighStage.CONNECTING -> "连接中"
                    WeighStage.CONNECTED, WeighStage.WAITING -> "待上秤"
                    WeighStage.MEASURING -> "测量中"
                    WeighStage.FINALIZING -> "同步中"
                    WeighStage.DONE -> "已完成"
                    WeighStage.ERROR -> "连接异常"
                    WeighStage.UNBOUND -> "未绑定"
                },
                fg = when (weighStage) {
                    WeighStage.DONE -> GColor.Green
                    WeighStage.ERROR -> GColor.Red
                    else -> GColor.Blue
                },
                bg = when (weighStage) {
                    WeighStage.DONE -> GColor.GreenSoft
                    WeighStage.ERROR -> GColor.RedSoft
                    else -> GColor.BlueSoft
                },
                dot = true,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (liveKg > 0) "%.2f".format(liveKg) else "--.--",
                        style = MaterialTheme.typography.displayLarge,
                        color = GColor.Text,
                    )
                    Text(
                        "kg",
                        style = MaterialTheme.typography.titleSmall,
                        color = GColor.Muted,
                        modifier = Modifier.padding(start = 5.dp, bottom = 7.dp),
                    )
                }
                if (liveLocked) {
                    GTag("已锁定", fg = GColor.Green, bg = GColor.GreenSoft, dot = true)
                }
            }
            // 同步环去掉（点了没反应，同步状态在下方 SyncBar/结果卡里看）
        }
        Text(
            livePhase,
            style = MaterialTheme.typography.bodySmall,
            color = GColor.Muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(14.dp))
        // 单一按钮：监听中点「暂停称重」，暂停中点「继续称重」——始终可点，一步切换
        GPrimaryButton(
            text = if (autoListen) "暂停称重" else "继续称重",
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
            onClick = onToggleListen,
        )
    }
}

/** 右侧同步状态环：完成绿、失败红、警告橙、其余蓝。 */
@Composable
private fun SyncRing(summary: String, estimate: BodyComp.Estimate?) {
    val (color, sweep) = when {
        summary.startsWith("✓") -> GColor.Green to 360f
        summary.startsWith("✗") -> GColor.Red to 200f
        summary.startsWith("⚠") -> GColor.Amber to 240f
        estimate != null -> GColor.Green to 360f
        else -> GColor.Blue to 290f
    }
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawCircle(GColor.LineSoft, radius = size.minDimension / 2f)
            drawArc(
                color, -90f, sweep, false,
                topLeft = androidx.compose.ui.geometry.Offset(4.dp.toPx(), 4.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(
                    size.width - 8.dp.toPx(), size.height - 8.dp.toPx(),
                ),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 3.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                ),
            )
        }
        Icon(
            Icons.Default.Refresh,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun MetricsGrid(est: BodyComp.Estimate) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(Modifier.weight(1f), "体脂率", "%.1f".format(est.bodyFatPercent), "%", GIconKind.Target)
            MetricCard(Modifier.weight(1f), "骨骼肌", "%.1f".format(est.muscleMassKg), "kg", GIconKind.Bars)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard(Modifier.weight(1f), "水分", "%.1f".format(est.percentHydration), "%", GIconKind.Height)
            MetricCard(Modifier.weight(1f), "基础代谢", "${est.basalMet}", "kcal", GIconKind.Tune)
        }
        Text(
            buildString {
                append("BMI %.1f".format(est.bmi))
                append(" · 内脏脂肪 ${est.visceralFatRating}")
                est.resistanceOhm?.let { append(" · 阻抗 %.0fΩ".format(it)) }
            },
            style = MaterialTheme.typography.labelSmall,
            color = GColor.Muted,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MetricCard(modifier: Modifier, label: String, value: String, unit: String, icon: GIconKind) {
    GPanel(modifier, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GIcon(icon, modifier = Modifier.size(13.dp), tint = GColor.Blue)
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
            }
            Row(
                Modifier.padding(top = 5.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    color = GColor.Text,
                )
                Text(
                    unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = GColor.Muted,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun SyncBar(summary: String) {
    val (fg, bg) = when {
        summary.startsWith("✗") -> GColor.Red to GColor.RedSoft
        summary.startsWith("⚠") -> GColor.Amber to GColor.AmberSoft
        else -> GColor.Green to GColor.GreenSoft
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, tint = fg,
            modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            summary.lineSequence().firstOrNull().orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RecentList(recent: List<MeasurementEntity>?, onOpenAll: () -> Unit) {
    if (recent == null) {
        GPanel { Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
            Text("读取中…", style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
        } }
        return
    }
    if (recent.isEmpty()) {
        GPanel {
            Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.Center) {
                Text(
                    "还没有记录，站上秤称一次就会出现在这里",
                    style = MaterialTheme.typography.labelSmall,
                    color = GColor.Muted,
                )
            }
        }
        return
    }
    GPanel {
        recent.forEachIndexed { index, row ->
            val status = row.uploadStatus
            val (fg, bg) = when (status) {
                UploadStatus.UPLOADED -> GColor.Green to GColor.GreenSoft
                UploadStatus.FAILED -> GColor.Red to GColor.RedSoft
                UploadStatus.SKIPPED -> GColor.Amber to GColor.AmberSoft
                else -> GColor.Sub to GColor.LineSoft
            }
            GRow(
                title = "%.2f kg".format(row.weightKg),
                subtitle = "${formatDateTime(row.measuredAt)} · ${com.example.b1garmin.scale.ScaleCatalog.familyName(row.model.ifBlank { row.brand })}",
                lead = { GLead(GIconKind.Scale) },
                topDivider = index > 0,
                onClick = onOpenAll,
                trailing = {
                    GTag(
                        text = when (status) {
                            UploadStatus.UPLOADED -> "已上传"
                            UploadStatus.FAILED -> "失败"
                            UploadStatus.SKIPPED -> "跳过"
                            else -> "待上传"
                        },
                        fg = fg,
                        bg = bg,
                    )
                    Spacer(Modifier.width(4.dp))
                    GChevron()
                },
            )
        }
    }
}

@Composable
private fun ResultCard(
    summary: String,
    logs: List<String>,
    showDebug: Boolean,
    onToggleDebug: () -> Unit,
    onCopyLogs: () -> Unit,
    onClearLogs: () -> Unit,
) {
    GPanel {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("结果", style = MaterialTheme.typography.titleSmall, color = GColor.Text)
                Spacer(Modifier.weight(1f))
                Text(
                    if (showDebug) "收起日志" else "调试日志",
                    style = MaterialTheme.typography.labelSmall,
                    color = GColor.Blue,
                    modifier = Modifier.clickable(onClick = onToggleDebug),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                summary.ifBlank { "站上秤后数据会自动出现在这里，并同步到 Garmin。" },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    summary.startsWith("✗") -> GColor.Red
                    summary.startsWith("✓") -> GColor.Green
                    summary.startsWith("⚠") -> GColor.Amber
                    else -> GColor.Sub
                },
            )
            val live = logs.lastOrNull { !it.trimStart().startsWith("·") }
            if (!live.isNullOrBlank() && live != summary) {
                Spacer(Modifier.height(4.dp))
                Text(
                    live,
                    style = MaterialTheme.typography.labelSmall,
                    color = GColor.Muted,
                    maxLines = 2,
                )
            }
            if (showDebug) {
                Spacer(Modifier.height(8.dp))
                Text(
                    logs.joinToString("\n"),
                    style = MaterialTheme.typography.labelSmall,
                    color = GColor.Sub,
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    Text(
                        "复制日志",
                        style = MaterialTheme.typography.labelMedium,
                        color = GColor.Blue,
                        modifier = Modifier.clickable(onClick = onCopyLogs),
                    )
                    Spacer(Modifier.width(18.dp))
                    Text(
                        "清空（${logs.size} 行）",
                        style = MaterialTheme.typography.labelMedium,
                        color = GColor.Muted,
                        modifier = Modifier.clickable(onClick = onClearLogs),
                    )
                }
            }
        }
    }
}

private fun formatDateTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
