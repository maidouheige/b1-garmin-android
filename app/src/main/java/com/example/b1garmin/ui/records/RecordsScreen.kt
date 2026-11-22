package com.example.b1garmin.ui.records

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.b1garmin.data.MeasurementEntity
import com.example.b1garmin.data.MeasurementRepository
import com.example.b1garmin.data.MeasurementSource
import com.example.b1garmin.data.SkipReason
import com.example.b1garmin.data.UploadStatus
import com.example.b1garmin.protocol.BodyComp
import kotlin.Result
import com.example.b1garmin.ui.theme.GCircleIconButton
import com.example.b1garmin.ui.theme.GColor
import com.example.b1garmin.ui.theme.GGroupTitle
import com.example.b1garmin.ui.theme.GIcon
import com.example.b1garmin.ui.theme.GIconKind
import com.example.b1garmin.ui.theme.GLead
import com.example.b1garmin.ui.theme.GPageBar
import com.example.b1garmin.ui.theme.GPrimaryButton
import com.example.b1garmin.ui.theme.GRow
import com.example.b1garmin.ui.theme.GPanel
import com.example.b1garmin.ui.theme.GTag
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 本地记录列表 + 趋势 + 按秤筛选 + 详情 + 删除（Garmin 风）。
 *
 * 只读本地 Room 数据；删除也只删本地，不动 Garmin。
 */
@Composable
fun RecordsScreen(
    onRetry: suspend (MeasurementEntity) -> Result<Unit>,
) {
    val context = LocalContext.current
    val repo = remember { MeasurementRepository.get(context) }
    val scope = rememberCoroutineScope()
    val rows by repo.observeRecent(1000).collectAsState(initial = null)
    var detailId by remember { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<MeasurementEntity?>(null) }
    var retryingId by remember { mutableStateOf<Long?>(null) }
    var retryMessage by remember { mutableStateOf<String?>(null) }
    var rangeDays by rememberSaveable { mutableStateOf(30) }
    var metricName by rememberSaveable { mutableStateOf(TrendMetric.WEIGHT.name) }
    var scaleMac by rememberSaveable { mutableStateOf("") }
    var failedOnly by rememberSaveable { mutableStateOf(false) }
    var retryingAll by remember { mutableStateOf(false) }
    var batchMessage by remember { mutableStateOf<String?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var exportRange by rememberSaveable { mutableStateOf(0) } // 0=近30天 1=近90天 2=全部
    var exportFormat by rememberSaveable { mutableStateOf(RecordsExport.Format.BOTH.name) }
    var saveQueue by remember { mutableStateOf<List<RecordsExport.FilePart>>(emptyList()) }
    var savingPart by remember { mutableStateOf<RecordsExport.FilePart?>(null) }
    var saveTrigger by remember { mutableStateOf(0) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val part = savingPart
        if (uri != null && part != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(part.bytes) }
                    ?: error("无法写入 ${part.name}")
            }.onSuccess {
                Toast.makeText(context, "已保存 ${part.name}", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
        // 选了「两者」时，等回到页面后再弹下一个保存框
        val next = saveQueue.firstOrNull()
        if (uri != null && next != null) {
            savingPart = next
            saveQueue = saveQueue.drop(1)
            saveTrigger++
        } else {
            savingPart = null
            saveQueue = emptyList()
        }
    }
    LaunchedEffect(saveTrigger) {
        if (saveTrigger > 0) {
            // 给系统文档界面一点时间完全退出，否则第二次 launch 会被忽略
            delay(350)
            savingPart?.let { saveLauncher.launch(it.name) }
        }
    }
    val detail = rows?.firstOrNull { it.id == detailId }

    val data = rows.orEmpty()
    val filtered = remember(data, scaleMac) {
        if (scaleMac.isBlank()) data else data.filter { it.scaleMac == scaleMac }
    }
    val failedRows = remember(filtered) {
        filtered.filter { it.uploadStatus == UploadStatus.FAILED }
    }
    val visibleRows = remember(filtered, failedOnly) {
        if (failedOnly) filtered.filter { it.uploadStatus == UploadStatus.FAILED } else filtered
    }
    val nowMs = remember(data) { System.currentTimeMillis() }
    val rangeStart = remember(nowMs, rangeDays) { startOfDay(nowMs, rangeDays) }
    val metric = TrendMetric.values().firstOrNull { it.name == metricName } ?: TrendMetric.WEIGHT
    val points = remember(filtered, rangeStart, metric) {
        filtered.filter { it.measuredAt >= rangeStart }
            .mapNotNull { row -> metric.valueOf(row)?.let { row.measuredAt to it } }
            .sortedBy { it.first }
    }
    val scales = remember(data) {
        data.groupBy { it.scaleMac }
            .entries
            .sortedByDescending { e -> e.value.maxOf { it.measuredAt } }
            .map { e -> e.key to scaleLabel(e.value.first()) }
    }

    val detailRow = detail
    if (detailRow != null) {
        BackHandler { detailId = null }
        val previous = remember(detailRow.id, data) {
            data.filter { it.scaleMac == detailRow.scaleMac && it.measuredAt < detailRow.measuredAt }
                .maxByOrNull { it.measuredAt }
        }
        RecordDetailPage(
            row = detailRow,
            previous = previous,
            retrying = retryingId == detailRow.id,
            retryMessage = retryMessage,
            onBack = { detailId = null },
            onRetry = {
                retryMessage = null
                retryingId = detailRow.id
                scope.launch {
                    val result = onRetry(detailRow)
                    retryingId = null
                    if (result.isFailure) {
                        retryMessage = result.exceptionOrNull()?.message ?: "补传失败"
                    }
                }
            },
            onDelete = {
                detailId = null
                pendingDelete = detailRow
            },
        )
    } else {
        Column(Modifier.fillMaxSize().background(GColor.Bg)) {
            GPageBar(
                kicker = "TRENDS",
            title = "记录",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "导出",
                        style = MaterialTheme.typography.labelMedium,
                        color = GColor.Blue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { showExport = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (failedOnly) "${visibleRows.size} 条待补传" else "${filtered.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = GColor.Muted,
                    )
                }
            },
        )
        when {
            rows == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GColor.Blue, strokeWidth = 2.dp)
            }

            data.isEmpty() -> EmptyRecords(Modifier.fillMaxSize())

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    RangeSelector(selected = rangeDays, onSelect = { rangeDays = it })
                }
                item {
                    TrendCard(
                        points = points,
                        rangeDays = rangeDays,
                        metric = metric,
                        onMetricChange = { metricName = it.name },
                    )
                }
                item {
                    ScaleFilterRow(
                        scales = scales,
                        selected = scaleMac,
                        onSelect = { scaleMac = it },
                    )
                }
                if (failedRows.isNotEmpty()) {
                    item {
                        FailedBanner(
                            count = failedRows.size,
                            failedOnly = failedOnly,
                            retrying = retryingAll,
                            message = batchMessage,
                            onToggle = { failedOnly = !failedOnly },
                            onRetryAll = {
                                if (retryingAll) return@FailedBanner
                                batchMessage = null
                                retryingAll = true
                                scope.launch {
                                    var ok = 0
                                    var failed = 0
                                    failedRows.forEach { row ->
                                        retryingId = row.id
                                        val result = onRetry(row)
                                        if (result.isSuccess) ok++ else failed++
                                    }
                                    retryingId = null
                                    retryingAll = false
                                    batchMessage = if (failed == 0) {
                                        "已补传 $ok 条"
                                    } else {
                                        "成功 $ok 条，失败 $failed 条"
                                    }
                                }
                            },
                        )
                    }
                }
                item {
                    GPanel {
                        if (visibleRows.isEmpty()) {
                            Box(
                                Modifier.fillMaxWidth().height(82.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    if (failedOnly) "没有待补传的记录" else "这个区间还没有记录",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GColor.Muted,
                                )
                            }
                        } else {
                            visibleRows.forEachIndexed { index, row ->
                                RecordRow(
                                    row = row,
                                    topDivider = index > 0,
                                    onClick = {
                                        retryMessage = null
                                        detailId = row.id
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        "记录只显示体脂秤产生并写入 Garmin 的数据；失败记录可手动重试补传。",
                        style = MaterialTheme.typography.labelSmall,
                        color = GColor.Muted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                    )
                }
            }
        }
    }

    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除本地记录？") },
            text = {
                Text("只删除手机里的这一条记录，Garmin Connect 上的数据不会受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { runCatching { repo.delete(row.id) } }
                }) {
                    Text("删除", color = GColor.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (showExport) {
        val now = System.currentTimeMillis()
        val from = when (exportRange) {
            0 -> now - 30L * 86_400_000
            1 -> now - 90L * 86_400_000
            else -> Long.MIN_VALUE
        }
        val exportRows = filtered.filter { it.measuredAt >= from }
        val format = runCatching { RecordsExport.Format.valueOf(exportFormat) }
            .getOrDefault(RecordsExport.Format.BOTH)
        val scopeName = if (scaleMac.isBlank()) {
            "全部秤"
        } else {
            scales.firstOrNull { it.first == scaleMac }?.second ?: "当前秤"
        }
        ExportDialog(
            count = exportRows.size,
            scopeName = scopeName,
            range = exportRange,
            format = format,
            onRangeChange = { exportRange = it },
            onFormatChange = { exportFormat = it.name },
            onDismiss = { showExport = false },
            onShare = {
                runCatching { RecordsExport.build(exportRows, format) }
                    .onSuccess { result ->
                        showExport = false
                        runCatching { RecordsExport.share(context, result) }
                            .onFailure {
                                Toast.makeText(
                                    context, "分享失败：${it.message}", Toast.LENGTH_LONG,
                                ).show()
                            }
                    }
                    .onFailure {
                        Toast.makeText(
                            context, "导出失败：${it.message}", Toast.LENGTH_LONG,
                        ).show()
                    }
            },
            onSave = {
                runCatching { RecordsExport.build(exportRows, format) }
                    .onSuccess { result ->
                        showExport = false
                        savingPart = result.files.first()
                        saveQueue = result.files.drop(1)
                        saveTrigger++
                    }
                    .onFailure {
                        Toast.makeText(
                            context, "导出失败：${it.message}", Toast.LENGTH_LONG,
                        ).show()
                    }
            },
        )
    }
}

/** 导出选项：先选范围/格式，再决定分享还是保存到本地。 */
@Composable
private fun ExportDialog(
    count: Int,
    scopeName: String,
    range: Int,
    format: RecordsExport.Format,
    onRangeChange: (Int) -> Unit,
    onFormatChange: (RecordsExport.Format) -> Unit,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(GColor.Surface)
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Text("导出记录", style = MaterialTheme.typography.titleLarge, color = GColor.Text)
            Text(
                "$scopeName · $count 条",
                style = MaterialTheme.typography.labelSmall,
                color = GColor.Muted,
                modifier = Modifier.padding(top = 3.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text("时间范围", style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
            Spacer(Modifier.height(6.dp))
            SegmentedOptions(
                labels = listOf("近 30 天", "近 90 天", "全部"),
                selected = range,
                onSelect = onRangeChange,
            )
            Spacer(Modifier.height(12.dp))
            Text("格式", style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
            Spacer(Modifier.height(6.dp))
            SegmentedOptions(
                labels = listOf("CSV", "FIT", "两者"),
                selected = format.ordinal,
                onSelect = { onFormatChange(RecordsExport.Format.values()[it]) },
            )
            Text(
                when (format) {
                    RecordsExport.Format.CSV -> "CSV：Garmin 网页导入，只含体重 / BMI / 体脂"
                    RecordsExport.Format.FIT -> "FIT：全指标，可导入 Garmin Connect"
                    RecordsExport.Format.BOTH -> "CSV + FIT：两份文件都会生成"
                },
                style = MaterialTheme.typography.labelSmall,
                color = GColor.Muted,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("取消", color = GColor.Sub) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSave, enabled = count > 0) {
                    Text("保存到本地", color = GColor.Blue)
                }
                Spacer(Modifier.width(4.dp))
                GPrimaryButton(
                    text = "分享",
                    modifier = Modifier.width(88.dp),
                    enabled = count > 0,
                    height = 38.dp,
                    onClick = onShare,
                )
            }
        }
    }
}

@Composable
private fun SegmentedOptions(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GColor.Track)
            .padding(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier.weight(1f).height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) GColor.Surface else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) GColor.Text else GColor.Sub,
                )
            }
        }
    }
}

/** 失败补传提示条：一键重试全部失败记录，并可切换「只看失败」。 */
@Composable
private fun FailedBanner(
    count: Int,
    failedOnly: Boolean,
    retrying: Boolean,
    message: String?,
    onToggle: () -> Unit,
    onRetryAll: () -> Unit,
) {
    GPanel {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(GColor.Red))
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "$count 条记录未上传",
                        style = MaterialTheme.typography.labelMedium,
                        color = GColor.Text,
                    )
                    if (message != null) {
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (message.startsWith("已")) GColor.Green else GColor.Red,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (retrying) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        color = GColor.Blue,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        "全部重试",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = GColor.Blue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onRetryAll)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Text(
                if (failedOnly) "正在只看失败记录 · 点此查看全部" else "点此只看失败记录",
                style = MaterialTheme.typography.labelSmall,
                color = GColor.Blue,
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(top = 4.dp, bottom = 1.dp),
            )
        }
    }
}

@Composable
private fun RangeSelector(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GColor.Track)
            .padding(4.dp),
    ) {
        listOf(7, 30, 180, 365).forEach { days ->
            val active = days == selected
            Box(
                Modifier.weight(1f).height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) GColor.Surface else Color.Transparent)
                    .clickable { onSelect(days) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    rangeLabel(days),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) GColor.Blue else GColor.Sub,
                )
            }
        }
    }
}

/** 记录页趋势可切换的体成分指标。 */
private enum class TrendMetric(
    val chip: String,
    val label: String,
    val unit: String,
    val decimals: Int,
    val axisDecimals: Int,
    val upIsGood: Boolean,
) {
    WEIGHT("体重", "体重", "kg", 2, 1, false),
    FAT("体脂", "体脂率", "%", 1, 1, false),
    MUSCLE("肌肉", "骨骼肌", "kg", 1, 1, true),
    WATER("水分", "水分率", "%", 1, 1, true),
    VISCERAL("内脏", "内脏脂肪", "级", 0, 0, false),
    ;

    fun valueOf(row: MeasurementEntity): Double? = when (this) {
        WEIGHT -> row.weightKg
        FAT -> row.bodyFatPct
        MUSCLE -> row.muscleKg
        WATER -> row.waterPct
        VISCERAL -> row.visceralFat?.toDouble()
    }

    /** 曲线颜色随指标区分（按主题令牌取值，避免缓存成固定色）。 */
    val color: Color
        get() = when (this) {
            WEIGHT -> GColor.Blue
            FAT -> GColor.Amber
            MUSCLE -> GColor.Green
            WATER -> GColor.Blue
            VISCERAL -> GColor.Red
        }
}

@Composable
private fun MetricSelector(selected: TrendMetric, onSelect: (TrendMetric) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        TrendMetric.values().forEach { metric ->
            val active = metric == selected
            Box(
                Modifier.weight(1f).height(30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (active) GColor.BlueSoft else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (active) GColor.BorderBlue else GColor.Line,
                        shape = RoundedCornerShape(50),
                    )
                    .clickable { onSelect(metric) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    metric.chip,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) GColor.Blue else GColor.Sub,
                )
            }
        }
    }
}

@Composable
private fun TrendCard(
    points: List<Pair<Long, Double>>,
    rangeDays: Int,
    metric: TrendMetric,
    onMetricChange: (TrendMetric) -> Unit,
) {
    var selectedIdx by remember(points, rangeDays) { mutableStateOf<Int?>(null) }
    val sel = selectedIdx?.takeIf { it in points.indices }
    val shown = sel?.let { points[it] } ?: points.lastOrNull()

    val minW = points.minOfOrNull { it.second } ?: 0.0
    val maxW = points.maxOfOrNull { it.second } ?: 0.0
    val lo: Double
    val hi: Double
    if (metric.axisDecimals == 0) {
        // 整数指标（内脏脂肪）留出上下 1 级的余量，避免刻度过窄
        lo = minW.toInt() - 1.0
        hi = maxW.toInt() + 1.0
    } else {
        val spanW = (maxW - minW).let { if (it < 0.1) 0.6 else it }
        lo = minW - spanW * 0.25
        hi = maxW + spanW * 0.25
    }

    GPanel {
        Column(Modifier.padding(16.dp)) {
            MetricSelector(selected = metric, onSelect = onMetricChange)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (sel == null) "最新${metric.label}" else "选中记录",
                        style = MaterialTheme.typography.labelSmall,
                        color = GColor.Muted,
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            shown?.let { "%.${metric.decimals}f".format(it.second) } ?: "—",
                            style = MaterialTheme.typography.headlineMedium,
                            color = GColor.Text,
                        )
                        Text(
                            " ${metric.unit}",
                            style = MaterialTheme.typography.labelSmall,
                            color = GColor.Muted,
                            modifier = Modifier.padding(bottom = 4.dp, start = 2.dp),
                        )
                    }
                    Text(
                        sel?.let { fmtDayTime(points[it].first) } ?: "最新记录",
                        style = MaterialTheme.typography.labelSmall,
                        color = GColor.Muted,
                    )
                }
                if (points.size >= 2) {
                    DeltaPill(
                        delta = points.last().second - points.first().second,
                        unit = metric.unit,
                        decimals = metric.decimals,
                        upIsGood = metric.upIsGood,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (points.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(130.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${rangeLabel(rangeDays)}还没有${metric.label}记录",
                        style = MaterialTheme.typography.labelSmall,
                        color = GColor.Muted,
                    )
                }
            } else {
                Row {
                    MetricChart(
                        points = points,
                        lo = lo,
                        hi = hi,
                        selected = sel,
                        lineColor = metric.color,
                        onSelect = { selectedIdx = it },
                        modifier = Modifier.weight(1f).height(130.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(
                        Modifier.width(42.dp).height(130.dp).padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text("%.${metric.axisDecimals}f".format(hi), style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
                        Text("%.${metric.axisDecimals}f".format((hi + lo) / 2), style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
                        Text("%.${metric.axisDecimals}f".format(lo), style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().padding(end = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    axisTicks(points, rangeDays).forEach {
                        Text(fmtAxis(it, rangeDays), style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    if (points.isNotEmpty()) "${metric.label}趋势 · 可拖动查看" else "${metric.label}趋势",
                    style = MaterialTheme.typography.labelSmall,
                    color = GColor.Muted,
                )
                Spacer(Modifier.weight(1f))
                Text("单位 ${metric.unit}", style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
            }
        }
    }
}

@Composable
private fun DeltaPill(delta: Double, unit: String, decimals: Int, upIsGood: Boolean) {
    val eps = 0.5 * Math.pow(10.0, -decimals.toDouble())
    val good = if (upIsGood) delta > eps else delta < -eps
    val bad = if (upIsGood) delta < -eps else delta > eps
    val color = when {
        good -> GColor.Green
        bad -> GColor.Red
        else -> GColor.Muted
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
        Text(
            "%+.${decimals}f".format(delta) + " $unit",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MetricChart(
    points: List<Pair<Long, Double>>,
    lo: Double,
    hi: Double,
    selected: Int?,
    lineColor: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridColor = GColor.LineSoft
    val markerColor = GColor.Muted.copy(alpha = 0.55f)
    Canvas(
        modifier
            .pointerInput(points) {
                detectTapGestures { pos ->
                    onSelect(nearestPointIndex(pos.x, size.width.toFloat(), points, 6.dp.toPx()))
                }
            }
            .pointerInput(points) {
                detectDragGestures(
                    onDragStart = { pos ->
                        onSelect(nearestPointIndex(pos.x, size.width.toFloat(), points, 6.dp.toPx()))
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        onSelect(nearestPointIndex(change.position.x, size.width.toFloat(), points, 6.dp.toPx()))
                    },
                )
            },
    ) {
        val padX = 6.dp.toPx()
        val padTop = 6.dp.toPx()
        val padBottom = 6.dp.toPx()
        val usableW = size.width - padX * 2
        val usableH = size.height - padTop - padBottom
        fun yOf(v: Double) = (padTop + usableH * ((hi - v) / (hi - lo)).toFloat())

        repeat(3) { i ->
            val y = padTop + usableH * i / 2f
            drawLine(gridColor, Offset(padX, y), Offset(size.width - padX, y), 1.dp.toPx())
        }

        val tFirst = points.first().first.toDouble()
        val tLast = points.last().first.toDouble()
        val timeSpan = tLast - tFirst
        fun xOf(i: Int): Float = if (points.size == 1) {
            size.width / 2f
        } else if (timeSpan > 0) {
            padX + usableW * ((points[i].first - tFirst) / timeSpan).toFloat()
        } else {
            padX + usableW * i / (points.size - 1f)
        }

        if (points.size == 1) {
            drawCircle(lineColor, 4.dp.toPx(), Offset(xOf(0), yOf(points[0].second)))
        } else {
            val coords = points.indices.map { Offset(xOf(it), yOf(points[it].second)) }
            val bottom = size.height - padBottom

            val fill = Path().apply {
                moveTo(coords.first().x, bottom)
                coords.forEach { lineTo(it.x, it.y) }
                lineTo(coords.last().x, bottom)
                close()
            }
            drawPath(
                fill,
                Brush.verticalGradient(
                    listOf(lineColor.copy(alpha = 0.14f), lineColor.copy(alpha = 0f)),
                    startY = padTop,
                    endY = bottom,
                ),
            )

            val stroke = Path().apply {
                moveTo(coords.first().x, coords.first().y)
                coords.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(
                stroke,
                lineColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            if (points.size <= 60) {
                coords.forEach { drawCircle(lineColor.copy(alpha = 0.45f), 1.6.dp.toPx(), it) }
            }
            drawCircle(Color.White, 4.dp.toPx(), coords.last())
            drawCircle(lineColor, 2.5.dp.toPx(), coords.last())

            selected?.let { idx ->
                if (idx in points.indices && idx != points.lastIndex) {
                    val x = xOf(idx)
                    drawLine(markerColor, Offset(x, padTop), Offset(x, bottom), 1.dp.toPx())
                    drawCircle(Color.White, 5.dp.toPx(), Offset(x, yOf(points[idx].second)))
                    drawCircle(lineColor, 3.dp.toPx(), Offset(x, yOf(points[idx].second)))
                }
            }
        }
    }
}

/** 找到触摸点横坐标最近的数据点下标。 */
private fun nearestPointIndex(x: Float, width: Float, points: List<Pair<Long, Double>>, padX: Float): Int {
    if (points.size <= 1) return 0
    val usableW = (width - padX * 2).coerceAtLeast(1f)
    val frac = ((x - padX) / usableW).coerceIn(0f, 1f)
    val tFirst = points.first().first
    val tLast = points.last().first
    if (tLast == tFirst) {
        return (frac * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex)
    }
    val target = tFirst + (tLast - tFirst) * frac.toDouble()
    return points.indices.minByOrNull { abs(points[it].first - target) } ?: 0
}

/** 横轴刻度：取 4 个近似等距的数据点，标签重复时合并。 */
private fun axisTicks(points: List<Pair<Long, Double>>, rangeDays: Int): List<Long> {
    if (points.size <= 1) return points.map { it.first }
    return listOf(0, points.size / 3, points.size * 2 / 3, points.lastIndex)
        .map { points[it].first }
        .distinctBy { fmtAxis(it, rangeDays) }
}

@Composable
private fun ScaleFilterRow(
    scales: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "同步历史",
                style = MaterialTheme.typography.titleMedium,
                color = GColor.Text,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "按秤筛选",
                style = MaterialTheme.typography.labelSmall,
                color = GColor.Muted,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            FilterPill("全部", selected.isBlank()) { onSelect("") }
            scales.forEach { (mac, label) ->
                FilterPill(label, selected == mac) { onSelect(mac) }
            }
        }
    }
}

@Composable
private fun FilterPill(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (active) GColor.Blue else GColor.Surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, if (active) GColor.Blue else GColor.Line,
        ),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (active) Color.White else GColor.Sub,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyRecords(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(GColor.Bg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = GColor.Muted,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("还没有本地记录", style = MaterialTheme.typography.titleMedium, color = GColor.Text)
        Spacer(Modifier.height(6.dp))
        Text(
            "站上秤称一次，结果会自动保存在这里；上传失败也会保留。",
            style = MaterialTheme.typography.labelSmall,
            color = GColor.Muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecordRow(row: MeasurementEntity, topDivider: Boolean, onClick: () -> Unit) {
    Column {
        if (topDivider) {
            HorizontalDivider(
                Modifier.padding(start = 16.dp, end = 16.dp),
                thickness = 1.dp,
                color = GColor.LineSoft,
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(GColor.BlueSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = GColor.Blue,
                    modifier = Modifier.size(17.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "%.2f".format(row.weightKg),
                        style = MaterialTheme.typography.titleMedium,
                        color = GColor.Text,
                    )
                    Text(
                        " kg",
                        style = MaterialTheme.typography.labelSmall,
                        color = GColor.Muted,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
                Text(
                    "${formatTime(row.measuredAt)} · ${row.model.ifBlank { row.brand.ifBlank { "未知型号" } }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = GColor.Muted,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    "体脂 ${fmt1(row.bodyFatPct)}% · 肌肉 ${fmt1(row.muscleKg)}kg",
                    style = MaterialTheme.typography.labelSmall,
                    color = GColor.Sub,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp),
                )
                row.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = GColor.Red,
                        maxLines = 1,
                    )
                }
            }
            StatusPill(row.uploadStatus)
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val (fg, bg) = when (status) {
        UploadStatus.UPLOADED -> GColor.Green to GColor.GreenSoft
        UploadStatus.FAILED -> GColor.Red to GColor.RedSoft
        UploadStatus.SKIPPED -> GColor.Amber to GColor.AmberSoft
        else -> GColor.Sub to GColor.LineSoft
    }
    GTag(statusLabel(status), fg = fg, bg = bg, dot = status == UploadStatus.UPLOADED)
}

@Composable
private fun RecordDetailPage(
    row: MeasurementEntity,
    previous: MeasurementEntity?,
    retrying: Boolean,
    retryMessage: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(GColor.Bg)) {
        // 顶栏：返回 + 标题 + 删除
        Column(Modifier.fillMaxWidth().background(GColor.Surface).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GCircleIconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = GColor.Sub,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "RECORD",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                        ),
                        color = GColor.Muted,
                    )
                    Text(
                        "测量记录",
                        style = MaterialTheme.typography.titleLarge,
                        color = GColor.Text,
                    )
                }
                GCircleIconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除这条记录",
                        tint = GColor.Red,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            HorizontalDivider(color = GColor.Line)
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            // 顶部：体重 + 与上次的差值 + BMI 标尺
            WeightHero(row, previous)
            Spacer(Modifier.height(10.dp))

            // 脂肪 / 去脂体重的构成条
            CompositionCard(row)
            Spacer(Modifier.height(10.dp))

            val score = BodyComp.huaweiHealthScore(row.sex.orEmpty(), row.bodyFatPct)
            if (score != null) {
                ScoreCard(score)
                Spacer(Modifier.height(10.dp))
            }

            MetricsPanel(row)
            Spacer(Modifier.height(6.dp))
            Text(
                "体脂、肌肉、水分等由 BIA 估算，仅供日常参考；与医疗设备结果可能有差异。",
                style = MaterialTheme.typography.labelSmall,
                color = GColor.Muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )

            // Garmin 同步
            GGroupTitle("Garmin 同步")
            GPanel {
                GRow(
                    title = "同步状态",
                    subtitle = when (row.uploadStatus) {
                        UploadStatus.UPLOADED -> "已写入 Garmin Connect"
                        UploadStatus.FAILED -> "上传失败，可重试"
                        UploadStatus.SKIPPED -> "按规则跳过，未上传"
                        else -> "等待上传"
                    },
                    lead = { GLead(GIconKind.Shield) },
                    trailing = { StatusPill(row.uploadStatus) },
                )
                row.garminSamplePk?.let {
                    GRow(
                        title = "Garmin 记录 ID",
                        subtitle = it.toString(),
                        lead = { GLead(GIconKind.Bars) },
                        topDivider = true,
                    )
                }
                row.skipReason?.let {
                    GRow(
                        title = "跳过原因",
                        subtitle = skipLabel(it),
                        lead = { GLead(GIconKind.Shield) },
                        topDivider = true,
                    )
                }
                row.error?.let {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            "错误信息",
                            style = MaterialTheme.typography.labelSmall,
                            color = GColor.Muted,
                        )
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = GColor.Red,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                if (row.uploadStatus == UploadStatus.FAILED) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        GPrimaryButton(
                            text = if (retrying) "上传中…" else "重新上传",
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !retrying,
                            onClick = onRetry,
                        )
                        retryMessage?.let {
                            Text(
                                "补传失败：$it",
                                style = MaterialTheme.typography.labelSmall,
                                color = GColor.Red,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            // 身体资料
            val bodyItems = listOfNotNull(
                row.heightCm?.let { "身高" to "$it cm" },
                row.age?.let { "年龄" to "$it 岁" },
                row.sex?.takeIf { it.isNotBlank() }?.let { "性别" to sexLabel(it) },
            )
            if (bodyItems.isNotEmpty()) {
                GGroupTitle("测量时身体资料")
                GPanel {
                    bodyItems.forEachIndexed { index, item ->
                        GRow(
                            title = item.first,
                            topDivider = index > 0,
                            trailing = {
                                Text(
                                    item.second,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = GColor.Text,
                                )
                            },
                        )
                    }
                }
            }

            // 数据来源
            GGroupTitle("数据来源")
            GPanel {
                GRow(
                    title = "设备",
                    subtitle = "${row.brand} ${row.model}".trim().ifBlank { "—" },
                    lead = { GLead(GIconKind.Scale) },
                )
                GRow(
                    title = "MAC 地址",
                    subtitle = row.scaleMac,
                    lead = { GLead(GIconKind.Bars) },
                    topDivider = true,
                )
                GRow(
                    title = "来源",
                    subtitle = "${sourceLabel(row.source)} · ${formatFullTime(row.measuredAt)}",
                    lead = { GLead(GIconKind.Shield) },
                    topDivider = true,
                )
            }

            // 删除
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = GColor.Surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, GColor.DangerBorder),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDelete),
            ) {
                Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "删除这条本地记录",
                        style = MaterialTheme.typography.labelLarge,
                        color = GColor.Red,
                    )
                }
            }
            Text(
                "只删除手机里的这一条，Garmin Connect 上的数据不受影响。",
                style = MaterialTheme.typography.labelSmall,
                color = GColor.Muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 体重主卡：大号体重 + 与上次的差值 + 线性 BMI 标尺。 */
@Composable
private fun WeightHero(row: MeasurementEntity, previous: MeasurementEntity?) {
    val status = bmiStatus(row.bmi)
    val delta = previous?.let { row.weightKg - it.weightKg }
    GPanel {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "体重",
                    style = MaterialTheme.typography.labelMedium,
                    color = GColor.Muted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatTime(row.measuredAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = GColor.Muted,
                )
            }
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "%.2f".format(row.weightKg),
                    style = MaterialTheme.typography.displayLarge,
                    color = GColor.Text,
                )
                Text(
                    " kg",
                    style = MaterialTheme.typography.titleSmall,
                    color = GColor.Muted,
                    modifier = Modifier.padding(start = 3.dp, bottom = 9.dp),
                )
                Spacer(Modifier.weight(1f))
                delta?.let { DeltaChip(it) }
            }
            Spacer(Modifier.height(14.dp))
            BmiScaleBar(row.bmi)
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BMI", style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
                Text(
                    fmt1(row.bmi),
                    style = MaterialTheme.typography.titleMedium,
                    color = GColor.Text,
                    modifier = Modifier.padding(start = 6.dp),
                )
                if (status != null) {
                    Spacer(Modifier.width(6.dp))
                    GTag(status.first, fg = status.second, bg = softBg(status.second))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${row.brand} ${row.model}".trim().ifBlank { "未知设备" },
                    style = MaterialTheme.typography.labelSmall,
                    color = GColor.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 与上次相比的差值胶囊。 */
@Composable
private fun DeltaChip(delta: Double) {
    val (fg, bg, text) = when {
        delta < -0.005 -> Triple(GColor.Green, GColor.GreenSoft, "↓ %.2f kg".format(-delta))
        delta > 0.005 -> Triple(GColor.Red, GColor.RedSoft, "↑ %.2f kg".format(delta))
        else -> Triple(GColor.Muted, GColor.LineSoft, "与上次持平")
    }
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(bg)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

/** 线性 BMI 标尺：四段颜色 + 当前位置圆点。 */
@Composable
private fun BmiScaleBar(bmi: Double?) {
    val colors = listOf(GColor.Blue, GColor.Green, GColor.Amber, GColor.Red)
    val labels = listOf("偏低", "标准", "超重", "肥胖")
    val markerColor = bmiStatus(bmi)?.second ?: GColor.Muted
    Column {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val gap = 3.dp.toPx()
            val segW = (size.width - gap * 3) / 4f
            val barH = 6.dp.toPx()
            colors.forEachIndexed { i, color ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * (segW + gap), 0f),
                    size = Size(segW, barH),
                    cornerRadius = CornerRadius(barH / 2f),
                )
            }
            val x = bmiFraction(bmi) * size.width
            drawCircle(Color.White, 6.dp.toPx(), Offset(x, barH / 2f))
            drawCircle(markerColor, 3.5.dp.toPx(), Offset(x, barH / 2f))
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors[i],
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 脂肪 / 去脂体重构成条。 */
@Composable
private fun CompositionCard(row: MeasurementEntity) {
    val fatPct = row.bodyFatPct
    val fatKg = fatPct?.let { row.weightKg * it / 100.0 }
    val leanKg = fatKg?.let { row.weightKg - it }
    GGroupTitle("身体成分")
    GPanel {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("脂肪", style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
                    Text(
                        fatKg?.let { "%.1f kg".format(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = GColor.Text,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("去脂体重", style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
                    Text(
                        leanKg?.let { "%.1f kg".format(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = GColor.Text,
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                val h = size.height
                drawRoundRect(
                    color = GColor.Blue,
                    size = Size(size.width, h),
                    cornerRadius = CornerRadius(h / 2f),
                )
                val f = ((fatPct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
                if (f > 0f) {
                    drawRoundRect(
                        color = GColor.Amber,
                        size = Size(size.width * f, h),
                        cornerRadius = CornerRadius(h / 2f),
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(GColor.Amber, "脂肪 ${fatPct?.let { fmt1(it) + "%" } ?: "—"}")
                Spacer(Modifier.width(16.dp))
                LegendDot(GColor.Blue, "去脂 ${fatPct?.let { fmt1(100 - it) + "%" } ?: "—"}")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
    }
}

/** 身体得分卡。 */
@Composable
private fun ScoreCard(score: Int) {
    val (label, color) = when {
        score >= 90 -> "优秀" to GColor.Green
        score >= 75 -> "良好" to GColor.Blue
        score >= 60 -> "一般" to GColor.Amber
        else -> "偏低" to GColor.Red
    }
    GPanel {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("身体得分", style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        score.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = GColor.Text,
                    )
                    Text(
                        " / 100",
                        style = MaterialTheme.typography.labelSmall,
                        color = GColor.Muted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.width(96.dp).height(6.dp).clip(RoundedCornerShape(50))
                        .background(GColor.LineSoft)
                ) {
                    Box(
                        Modifier.fillMaxWidth(score / 100f).height(6.dp)
                            .clip(RoundedCornerShape(50)).background(color)
                    )
                }
            }
        }
    }
}

private data class MetricItem(
    val label: String,
    val value: String,
    val unit: String,
    val status: Pair<String, Color>?,
)

/** 身体指标：两列规格表，不用华为那种三列彩色格子。 */
@Composable
private fun MetricsPanel(row: MeasurementEntity) {
    val bodyType = BodyComp.huaweiBodyType(
        row.sex.orEmpty(), row.bmi, row.bodyFatPct, row.muscleKg, row.weightKg,
    )
    val items = listOfNotNull(
        MetricItem("体脂率", fmt1(row.bodyFatPct), "%", fatStatus(row.bodyFatPct, row.sex)),
        MetricItem("骨骼肌", fmt1(row.muscleKg), "kg", null),
        MetricItem("水分率", fmt1(row.waterPct), "%", waterStatus(row.waterPct, row.sex)),
        MetricItem("骨盐量", fmt1(row.boneKg), "kg", null),
        MetricItem("内脏脂肪", row.visceralFat?.toString() ?: "—", "级", visceralStatus(row.visceralFat)),
        MetricItem("基础代谢", row.bmrKcal?.toString() ?: "—", "kcal", null),
        MetricItem("心率", row.heartRateBpm?.toString() ?: "—", "bpm", heartStatus(row.heartRateBpm)),
        MetricItem("阻抗", row.impedanceOhm?.let { "%.0f".format(it) } ?: "—", "Ω", null),
        MetricItem("代谢年龄", row.metabolicAge?.toString() ?: "—", "岁", null),
        bodyType?.let { MetricItem("体型", it, "", null) },
    )
    GGroupTitle("身体指标")
    GPanel {
        Column {
            items.chunked(2).forEachIndexed { index, pair ->
                if (index > 0) {
                    HorizontalDivider(
                        color = GColor.LineSoft,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetricCell(pair[0], Modifier.weight(1f))
                    if (pair.size > 1) {
                        Box(Modifier.width(1.dp).height(28.dp).background(GColor.LineSoft))
                        Spacer(Modifier.width(14.dp))
                        MetricCell(pair[1], Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCell(item: MetricItem, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(item.label, style = MaterialTheme.typography.labelSmall, color = GColor.Muted)
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                item.value,
                style = MaterialTheme.typography.titleMedium,
                color = GColor.Text,
                maxLines = 1,
            )
            if (item.unit.isNotEmpty()) {
                Text(
                    " ${item.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = GColor.Muted,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            item.status?.let {
                Text(
                    " · ${it.first}",
                    style = MaterialTheme.typography.labelSmall,
                    color = it.second,
                    modifier = Modifier.padding(bottom = 2.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 状态色对应的浅底色。 */
private fun softBg(color: Color): Color = when (color) {
    GColor.Blue -> GColor.BlueSoft
    GColor.Green -> GColor.GreenSoft
    GColor.Amber -> GColor.AmberSoft
    GColor.Red -> GColor.RedSoft
    else -> GColor.LineSoft
}

/** BMI 在线性标尺上的位置（0~1）。 */
private fun bmiFraction(bmi: Double?): Float {
    val v = (bmi ?: 22.0).coerceIn(14.0, 40.0)
    val f = when {
        v < 18.5 -> (v - 14) / 4.5 * 0.25
        v < 24 -> 0.25 + (v - 18.5) / 5.5 * 0.25
        v < 28 -> 0.5 + (v - 24) / 4.0 * 0.25
        else -> 0.75 + (v - 28) / 12.0 * 0.25
    }
    return f.toFloat().coerceIn(0f, 1f)
}

private fun bmiStatus(bmi: Double?): Pair<String, Color>? {
    if (bmi == null) return null
    return when {
        bmi < 18.5 -> "偏低" to GColor.Blue
        bmi < 24 -> "标准" to GColor.Green
        bmi < 28 -> "超重" to GColor.Amber
        else -> "肥胖" to GColor.Red
    }
}

private fun fatStatus(pct: Double?, sex: String?): Pair<String, Color>? {
    if (pct == null) return null
    val label = sexLabel(sex)
    val (low, mid, high) = when (label) {
        "男" -> Triple(10.0, 20.0, 25.0)
        "女" -> Triple(20.0, 30.0, 35.0)
        else -> return null
    }
    return when {
        pct < low -> "偏低" to GColor.Blue
        pct < mid -> "标准" to GColor.Green
        pct < high -> "偏高" to GColor.Amber
        else -> "过高" to GColor.Red
    }
}

private fun visceralStatus(level: Int?): Pair<String, Color>? {
    if (level == null) return null
    return when {
        level <= 9 -> "标准" to GColor.Green
        level <= 14 -> "偏高" to GColor.Amber
        else -> "过高" to GColor.Red
    }
}

private fun waterStatus(pct: Double?, sex: String?): Pair<String, Color>? {
    if (pct == null) return null
    val (low, high) = when (sexLabel(sex)) {
        "男" -> 55.0 to 65.0
        "女" -> 45.0 to 60.0
        else -> return null
    }
    return when {
        pct < low -> "偏低" to GColor.Blue
        pct <= high -> "标准" to GColor.Green
        else -> "偏高" to GColor.Amber
    }
}

private fun heartStatus(bpm: Int?): Pair<String, Color>? {
    if (bpm == null) return null
    return when {
        bpm < 60 -> "偏低" to GColor.Blue
        bpm <= 100 -> "正常" to GColor.Green
        else -> "偏高" to GColor.Amber
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatFullTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

private fun fmtDayTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun fmtAxis(ms: Long, rangeDays: Int): String =
    SimpleDateFormat(if (rangeDays >= 365) "M月" else "MM-dd", Locale.getDefault()).format(Date(ms))

private fun rangeLabel(days: Int): String = when (days) {
    7 -> "近 7 天"
    30 -> "近 1 个月"
    180 -> "近半年"
    365 -> "近 1 年"
    else -> "近 $days 天"
}

/** 以本地时区的当天 00:00 为基准，取最近 [days] 天的起点。 */
private fun startOfDay(nowMs: Long, days: Int): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = nowMs
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    c.add(Calendar.DAY_OF_YEAR, -(days - 1))
    return c.timeInMillis
}

private fun scaleLabel(row: MeasurementEntity): String =
    row.model.ifBlank { row.brand.ifBlank { "未知秤" } }

private fun fmt1(v: Double?): String = v?.let { "%.1f".format(it) } ?: "—"

private fun pct(v: Double?): String = v?.let { "%.1f%%".format(it) } ?: "—"

private fun kg(v: Double?): String = v?.let { "%.1f kg".format(it) } ?: "—"

private fun statusLabel(status: String): String = when (status) {
    UploadStatus.UPLOADED -> "已上传"
    UploadStatus.FAILED -> "上传失败"
    UploadStatus.SKIPPED -> "已跳过"
    UploadStatus.PENDING -> "待上传"
    else -> status
}

private fun sourceLabel(source: String): String = when (source) {
    MeasurementSource.HISTORY -> "秤内历史"
    MeasurementSource.LIVE -> "实时称重"
    else -> source
}

private fun skipLabel(reason: String): String = when (reason) {
    SkipReason.DUPLICATE_LEDGER -> "已同步过"
    SkipReason.DUPLICATE_RECENT -> "5 分钟内重复体重"
    else -> reason
}

private fun sexLabel(sex: String?): String = when (sex?.lowercase()) {
    "male", "m", "男" -> "男"
    "female", "f", "女" -> "女"
    else -> sex ?: "—"
}
