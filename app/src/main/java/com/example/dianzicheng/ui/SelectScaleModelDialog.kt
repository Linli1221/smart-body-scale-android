package com.example.dianzicheng.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.domain.ScaleModel
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
 * 手动选择体脂秤型号的对话框。
 *
 * 用户可在此选择具体秤型号（薄荷健康/沃莱 或 小米体脂秤 2 代），
 * 选择后系统将优先匹配该协议并在配对与测量页面提供针对性的唤醒指导。
 */
@Composable
fun SelectScaleModelDialog(
    currentModel: ScaleModel,
    onModelSelected: (ScaleModel) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(currentModel) }
    val categories = remember { ScaleModel.grouped() }

    WindowDialog(
        show = true,
        title = "选择体脂秤型号与品牌",
        summary = "选择特定型号可优先匹配专有协议并提高识别成功率",
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
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
                                            if (model == ScaleModel.AUTO) {
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

                                // 选中的型号展示专属唤醒与踩秤提示
                                if (isSelected && model.wakeGuidance.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Icon(
                                                imageVector = MiuixIcons.Info,
                                                contentDescription = null,
                                                tint = MiuixTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .padding(top = 2.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = model.wakeGuidance,
                                                style = MiuixTheme.textStyles.footnote2,
                                                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                                lineHeight = 16.sp
                                            )
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
                    Text("确定使用此型号", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}
