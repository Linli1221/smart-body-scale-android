package com.example.dianzicheng.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.ui.theme.电子秤Theme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史数据页：汇总展示所有测量记录中的身体成分指标。
 *
 * 页面自上而下分为三块：
 * 1. **概览卡片**：总记录数、测量天数跨度、平均体重；
 * 2. **趋势汇总**：首末两次测量的体重 / BMI / 体脂率 / 肌肉量 / 蛋白质 / 骨量变化量；
 * 3. **完整历史记录**：按时间倒序列出每一次测量（每行一条，含全部指标）。
 *
 * 无 BIA 数据（未赤脚称重）的记录中，体脂率等指标显示为 --，不参与均值与变化量统计。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDataScreen(
    history: List<BodyMeasurement>,
    onNavigateBack: () -> Unit
) {
    // 时间正序：首条为最早一次，末条为最新一次
    val sorted = remember(history) { history.sortedBy { it.measuredAtEpochMs } }
    val latest = sorted.lastOrNull()
    val earliest = sorted.firstOrNull()

    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("历史数据", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(
                    "暂无历史数据",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 1. 概览：总记录数 / 天数跨度 / 平均体重 ──
            item(key = "overview") {
                val daySpan = if (earliest != null && latest != null) {
                    ((latest.measuredAtEpochMs - earliest.measuredAtEpochMs) / 86_400_000L).toInt()
                } else 0
                val avgWeight = sorted.map { it.weightKg }.average()

                HistorySummaryCard(
                    items = listOf(
                        HistorySummaryItem("总记录数", "${sorted.size} 次"),
                        HistorySummaryItem("测量跨度", if (daySpan > 0) "$daySpan 天" else "单日"),
                        HistorySummaryItem("平均体重", String.format("%.1f kg", avgWeight))
                    )
                )
            }

            // ── 2. 趋势汇总：首末对比 ──
            if (sorted.size >= 2) {
                item(key = "trend_summary") {
                    HistoryTrendCard(earliest = earliest!!, latest = latest!!)
                }
            }

            // ── 3. 完整历史记录（时间倒序，最新在最上） ──
            item(key = "list_header") {
                Text(
                    text = "全部记录（${sorted.size} 次）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            itemsIndexed(sorted.reversed()) { index, measurement ->
                HistoryDataRow(
                    index = index + 1,
                    measurement = measurement,
                    dateStr = dateFormat.format(Date(measurement.measuredAtEpochMs))
                )
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/** 概览卡中的单个数据项（标签 + 数值）。 */
data class HistorySummaryItem(val label: String, val value: String)

/** 概览卡片：横向排布三枚关键统计。 */
@Composable
fun HistorySummaryCard(items: List<HistorySummaryItem>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            items.forEach { item ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 趋势汇总卡片：对比最早一次与最新一次的体重及各项身体成分，
 * 变化量以「↑/↓ + 差值」呈现，持平显示 0。
 */
@Composable
fun HistoryTrendCard(earliest: BodyMeasurement, latest: BodyMeasurement, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "趋势汇总",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "首次 → 最新 的变化量",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(12.dp))

            TrendRow("体重", earliest.weightKg, latest.weightKg, unit = "kg", decimals = 2)
            TrendRow("BMI", earliest.bmi, latest.bmi, unit = "", decimals = 1)
            TrendRow("体脂率", earliest.bodyFatPct, latest.bodyFatPct, unit = "%", decimals = 1)
            TrendRow("肌肉量", earliest.muscleKg, latest.muscleKg, unit = "kg", decimals = 1)
            TrendRow("蛋白质", earliest.proteinPct, latest.proteinPct, unit = "%", decimals = 1)
            TrendRow("骨量", earliest.boneMassKg, latest.boneMassKg, unit = "kg", decimals = 1)
        }
    }
}

/**
 * 趋势汇总中的单行：指标名 + 首末值 + 变化量。
 *
 * 0 值视为未测出（未赤脚称重），整行以 -- 呈现、不计算变化量。
 */
@Composable
fun TrendRow(
    label: String,
    from: Double,
    to: Double,
    unit: String,
    decimals: Int,
    modifier: Modifier = Modifier
) {
    val valid = from > 0.0 && to > 0.0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(64.dp)
        )
        if (valid) {
            val fmt = "%.${decimals}f"
            Text(
                text = String.format(fmt, from) + unit,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(72.dp)
            )
            Text(
                text = "→",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.width(24.dp)
            )
            Text(
                text = String.format(fmt, to) + unit,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(72.dp)
            )
            val delta = to - from
            val arrow = when {
                delta > 0.0001 -> "↑"
                delta < -0.0001 -> "↓"
                else -> "·"
            }
            val deltaColor = when (arrow) {
                "↑" -> MaterialTheme.colorScheme.error
                "↓" -> Color(0xFF4CAF50)
                else -> MaterialTheme.colorScheme.outline
            }
            Text(
                text = "$arrow ${String.format(fmt, kotlin.math.abs(delta))}$unit",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = deltaColor,
                modifier = Modifier.weight(1f)
            )
        } else {
            Text(
                text = "--",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/**
 * 完整历史记录中的单行数据：序号 + 时间 + 体重 + 五项身体成分。
 *
 * 每项指标一格等宽排列，缺失（未赤脚称重）时显示 --。
 */
@Composable
fun HistoryDataRow(
    index: Int,
    measurement: BodyMeasurement,
    dateStr: String,
    modifier: Modifier = Modifier
) {
    val hasBia = measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#$index",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (measurement.memberNameSnapshot != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = measurement.memberNameSnapshot,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 体重为主数值，右侧五列指标
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = String.format("%.2f", measurement.weightKg),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    text = " kg",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 3.dp),
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HistoryDataCell("BMI", if (measurement.bmi > 0.0) String.format("%.1f", measurement.bmi) else "--", modifier = Modifier.weight(1f))
                    HistoryDataCell("体脂", if (hasBia) String.format("%.1f%%", measurement.bodyFatPct) else "--", modifier = Modifier.weight(1f))
                    HistoryDataCell("肌肉", if (hasBia) String.format("%.1f", measurement.muscleKg) else "--", modifier = Modifier.weight(1f))
                    HistoryDataCell("蛋白", if (hasBia) String.format("%.1f%%", measurement.proteinPct) else "--", modifier = Modifier.weight(1f))
                    HistoryDataCell("骨量", if (hasBia) String.format("%.1f", measurement.boneMassKg) else "--", modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 历史数据行中的单个指标单元格（标签在上、数值在下，居中）。 */
@Composable
fun HistoryDataCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (value == "--") MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Preview(showBackground = true)
@Composable
fun HistoryDataScreenPreview() {
    电子秤Theme(dynamicColor = true) {
        HistoryDataScreen(
            history = listOf(
                BodyMeasurement("1", System.currentTimeMillis(), 70.5, 500.0, 22.8, 18.5, 60.0, 55.0, 16.5, 3.2, "1", "我的名字"),
                BodyMeasurement("2", System.currentTimeMillis() - 86_400_000L * 3, 71.2, 510.0, 23.1, 19.0, 59.0, 54.0, 16.0, 3.1, "1", "我的名字"),
                BodyMeasurement("3", System.currentTimeMillis() - 86_400_000L * 10, 72.0, 0.0, 23.4, 0.0, 0.0, 0.0, 0.0, 0.0, "1", "我的名字")
            ),
            onNavigateBack = {}
        )
    }
}
