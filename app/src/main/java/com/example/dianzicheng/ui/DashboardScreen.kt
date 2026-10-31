package com.example.dianzicheng.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.ui.theme.电子秤Theme
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
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
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DashboardContent(
    uiState: ScaleUiState,
    onStartScan: () -> Unit,
    onDismissAlert: () -> Unit,
    onSelectMember: (FamilyMember?) -> Unit,
    onBindMember: (FamilyMember) -> Unit,
    onNavigateToPairing: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showMemberSelectDialog by remember { mutableStateOf(false) }
    var hasAutoPromptedForMeasId by remember { mutableStateOf<String?>(null) }

    var lastStableState by remember { mutableStateOf(false) }

    // 测量完全结束后（锁定完成并下秤），若当前测量未匹配到成员且有多个成员可选，自动弹出成员匹配弹窗
    LaunchedEffect(uiState.isStable, uiState.currentMeasurement?.id) {
        val currentMeas = uiState.currentMeasurement
        if (lastStableState && !uiState.isStable && currentMeas != null && currentMeas.id != hasAutoPromptedForMeasId) {
            hasAutoPromptedForMeasId = currentMeas.id
            if (uiState.selectedMember == null && currentMeas.memberId == null && uiState.availableMembers.size > 1) {
                showMemberSelectDialog = true
            }
        }
        if (uiState.isStable) {
            lastStableState = true
        } else if (currentMeas == null) {
            lastStableState = false
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "体重秤",
                color = Color.Transparent
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Member Selector Chip
            Surface(
                onClick = { showMemberSelectDialog = true },
                shape = CircleShape,
                color = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        MiuixIcons.Contacts,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = uiState.selectedMember?.let {
                            "绑定成员: ${it.name} (${if (it.sex == Sex.MALE) "男" else "女"} ${it.heightCm.toInt()}cm)"
                        } ?: "全员智能自动匹配",
                        style = MiuixTheme.textStyles.button,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        MiuixIcons.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Connection Status Chip
            ConnectionStatusChip(
                state = uiState.connection,
                // 多设备自动连接下，「当前使用设备」与「正在连接的设备」可能不是同一台，
                // 优先展示实际连接的那台，避免状态栏显示错误的设备名
                deviceName = uiState.discoveredDeviceName ?: uiState.pairedDeviceName,
                isDeviceRemembered = uiState.isDeviceRemembered,
                isBluetoothEnabled = uiState.isBluetoothEnabled
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Weight Display with modern circle
            WeightDisplay(
                weight = uiState.liveWeightKg,
                isStable = uiState.isStable,
                isCompact = uiState.currentMeasurement != null
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Metrics Card
            AnimatedVisibility(
                visible = uiState.currentMeasurement != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                uiState.currentMeasurement?.let {
                    MeasurementResultCard(
                        measurement = it,
                        availableMembers = uiState.availableMembers,
                        onBindMember = onBindMember,
                        biaModelName = uiState.selectedBiaModel.displayName
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Button / Binding Guidance Card
            if (!uiState.isDeviceRemembered) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        contentColor = MiuixTheme.colorScheme.onSurface
                    ),
                    cornerRadius = 16.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "未绑定体脂秤",
                            style = MiuixTheme.textStyles.title2,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "手动连接并记住体脂秤后，App 将仅针对已记住的设备开启自动连接，防止误连周围其他设备；可同时记住多台，踩上任意一台都能自动连接。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onNavigateToPairing,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Icon(MiuixIcons.SearchDevice, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("搜索并绑定设备")
                        }
                    }
                }
            } else if (!uiState.isBluetoothEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.defaultColors(
                        color = MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        contentColor = MiuixTheme.colorScheme.onSurface
                    ),
                    cornerRadius = 16.dp
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "手机蓝牙已关闭",
                            style = MiuixTheme.textStyles.title2,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "需要开启手机蓝牙以连接已绑定的体脂秤并接收体重数据。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val intent = android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.error,
                                contentColor = MiuixTheme.colorScheme.onError
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(MiuixIcons.SearchDevice, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("开启手机蓝牙")
                        }
                    }
                }
            } else {
                if (uiState.connection == BleScaleClient.ConnectionState.IDLE) {
                    // 原 FilledTonalButton：默认 buttonColors()（secondaryVariant 灰底）即 Miuix 的 tonal 层级按钮
                    Button(
                        onClick = onStartScan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        cornerRadius = 16.dp
                    ) {
                        Text("开始称重", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                } else if (uiState.connection == BleScaleClient.ConnectionState.SCANNING) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val rememberedCount = uiState.rememberedScales.size
                        val scanningDeviceLabel = if (rememberedCount > 1) {
                            "$rememberedCount 台已记住设备"
                        } else {
                            uiState.pairedDeviceName ?: uiState.pairedDeviceMac ?: "体脂秤"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "正在寻找已记住的体脂秤 ($scanningDeviceLabel)...",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = onStartScan,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    MiuixIcons.Refresh,
                                    contentDescription = "重新扫描",
                                    modifier = Modifier.size(16.dp),
                                    tint = MiuixTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (uiState.showNewMemberAlert) {
        OverlayDialog(
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

    if (showMemberSelectDialog) {
        SelectMemberDialog(
            members = uiState.availableMembers,
            currentMemberId = uiState.selectedMember?.id,
            onDismiss = { showMemberSelectDialog = false },
            onSelect = { member ->
                onSelectMember(member)
                showMemberSelectDialog = false
            }
        )
    }
}

@Composable
fun ConnectionStatusChip(
    state: BleScaleClient.ConnectionState,
    deviceName: String? = null,
    isDeviceRemembered: Boolean = true,
    isBluetoothEnabled: Boolean = true
) {
    if (!isBluetoothEnabled) {
        Surface(
            color = MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
            shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, MiuixTheme.colorScheme.error.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MiuixTheme.colorScheme.error, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "手机蓝牙已关闭",
                    style = MiuixTheme.textStyles.button,
                    color = MiuixTheme.colorScheme.error
                )
            }
        }
        return
    }

    if (!isDeviceRemembered) {
        Surface(
            color = MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
            shape = CircleShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, MiuixTheme.colorScheme.error.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MiuixTheme.colorScheme.error, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "未绑定设备（自动连接已暂停）",
                    style = MiuixTheme.textStyles.button,
                    color = MiuixTheme.colorScheme.error
                )
            }
        }
        return
    }

    val color = when (state) {
        BleScaleClient.ConnectionState.IDLE       -> MiuixTheme.colorScheme.outline
        BleScaleClient.ConnectionState.SCANNING   -> MiuixTheme.colorScheme.primary
        // M3 tertiary 无同名 token，按语义就近取 secondary（保持与扫描中 primary 的视觉区分）
        BleScaleClient.ConnectionState.CONNECTING -> MiuixTheme.colorScheme.secondary
        BleScaleClient.ConnectionState.CONNECTED,
        BleScaleClient.ConnectionState.MEASURING  -> Color(0xFF4CAF50)
    }

    val label = when (state) {
        BleScaleClient.ConnectionState.IDLE -> if (deviceName != null) "就绪 · $deviceName" else "未连接"
        BleScaleClient.ConnectionState.SCANNING -> if (deviceName != null) "正在寻找 · $deviceName" else "搜索中"
        BleScaleClient.ConnectionState.CONNECTING -> if (deviceName != null) "正在连接 · $deviceName" else "连接中"
        BleScaleClient.ConnectionState.CONNECTED -> if (deviceName != null) "已连接 · $deviceName" else "已连接"
        BleScaleClient.ConnectionState.MEASURING -> if (deviceName != null) "测量中 · $deviceName" else "测量中"
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MiuixTheme.textStyles.button,
                color = color
            )
        }
    }
}

@Composable
fun WeightDisplay(weight: Double, isStable: Boolean, isCompact: Boolean = false) {
    // 闪烁动画只在"称重进行中且未稳定"时创建；
    // 体重为 0 或已稳定时不启动无限动画，避免无谓的每帧重组
    val blinking = !isStable && weight > 0
    val alpha = if (blinking) {
        val infiniteTransition = rememberInfiniteTransition()
        val a by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(500),
                repeatMode = RepeatMode.Reverse
            )
        )
        a
    } else {
        1f
    }

    val size = if (isCompact) 180.dp else 240.dp
    val fontSize = if (isCompact) 60.sp else 76.sp

    Box(
        modifier = Modifier
            .size(size)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        Color.Transparent
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format("%.2f", weight),
                fontSize = fontSize,
                fontWeight = FontWeight.Black,
                color = MiuixTheme.colorScheme.onBackground.copy(alpha = if (weight > 0) alpha else 0.3f)
            )
            Text(
                text = "kg",
                fontSize = if (isCompact) 20.sp else 24.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun MeasurementResultCard(
    measurement: BodyMeasurement,
    availableMembers: List<FamilyMember>,
    onBindMember: (FamilyMember) -> Unit,
    biaModelName: String? = null
) {
    var showBindDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 8.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
            contentColor = MiuixTheme.colorScheme.onSurface
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Member Binding Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        MiuixIcons.ContactsCircle,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MiuixTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "数据所属: ${measurement.memberNameSnapshot ?: "未绑定成员"}",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                if (availableMembers.isNotEmpty()) {
                    // 原 OutlinedButton：透明底 + outline 描边的小号次级按钮
                    TextButton(
                        text = "重选/绑定成员",
                        onClick = { showBindDialog = true },
                        modifier = Modifier
                            .height(32.dp)
                            .border(
                                1.dp,
                                MiuixTheme.colorScheme.outline,
                                RoundedCornerShape(12.dp)
                            ),
                        cornerRadius = 12.dp,
                        colors = ButtonDefaults.textButtonColors(
                            color = Color.Transparent,
                            textColor = MiuixTheme.colorScheme.primary
                        ),
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        textStyle = MiuixTheme.textStyles.button.copy(fontSize = 12.sp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val hasBia = measurement.impedanceOhm > 0.0 && measurement.bodyFatPct > 0.0
            // 从成员列表查找性别，用于体脂率健康区间判断（男女标准不同）
            val isMale = availableMembers.firstOrNull { it.id == measurement.memberId }
                ?.sex == com.example.dianzicheng.domain.Sex.MALE

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                MetricItem(
                    label = "BMI",
                    value = if (measurement.bmi > 0.0) String.format("%.1f", measurement.bmi) else "--",
                    status = if (measurement.bmi > 0.0) getBmiStatus(measurement.bmi) else null,
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "体脂率",
                    value = if (hasBia) String.format("%.1f%%", measurement.bodyFatPct) else "--",
                    status = if (hasBia) getFatStatus(measurement.bodyFatPct, isMale) else null,
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "水分",
                    value = if (hasBia) String.format("%.1f%%", measurement.waterPct) else "--",
                    status = if (hasBia && measurement.waterPct in 50.0..65.0) "标准" else if (hasBia) "注意" else null,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                MetricItem("肌肉量", if (hasBia) String.format("%.1fkg", measurement.muscleKg) else "--", modifier = Modifier.weight(1f))
                MetricItem("蛋白质", if (hasBia) String.format("%.1f%%", measurement.proteinPct) else "--", modifier = Modifier.weight(1f))
                MetricItem("骨量", if (hasBia) String.format("%.1fkg", measurement.boneMassKg) else "--", modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                MetricItem(
                    label = "阻抗",
                    value = if (measurement.impedanceOhm > 0.0) "${measurement.impedanceOhm.toInt()}Ω" else "未测出 (请赤脚称重)",
                    modifier = Modifier.weight(1f)
                )
            }

            // 当前使用的电阻抗估算模型标识：让用户一眼看出体脂率是按哪个公式算出来的
            if (hasBia && !biaModelName.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "估算模型：$biaModelName",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.outline
                )
            }
        }
    }

    if (showBindDialog) {
        SelectMemberDialog(
            members = availableMembers,
            currentMemberId = measurement.memberId,
            onDismiss = { showBindDialog = false },
            onSelect = { member ->
                if (member != null) {
                    onBindMember(member)
                }
                showBindDialog = false
            }
        )
    }
}

@Composable
fun SelectMemberDialog(
    members: List<FamilyMember>,
    currentMemberId: String?,
    onDismiss: () -> Unit,
    onSelect: (FamilyMember?) -> Unit
) {
    OverlayDialog(
        show = true,
        title = "选择绑定成员",
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "选择成员后，系统将使用该成员的身高、年龄与性别重新计算准确的体脂率等指标并保存绑定。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Auto Match Option
            Surface(
                onClick = { onSelect(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                color = if (currentMemberId == null) MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(MiuixIcons.Promotions, contentDescription = null, tint = MiuixTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("全员智能自动匹配", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (members.isEmpty()) {
                Text(
                    "暂无成员，请在“我的”页面添加家庭成员",
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
                            color = if (isSelected) MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    MiuixIcons.Contacts,
                                    contentDescription = null,
                                    tint = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = member.name, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "${if (member.sex == Sex.MALE) "男" else "女"} · ${member.heightCm.toInt()}cm",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                                    )
                                }
                                if (isSelected) {
                                    Icon(MiuixIcons.Basic.Check, contentDescription = null, tint = MiuixTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }

            // 原 AlertDialog confirmButton（右下角）→ 对话框内容底部右对齐按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}

/**
 * BMI 健康区间文案，历史页的指标卡片复用同一套标准。
 */
fun getBmiStatus(bmi: Double): String = when {
    bmi < 18.5 -> "偏瘦"
    bmi < 24.0 -> "标准"
    bmi < 28.0 -> "超重"
    else -> "肥胖"
}

/**
 * 体脂率健康区间文案（男女标准不同），历史页的指标卡片复用同一套标准。
 */
fun getFatStatus(fat: Double, isMale: Boolean): String = when {
    isMale -> when {
        fat < 8.0  -> "偏低"
        fat < 20.0 -> "标准"
        fat < 25.0 -> "偏高"
        else       -> "肥胖"
    }
    else -> when {
        fat < 17.0 -> "偏低"
        fat < 30.0 -> "标准"
        fat < 35.0 -> "偏高"
        else       -> "肥胖"
    }
}

@Composable
fun MetricItem(label: String, value: String, status: String? = null, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(text = label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.outline)
        Text(text = value, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
        if (status != null) {
            val color = if (status == "标准") Color(0xFF4CAF50) else MiuixTheme.colorScheme.error
            Surface(
                color = color.copy(alpha = 0.1f),
                shape = CircleShape,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = status,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MiuixTheme.textStyles.footnote2,
                    color = color,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun DashboardScreen(
    viewModel: ScaleViewModel,
    modifier: Modifier = Modifier,
    onNavigateToPairing: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkBluetoothAndLocationState()
    }

    // 仅当已有记住的设备且系统蓝牙已开启时，才在进入主页或蓝牙状态恢复时自动触发扫描。
    // 去除 uiState.connection 监听键，避免断开重连过渡期死循环触发或清除最新测量卡片
    LaunchedEffect(uiState.isDeviceRemembered, uiState.isBluetoothEnabled) {
        if (uiState.isDeviceRemembered && uiState.connection == BleScaleClient.ConnectionState.IDLE && uiState.isBluetoothEnabled) {
            viewModel.startScanning()
        }
    }

    DashboardContent(
        uiState = uiState,
        onStartScan = { viewModel.startScanning() },
        onDismissAlert = { viewModel.dismissAlert() },
        onSelectMember = { viewModel.selectMember(it) },
        onBindMember = { viewModel.bindCurrentMeasurementToMember(it) },
        onNavigateToPairing = onNavigateToPairing,
        modifier = modifier
    )
}
