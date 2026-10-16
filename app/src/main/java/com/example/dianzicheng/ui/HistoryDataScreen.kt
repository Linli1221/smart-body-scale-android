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
 * 2. **指标数据卡**：BMI / 体脂率 / 肌肉量 / 蛋白质 / 骨量 各一张数据卡片，
 *    展示最新值、平均值与极值区间（不画走势线，趋势由历史页的趋势卡片承担）；
 * 3. **全部记录**：按时间倒序列出每一次测量（每行一条，含全部指标）。
 *
 * 无 BIA 数据（未赤脚称重）的记录中，体脂率等指标值为 0，构建统计序列时直接过滤，
 * 不参与计算，对应卡片显示「暂无有效数据」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDataScreen(
    history: List<BodyMeasurement>,
    onNavigateBack: () -> Unit
) {
    // 时间正序：首条为最早一次，末条为最新一次
    val sorted = remember(history) { history.sortedBy { it.measuredAtEpochMs } }

    val dateFormat = remember { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()) }

    // 五项身体成分的统计序列（0 值 = 未测出，已过滤）
    val summaries = remember(sorted) {
        listOf(
            MetricSummary("BMI", "", 1, sorted.map { it.bmi }.filter { it > 0.0 }),
            MetricSummary("体脂率", "%", 1, sorted.map { it.bodyFatPct }.filter { it > 0.0 }),
            MetricSummary("肌肉量", "kg", 1, sorted.map { it.muscleKg }.filter { it > 0.0 }),
            MetricSummary("蛋白质", "%", 1, sorted.map { it.proteinPct }.filter { it > 0.0 }),
            MetricSummary("骨量", "kg", 1, sorted.map { it.boneMassKg }.filter { it > 0.0 }),
        )
    }

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
                val daySpan =
                    ((sorted.last().measuredAtEpochMs - sorted.first().measuredAtEpochMs) / 86_400_000L).toInt()
                val avgWeight = sorted.map { it.weightKg }.average()

                HistorySummaryCard(
                    items = listOf(
                        HistorySummaryItem("总记录数", "${sorted.size} 次"),
                        HistorySummaryItem("测量跨度", if (daySpan > 0) "$daySpan 天" else "单日"),
                        HistorySummaryItem("平均体重", String.format("%.1f kg", avgWeight))
                    )
                )
            }

            // ── 2. 指标数据卡：五项身体成分各一张 ──
            item(key = "summary_header") {
                Text(
                    text = "指标数据",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            itemsIndexed(summaries, key = { _, summary -> summary.label }) { _, summary ->
                MetricDataCard(summary = summary)
            }

            // ── 3. 全部记录（时间倒序，最新在最上） ──
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
 * 单个指标数据卡片的数据源。
 *
 * @param label    指标名（如「BMI」「体脂率」）
 * @param unit     单位后缀，无单位传空串
 * @param decimals 数值小数位数
 * @param values   按时间正序的有效测量值序列（0 值已在调用侧过滤）
 */
data class MetricSummary(
    val label: String,
    val unit: String,
    val decimals: Int,
    val values: List<Double>
)

/**
 * 单指标数据卡片。
 *
 * 顶部为指标名 + 最新值；下方三列等宽展示 平均值 / 最高 / 最低，
 * 让用户在不翻逐条记录的前提下掌握该指标的整体水平与波动区间。
 *
 * 不着色、不标注方向：BMI 偏高是负面、肌肉量偏高却是正面，
 * 用红绿臆断好坏会误导用户，因此统一用中性色呈现。
 */
@Composable
fun MetricDataCard(summary: MetricSummary, modifier: Modifier = Modifier) {
    val fmt = "%.f"
    val latest = summary.values.lastOrNull()
    val average = summary.values.average()
    val maxValue = summary.values.maxOrNull()
    val minValue = summary.values.minOrNull()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = summary.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (latest != null) String.format(fmt, latest) + summary.unit else "--",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = if (latest != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (summary.values.isEmpty()) {
                Text(
                    text = "暂无有效数据（需赤脚称重）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    MetricStatCell("平均值", String.format(fmt, average) + summary.unit, modifier = Modifier.weight(1f))
                    MetricStatCell("最高", String.format(fmt, maxValue!!) + summary.unit, modifier = Modifier.weight(1f))
                    MetricStatCell("最低", String.format(fmt, minValue!!) + summary.unit, modifier = Modifier.weight(1f))
                    MetricStatCell("测量次数", " 次", modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 数据卡片中的单列统计值（标签在上、数值在下，居中）。 */
@Composable
fun MetricStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
/**
 * 全部记录中的单行数据：序号 + 时间 + 体重 + 五项身体成分。
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
