package com.example.dianzicheng.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.domain.BiaModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 选择电阻抗（BIA）体成分估算模型的对话框。
 *
 * 入口位于「我的 → 应用设置 → 体脂估算模型」。用户可在三种国际公认 /
 * 内置的 F-F BIA 公式之间切换，切换后对下一次称重生效，历史记录不追溯重算。
 *
 * 每个模型都会展示：
 * - 公式原文（含性别差异说明）
 * - 适用人群与金标准来源
 * - 特性简述
 */
@Composable
fun SelectBiaModelDialog(
    currentModel: BiaModel,
    onModelSelected: (BiaModel) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(currentModel) }
    val categories = remember { BiaModel.grouped() }

    WindowDialog(
        show = true,
        title = "选择体脂估算模型",
        summary = "切换后自动用新公式重算全部历史记录，历史曲线保持同一口径",
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { (categoryName, models) ->
                    item(key = "header_$categoryName") {
                        // 分组标签：SmallTitle 自带 subtitle 字阶；弹窗内容区自带水平内边距，微调 insideMargin 避免过度缩进
                        SmallTitle(text = categoryName, insideMargin = PaddingValues(0.dp))
                    }

                    items(models, key = { it.id }) { model ->
                        val isSelected = model == selected
                        // 选中态由 RadioButton 状态 + primaryContainer 底色表达，去掉自定描边
                        Surface(
                            onClick = { selected = model },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                MiuixTheme.colorScheme.primaryContainer
                            } else {
                                MiuixTheme.colorScheme.surfaceContainer
                            }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selected = model }
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = model.displayName,
                                                style = MiuixTheme.textStyles.body2,
                                                // 选中态加粗强调（规范允许保留的强调）
                                                fontWeight = if (isSelected) FontWeight.Bold else null
                                            )
                                            if (model == BiaModel.DEFAULT) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    color = MiuixTheme.colorScheme.primaryContainer,
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text(
                                                        text = "推荐",
                                                        style = MiuixTheme.textStyles.footnote2,
                                                        color = MiuixTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = model.description,
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                                        )
                                    }
                                }

                                // 选中的模型展开公式原文与来源，方便核对口径
                                if (isSelected) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MiuixTheme.colorScheme.surfaceContainer,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Row(verticalAlignment = Alignment.Top) {
                                                Icon(
                                                    imageVector = MiuixIcons.Info,
                                                    contentDescription = null,
                                                    tint = MiuixTheme.colorScheme.primary,
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .padding(top = 2.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Column {
                                                    Text(
                                                        text = "适用人群：${model.brand}",
                                                        style = MiuixTheme.textStyles.footnote2,
                                                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = model.formulaText,
                                                        style = MiuixTheme.textStyles.footnote2,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MiuixTheme.colorScheme.onSurface
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "来源：${model.source}",
                                                        style = MiuixTheme.textStyles.footnote2,
                                                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 弹窗按钮行：参照 Example DialogSection.kt 的对分按钮行（次操作默认样式，主操作主色）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = "使用此模型",
                    onClick = {
                        onModelSelected(selected)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}
