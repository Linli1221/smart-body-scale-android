package com.example.dianzicheng.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.domain.BiaModel
import com.example.dianzicheng.domain.ScaleModel
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

/** 选项卡内的「推荐」徽标。 */
@Composable
private fun RecommendBadge() {
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

/** 选中项展开的提示信息块（Info 图标 + 说明文字）。 */
@Composable
private fun InfoHint(text: String, monospace: Boolean = false, strongColor: Boolean = false) {
    Row(
        modifier = Modifier.padding(top = 8.dp),
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
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            color = if (strongColor) MiuixTheme.colorScheme.onSurface
            else MiuixTheme.colorScheme.onSurfaceSecondary,
            fontFamily = if (monospace) FontFamily.Monospace else null
        )
    }
}

/**
 * 选择体脂秤型号与品牌：选中特定型号可优先匹配专有协议并提高识别成功率。
 * 选中项展开对应型号的唤醒/踩秤指导。
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
                        SmallTitle(text = categoryName, insideMargin = PaddingValues(0.dp))
                    }
                    items(models.size, key = { models[it].id }) { index ->
                        val model = models[index]
                        val isSelected = model == selected
                        Surface(
                            onClick = { selected = model },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MiuixTheme.colorScheme.primaryContainer
                            else MiuixTheme.colorScheme.surfaceContainer
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                            if (model == ScaleModel.AUTO) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                RecommendBadge()
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
                                if (isSelected && model.wakeGuidance.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MiuixTheme.colorScheme.surfaceContainer,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            InfoHint(text = model.wakeGuidance)
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
                    text = "确定使用此型号",
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

/**
 * 选择体脂估算模型：切换后自动用新公式重算全部历史记录，历史曲线保持同一口径。
 * 选中项展开适用人群、公式原文与文献来源。
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
                        SmallTitle(text = categoryName, insideMargin = PaddingValues(0.dp))
                    }
                    items(models.size, key = { models[it].id }) { index ->
                        val model = models[index]
                        val isSelected = model == selected
                        Surface(
                            onClick = { selected = model },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MiuixTheme.colorScheme.primaryContainer
                            else MiuixTheme.colorScheme.surfaceContainer
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                                fontWeight = if (isSelected) FontWeight.Bold else null
                                            )
                                            if (model == BiaModel.DEFAULT) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                RecommendBadge()
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
                                if (isSelected) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MiuixTheme.colorScheme.surfaceContainer,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            InfoHint(text = "适用人群：${model.brand}")
                                            InfoHint(text = model.formulaText, monospace = true, strongColor = true)
                                            InfoHint(text = "来源：${model.source}")
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
