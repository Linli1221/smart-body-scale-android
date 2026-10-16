package com.example.dianzicheng.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * 页面自上而下分为四块：
 * 1. **概览卡片**：总记录数、测量天数跨度、平均体重；
 * 2. **指标趋势**：BMI / 体脂率 / 肌肉量 / 蛋白质 / 骨量 各一张趋势卡片，
 *    含最新值、相较首次的变化量与迷你走势线；
 * 3. **全部记录**：按时间倒序列出每一次测量（每行一条，含全部指标）。
 *
 * 无 BIA 数据（未赤脚称重）的记录中，体脂率等指标值为 0，构建趋势序列时直接过滤，
 * 不参与统计，对应卡片显示「暂无有效数据」。
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

    // 五项身体成分的趋势序列（0 值 = 未测出，已过滤）
    val trends = remember(sorted) {
        listOf(
            MetricTrend("BMI", "", 1, sorted.map { it.bmi }.filter { it > 0.0 }),
            MetricTrend("体脂率", "%", 1, sorted.map { it.bodyFatPct }.filter { it > 0.0 }),
            MetricTrend("肌肉量", "kg", 1, sorted.map { it.muscleKg }.filter { it > 0.0 }),
            MetricTrend("蛋白质", "%", 1, sorted.map { it.proteinPct }.filter { it > 0.0 }),
            MetricTrend("骨量", "kg", 1, sorted.map { it.boneMassKg }.filter { it > 0.0 }),
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

            // ── 2. 指标趋势：五项身体成分各一张卡片 ──
            item(key = "trend_header") {
                Text(
                    text = "指标趋势",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            itemsIndexed(trends, key = { _, trend -> trend.label }) { _, trend ->
                MetricTrendCard(trend = trend)
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
 * 单个指标趋势卡片的数据源。
 *
 * @param label    指标名（如「BMI」「体脂率」）
 * @param unit     单位后缀，无单位传空串
 * @param decimals 数值小数位数
 * @param values   按时间正序的有效测量值序列（0 值已在调用侧过滤）
 */
data class MetricTrend(
    val label: String,
    val unit: String,
    val decimals: Int,
    val values: List<Double>
)

/**
 * 单指标趋势卡片。
 *
 * 自上而下三行信息：
 * 1. 指标名 + 最新值（大号数字，无数据时显示 --）；
 * 2. 相较首次的变化量标签（↑/↓ + 差值，数据不足时给出原因说明）；
 * 3. 迷你走势线（取最近 20 个有效值绘制折线 + 渐变面积 + 末点高亮）。
 *
 * 变化量只标注方向、不着色：BMI 涨了不好、肌肉量涨了却是好事，
 * 用红绿臆断好坏会误导用户，因此统一用中性色呈现。
 */
@Composable
fun MetricTrendCard(trend: MetricTrend, modifier: Modifier = Modifier) {
    val fmt = "%.${trend.decimals}f"
    val latest = trend.values.lastOrNull()
    val first = trend.values.firstOrNull()
    val delta = if (trend.values.size >= 2 && latest != null && first != null) latest - first else null

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = trend.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (latest != null) String.format(fmt, latest) + trend.unit else "--",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = if (latest != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (delta != null) {
                val arrow = when {
                    delta > 0.0001 -> "↑"
                    delta < -0.0001 -> "↓"
                    else -> "·"
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "较首次 $arrow ${String.format(fmt, kotlin.math.abs(delta))}${trend.unit} · 共 ${trend.values.size} 次",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(
                    text = if (trend.values.isEmpty()) "暂无有效数据（需赤脚称重）" else "仅一次测量，暂无趋势",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (trend.values.size >= 2) {
                Spacer(modifier = Modifier.height(10.dp))
                MetricSparkline(
                    values = trend.values.takeLast(20),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                )
            }
        }
    }
}

/**
 * 迷你走势线：折线 + 渐变面积填充 + 末点高亮圆点。
 *
 * 纵向按序列自身的最小/最大值归一化，因此图形反映的是变化趋势而非绝对区间；
 * 序列只有 1 个点时直接不绘制。
 */
@Composable
fun MetricSparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary
) {
    if (values.size < 2) return

    Canvas(modifier = modifier) {
        val minVal = values.min()
        val maxVal = values.max()
        val range = if (maxVal > minVal) maxVal - minVal else 1.0
        val w = size.width
        val h = size.height
        val step = w / (values.size - 1).toFloat()

        fun yOf(v: Double) = (h * (1.0 - (v - minVal) / range)).toFloat()

        // 渐变面积：让趋势方向一眼可读
        val fillPath = Path()
        fillPath.moveTo(0f, yOf(values.first()))
        for (i in 1 until values.size) {
            fillPath.lineTo(i * step, yOf(values[i]))
        }
        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.22f), lineColor.copy(alpha = 0f)),
                startY = 0f,
                endY = h
            )
        )

        val linePath = Path()
        linePath.moveTo(0f, yOf(values.first()))
        for (i in 1 until values.size) {
            linePath.lineTo(i * step, yOf(values[i]))
        }
        drawPath(
            path = linePath,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // 末点高亮：标示当前所处位置
        drawCircle(lineColor, radius = 3.5.dp.toPx(), center = Offset(w, yOf(values.last())))
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
