package com.example.dianzicheng.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.BiaModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
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
                        Text(
                            text = categoryName,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }

                    items(models, key = { it.id }) { model ->
                        val isSelected = model == selected
                        Surface(
                            onClick = { selected = model },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.25f)
                            },
                            border = if (isSelected) {
                                BorderStroke(
                                    1.5.dp,
                                    MiuixTheme.colorScheme.primary
                                )
                            } else null
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { selected = model },
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = model.displayName,
                                                style = MiuixTheme.textStyles.body2,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                            if (model == BiaModel.DEFAULT) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    color = MiuixTheme.colorScheme.primaryContainer,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = "推荐",
                                                        style = MiuixTheme.textStyles.footnote2,
                                                        color = MiuixTheme.colorScheme.primary,
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
                                        color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
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
                                                        color = MiuixTheme.colorScheme.onSurface,
                                                        lineHeight = 16.sp
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss
                )
                Button(
                    onClick = {
                        onModelSelected(selected)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Icon(MiuixIcons.Basic.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("使用此模型", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}
