package com.example.b1garmin.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * 竖向滚轮选择器（自绘，不引第三方库）。
 *
 * 用 LazyColumn + 居中吸附实现：
 * - 上下各留 (visibleCount-1)/2 个 item 的空白，选中的那一行正好在正中
 * - 滑动停止后吸附到最近的整行，界面不会停在半行上
 * - 离中心越远，字越小越淡，层次感靠这个
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelPicker(
    labels: List<String>,
    initialIndex: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 46.dp,
    visibleCount: Int = 5,
) {
    if (labels.isEmpty()) return
    val density = LocalDensity.current
    val padDp = ((visibleCount - 1) / 2) * itemHeight.value
    val startIndex = initialIndex.coerceIn(0, labels.size - 1)

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Center)

    // 当前停在正中的那一行
    val centered by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) {
                listState.firstVisibleItemIndex
            } else {
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
                    ?: listState.firstVisibleItemIndex
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { centered }.collect { onIndexChange(it) }
    }

    Box(
        modifier.height((visibleCount * itemHeight.value).dp),
        contentAlignment = Alignment.Center
    ) {
        // 正中间的高亮条
        Box(
            Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = padDp.dp),
            flingBehavior = flingBehavior,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(labels.size) { i ->
                val dist = abs(i - centered)
                val scale = when (dist) { 0 -> 1f; 1 -> 0.88f; 2 -> 0.78f; else -> 0.72f }
                val alpha = when (dist) { 0 -> 1f; 1 -> 0.75f; 2 -> 0.5f; else -> 0.32f }
                Box(
                    Modifier.fillMaxWidth().height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        labels[i],
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (dist == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (dist == 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        }
                    )
                }
            }
        }
    }
}

/**
 * 资料设置页。
 *
 * 身高、目标体重用滚轮；出生日期用日历（自动算年龄）；性别直接选男/女。
 * 所有值点「保存」才生效，取消则不动原来的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initial: Profile,
    onSave: (Profile) -> Unit,
    onClose: () -> Unit,
) {
    var height by remember { mutableStateOf(initial.heightCm) }
    var target by remember { mutableStateOf(initial.targetKg) }
    var birthY by remember { mutableStateOf(initial.birthYear) }
    var birthM by remember { mutableStateOf(initial.birthMonth) }
    var birthD by remember { mutableStateOf(initial.birthDay) }
    var male by remember { mutableStateOf(initial.male) }
    var ageFallback by remember { mutableStateOf(initial.ageFallback) }
    var showDate by remember { mutableStateOf(false) }

    // 身高：100.0 ~ 220.0，步进 0.5
    val heightValues = remember { (200..440).map { it / 2.0 } }
    val heightLabels = remember { heightValues.map { trimNum(it) } }
    val heightIndex = remember(height) {
        heightValues.indexOfFirst { abs(it - height) < 0.26 }.coerceAtLeast(0)
    }
    // 目标体重：30.0 ~ 200.0，步进 0.1；第 0 项是「不设」
    val weightValues = remember { listOf(0.0) + (300..2000).map { it / 10.0 } }
    val weightLabels = remember {
        listOf("不设（按身高推算）") + (300..2000).map { trimNum(it / 10.0) }
    }
    val weightIndex = remember(target) {
        weightValues.indexOfFirst { abs(it - target) < 0.051 }.coerceAtLeast(0)
    }
    // 年龄滚轮（只在没填出生日期时出现）
    val ageValues = remember { (10..100).map { it.toDouble() } }
    val ageLabels = remember { ageValues.map { "${it.toInt()} 岁" } }
    val ageIndex = remember(ageFallback) {
        (ageFallback - 10).coerceIn(0, ageValues.size - 1)
    }

    val cur = Profile(
        heightCm = height,
        targetKg = target,
        birthYear = birthY,
        birthMonth = birthM,
        birthDay = birthD,
        ageFallback = ageFallback,
        male = male,
    )

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Text(
                    "资料设置",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { onSave(cur) }) { Text("保存") }
            }
            Text(
                "这些参数设一次就行，之后主界面上不会再出现输入框，也就不会被误触清空。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))

            // ---------------- 身高 ----------------
            Text("身高", style = MaterialTheme.typography.titleMedium)
            Text(
                "%.1f cm".format(height),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            WheelPicker(
                labels = heightLabels,
                initialIndex = heightIndex,
                onIndexChange = { height = heightValues[it] },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            // ---------------- 参考体重（只发给秤用） ----------------
            Text("参考体重", style = MaterialTheme.typography.titleMedium)
            Text(
                if (target > 0) "%.1f kg（只发给秤当参考）".format(target)
                else "不设（按 BMI 22 推一个值发给秤）",
                style = MaterialTheme.typography.bodySmall,
                color = if (target > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline
            )
            Text(
                "这个值只是协议里必须填的一个数，秤拿它算它自己那套体成分——" +
                        "而我们不用秤的结果，上传给佳明的数值是按体重+阻抗自己算的，" +
                        "所以这里填什么都不影响上传的数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            WheelPicker(
                labels = weightLabels,
                initialIndex = weightIndex,
                onIndexChange = { target = weightValues[it] },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            // ---------------- 出生日期 / 年龄 ----------------
            Text("出生日期", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (birthY > 0) "%d 年 %d 月 %d 日".format(birthY, birthM, birthD)
                    else "没填出生日期",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { showDate = true }) { Text("选择日期") }
            }
            if (birthY > 0) {
                Text(
                    "→ 现在 %d 岁（生日没到会少一岁）".format(cur.age),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                TextButton(onClick = { birthY = 0 }) {
                    Text("不按出生日期，直接填年龄", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(
                    "→ 现在 %d 岁".format(ageFallback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                WheelPicker(
                    labels = ageLabels,
                    initialIndex = ageIndex,
                    onIndexChange = { ageFallback = ageValues[it].toInt() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(12.dp))

            // ---------------- 性别 ----------------
            Text("性别", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = male,
                    onClick = { male = true },
                    label = { Text("男") }
                )
                FilterChip(
                    selected = !male,
                    onClick = { male = false },
                    label = { Text("女") }
                )
            }
            Spacer(Modifier.height(20.dp))

            Text(
                "保存后：BMI、基础代谢、体脂率方程都按这里的资料算；秤握手时发的年龄也用这里算出来的年龄。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDate) {
        val initialMillis = remember(birthY, birthM, birthD) {
            LocalDate.of(
                if (birthY > 0) birthY else 1991,
                birthM.coerceIn(1, 12),
                1
            ).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        val d = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        birthY = d.year
                        birthM = d.monthValue
                        birthD = d.dayOfMonth
                    }
                    showDate = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = dateState)
        }
    }
}

/** 去掉多余的 .0：174.0 → 174，174.5 → 174.5 */
private fun trimNum(v: Double): String =
    if (abs(v - Math.round(v)) < 1e-6) Math.round(v).toInt().toString()
    else String.format("%.1f", v)
