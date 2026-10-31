package com.example.dianzicheng.ui.page

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.ui.component.ConnectionStatusChip
import com.example.dianzicheng.ui.component.MetricItem
import com.example.dianzicheng.ui.component.WeightDisplay
import com.example.dianzicheng.ui.component.getBmiStatus
import com.example.dianzicheng.ui.component.getFatStatus
import com.example.dianzicheng.ui.component.getWaterStatus
import com.example.dianzicheng.ui.state.ScaleUiState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.ContactsCircle
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Promotions
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 测量主页（单页面架构的默认页）：
 * 成员绑定入口 → 连接状态 → 体重读数 → 测量结果卡 → 操作区（绑定引导 / 开启蓝牙 / 开始称重）。
 */
@Composable
fun MeasurePage(
    uiState: ScaleUiState,
    onStartScan: () -> Unit,
    onOpenPairing: () -> Unit,
    onSelectMember: (FamilyMember?) -> Unit,
    onBindMember: (FamilyMember) -> Unit,
    onDismissAlert: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var memberDialogMode by remember { mutableStateOf<MemberDialogMode?>(null) }
    var autoPromptedId by remember { mutableStateOf<String?>(null) }

    // 首测未绑定成员时自动弹出成员选择（每条测量一次；正常匹配流程几乎不会触发）
    var previousStable by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isStable) {
        val finishedNow = previousStable && !uiState.isStable
        val measurement = uiState.currentMeasurement
        if (finishedNow &&
            measurement != null &&
            measurement.id != autoPromptedId &&
            uiState.selectedMember == null &&
            measurement.memberId == null &&
            uiState.availableMembers.size > 1
        ) {
            autoPromptedId = measurement.id
            memberDialogMode = MemberDialogMode.Select
        }
        previousStable = uiState.isStable
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ① 成员绑定入口 chip
        MemberChip(
            selectedMember = uiState.selectedMember,
            onClick = { memberDialogMode = MemberDialogMode.Select }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ② 连接状态
        ConnectionStatusChip(uiState = uiState)

        Spacer(modifier = Modifier.height(16.dp))

        // ③ 体重大读数（产品核心读数）
        WeightDisplay(
            weightKg = uiState.liveWeightKg,
            isStable = uiState.isStable,
            isCompact = uiState.currentMeasurement != null
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ④ 测量结果卡
        AnimatedVisibility(
            visible = uiState.currentMeasurement != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            uiState.currentMeasurement?.let { measurement ->
                MeasurementResultCard(
                    measurement = measurement,
                    members = uiState.availableMembers,
                    // 阻抗行优先实时值；下秤后回落到记录值，避免结果卡显示「未测出」
                    impedanceOhm = if (uiState.impedanceOhm > 0) {
                        uiState.impedanceOhm
                    } else {
                        measurement.impedanceOhm
                    },
                    biaModelName = uiState.selectedBiaModel.displayName,
                    onRebind = { memberDialogMode = MemberDialogMode.Bind }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ⑤ 操作区（三分支）
        when {
            !uiState.isDeviceRemembered -> NotPairedGuideCard(onOpenPairing = onOpenPairing)
            !uiState.isBluetoothEnabled -> BluetoothOffCard(
                onEnableBluetooth = {
                    context.startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            )
            uiState.connection == BleScaleClient.ConnectionState.IDLE -> {
                Button(
                    onClick = onStartScan,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开始称重", style = MiuixTheme.textStyles.button)
                }
            }
            uiState.connection == BleScaleClient.ConnectionState.SCANNING -> {
                val scanningLabel = if (uiState.rememberedScales.size > 1) {
                    "${uiState.rememberedScales.size} 台已记住设备"
                } else {
                    uiState.pairedDeviceName ?: uiState.pairedDeviceMac ?: "体脂秤"
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            top.yukonga.miuix.kmp.basic.LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "正在寻找已记住的体脂秤 ($scanningLabel)...",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        IconButton(onClick = onStartScan) {
                            Icon(
                                imageVector = MiuixIcons.Refresh,
                                contentDescription = "重新扫描",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
            // CONNECTING / CONNECTED / MEASURING：等待称重即可，无操作按钮
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    // ── 弹窗（Window 层，可在底部弹层之上安全展示） ─────────────────────────
    val mode = memberDialogMode
    if (mode != null) {
        SelectMemberDialog(
            members = uiState.availableMembers,
            currentMemberId = uiState.currentMeasurement?.memberId ?: uiState.selectedMember?.id,
            allowAutoMatch = mode == MemberDialogMode.Select,
            onDismiss = { memberDialogMode = null },
            onSelect = { member ->
                memberDialogMode = null
                if (mode == MemberDialogMode.Bind) {
                    // 结果卡流：只接受具体成员（重绑重算）
                    if (member != null) onBindMember(member)
                } else {
                    // 顶部 chip 流：null = 全员智能自动匹配
                    onSelectMember(member)
                }
            }
        )
    }

    if (uiState.showNewMemberAlert) {
        WindowDialog(
            show = true,
            title = "发现新成员？",
            summary = "测量体重与已有成员差距较大，您可以稍后在“我的”页面添加新成员。",
            onDismissRequest = onDismissAlert
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(text = "知道了", onClick = onDismissAlert)
            }
        }
    }
}

private enum class MemberDialogMode { Select, Bind }

/** 成员绑定入口 chip：显示当前绑定成员或智能匹配模式。 */
@Composable
private fun MemberChip(
    selectedMember: FamilyMember?,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = MiuixIcons.Contacts,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = selectedMember?.let {
                    "绑定成员: ${it.name} (${if (it.sex == Sex.MALE) "男" else "女"} ${it.heightCm.toInt()}cm)"
                } ?: "全员智能自动匹配",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = MiuixIcons.ExpandMore,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
        }
    }
}

/** 测量结果卡：数据归属 + 8 项指标 + 估算模型标注。 */
@Composable
private fun MeasurementResultCard(
    measurement: BodyMeasurement,
    members: List<FamilyMember>,
    impedanceOhm: Double,
    biaModelName: String,
    onRebind: () -> Unit
) {
    val hasBia = measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0
    // 未绑定成员时默认按男性阈值判定体脂区间（与历史记录卡/详情弹窗保持一致）
    val isMale = members.firstOrNull { it.id == measurement.memberId }?.sex != Sex.FEMALE

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            // 头行：数据归属 + 重绑入口
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MiuixIcons.ContactsCircle,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "数据所属: ${measurement.memberNameSnapshot ?: "未绑定成员"}",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (members.isNotEmpty()) {
                    TextButton(text = "重选/绑定成员", onClick = onRebind)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 行 1：BMI | 体脂率 | 水分
            Row(modifier = Modifier.fillMaxWidth()) {
                MetricItem(
                    label = "BMI",
                    value = if (measurement.bmi > 0) String.format("%.1f", measurement.bmi) else "--",
                    badge = if (measurement.bmi > 0) getBmiStatus(measurement.bmi) else null,
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "体脂率",
                    value = if (hasBia) String.format("%.1f%%", measurement.bodyFatPct) else "--",
                    badge = if (hasBia) getFatStatus(measurement.bodyFatPct, isMale) else null,
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "水分",
                    value = if (hasBia) String.format("%.1f%%", measurement.waterPct) else "--",
                    badge = if (hasBia) getWaterStatus(measurement.waterPct) else null,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 行 2：肌肉量 | 蛋白质 | 骨量
            Row(modifier = Modifier.fillMaxWidth()) {
                MetricItem(
                    label = "肌肉量",
                    value = if (hasBia) String.format("%.1fkg", measurement.muscleKg) else "--",
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "蛋白质",
                    value = if (hasBia) String.format("%.1f%%", measurement.proteinPct) else "--",
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "骨量",
                    value = if (hasBia) String.format("%.1fkg", measurement.boneMassKg) else "--",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 行 3：阻抗
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                MetricItem(
                    label = "阻抗",
                    value = if (impedanceOhm > 0) "${impedanceOhm.toInt()}Ω" else "未测出 (请赤脚称重)"
                )
            }

            // 估算模型标注（仅测出 BIA 时展示口径）
            if (hasBia && biaModelName.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "估算模型：$biaModelName",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** 未绑定体脂秤引导卡。 */
@Composable
private fun NotPairedGuideCard(onOpenPairing: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "未绑定体脂秤",
                style = MiuixTheme.textStyles.title2,
                color = MiuixTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "手动连接并记住体脂秤后，App 将仅针对已记住的设备开启自动连接，防止误连周围其他设备；可同时记住多台，踩上任意一台都能自动连接。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onOpenPairing,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(MiuixIcons.SearchDevice, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("搜索并绑定设备", style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/** 蓝牙未开启警示卡。 */
@Composable
private fun BluetoothOffCard(onEnableBluetooth: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "手机蓝牙已关闭",
                style = MiuixTheme.textStyles.title2,
                color = MiuixTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "需要开启手机蓝牙以连接已绑定的体脂秤并接收体重数据。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onEnableBluetooth,
                // 警示操作保留 error 语义（Defaults 工厂 + token，非字面色）
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.error,
                    contentColor = MiuixTheme.colorScheme.onError
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(MiuixIcons.SearchDevice, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("开启手机蓝牙", style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/**
 * 选择绑定成员弹窗（Window 层）。
 *
 * @param allowAutoMatch true=顶部 chip 流（含「全员智能自动匹配」选项）；
 *                       false=结果卡重绑流（只接受具体成员）。
 */
@Composable
fun SelectMemberDialog(
    members: List<FamilyMember>,
    currentMemberId: String?,
    allowAutoMatch: Boolean,
    onDismiss: () -> Unit,
    onSelect: (FamilyMember?) -> Unit
) {
    WindowDialog(
        show = true,
        title = "选择绑定成员",
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "选择成员后，系统将使用该成员的身高、年龄与性别重新计算准确的体脂率等指标并保存绑定。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (allowAutoMatch) {
                Surface(
                    onClick = { onSelect(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = if (currentMemberId == null) {
                        MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    } else {
                        Color.Transparent
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            MiuixIcons.Promotions,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("全员智能自动匹配", style = MiuixTheme.textStyles.body1)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            if (members.isEmpty()) {
                Text(
                    text = "暂无成员，请在“我的”页面添加家庭成员",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(members) { member ->
                        val isSelected = member.id == currentMemberId
                        Surface(
                            onClick = { onSelect(member) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) {
                                MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else {
                                Color.Transparent
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    MiuixIcons.Contacts,
                                    contentDescription = null,
                                    tint = if (isSelected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = member.name, style = MiuixTheme.textStyles.body1)
                                    Text(
                                        text = "${if (member.sex == Sex.MALE) "男" else "女"} · ${member.heightCm.toInt()}cm",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        MiuixIcons.Basic.Check,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}
