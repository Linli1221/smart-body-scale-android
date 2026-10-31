package com.example.dianzicheng.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.domain.RememberedScale
import com.example.dianzicheng.domain.ScaleModel
import com.example.dianzicheng.domain.sameMacAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首次连接与设备绑定页面。
 *
 * 核心交互逻辑：
 * 1. 引导用户站上秤面唤醒屏幕发射广播。
 * 2. 严格优先展示体脂秤/体重秤设备，隔离无关蓝牙设备（耳机、手表、电视等）。
 * 3. 动态感知用户当前踩秤动作，若捕捉到实时体重（如 65.20 kg）立即置顶加粗高亮展示。
 * 4. 用户点击「立即绑定」后立即存盘并给予对勾反馈，平滑过渡至主页，杜绝旧逻辑中因 GATT 超时卡死的弊端。
 */
@Composable
fun PairingScreen(
    viewModel: ScaleViewModel,
    onPairingComplete: () -> Unit,
    onNavigateBack: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var bindingMac by remember { mutableStateOf<String?>(null) }
    var boundSuccess by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showOtherDevices by remember { mutableStateOf(false) }

    // 蓝牙与定位权限配置
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasBlePermissions()) {
            viewModel.startPairingScan()
        }
    }

    // 页面进入时自启动配对扫描
    LaunchedEffect(Unit) {
        if (hasBlePermissions()) {
            viewModel.startPairingScan()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // 离开页面时注销配对模式。
    // 注意：DisposableEffect(Unit) 的 onDispose 会捕获创建时的 uiState 快照，
    // 用捕获值判断"是否正在扫描"会读到陈旧状态，导致扫描残留、持续耗电。
    // 因此这里通过 rememberUpdatedState 持有最新状态引用。
    val latestConnection by rememberUpdatedState(uiState.connection)
    DisposableEffect(Unit) {
        onDispose {
            if (latestConnection == BleScaleClient.ConnectionState.SCANNING) {
                viewModel.stopPairingScan()
            }
        }
    }

    // 执行设备绑定操作
    val onBindDevice: (BleScaleClient.DiscoveredScaleDevice) -> Unit = { scale ->
        bindingMac = scale.address
        boundSuccess = true
        viewModel.pairAndConnectDevice(scale)
        coroutineScope.launch {
            // 稍作停顿展现绑定成功状态，随后平滑导航至主界面
            delay(400)
            onPairingComplete()
        }
    }

    // 过滤已知体脂秤与其它蓝牙杂项
    val confirmedScales = remember(uiState.discoveredScales) {
        uiState.discoveredScales.filter { it.isConfirmedScale }
    }
    val otherDevices = remember(uiState.discoveredScales) {
        uiState.discoveredScales.filter { !it.isConfirmedScale }
    }
    // 是否有设备正在被踩着称重中
    val liveWeighingScale = remember(confirmedScales) {
        confirmedScales.firstOrNull { it.liveWeightKg != null && it.liveWeightKg > 0.0 }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = if (uiState.rememberedScales.isEmpty()) "绑定体脂秤" else "体脂秤管理",
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回"
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showModelDialog = true }) {
                        Icon(
                            imageVector = MiuixIcons.Tune,
                            contentDescription = "选择特定型号偏好",
                            tint = if (uiState.selectedScaleModel != ScaleModel.AUTO) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
                        )
                    }
                    if (onNavigateBack == null) {
                        TextButton(
                            text = "跳过",
                            onClick = onPairingComplete,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            textStyle = MiuixTheme.textStyles.button.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        if (hasBlePermissions()) {
                            viewModel.startPairingScan()
                        } else {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(MiuixIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("重新搜索设备", style = MiuixTheme.textStyles.button)
                }
                if (onNavigateBack == null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        text = "暂不绑定，先去主页",
                        onClick = onPairingComplete,
                        colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.onSurfaceSecondary)
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 型号偏好轻量指示 ──
            if (uiState.selectedScaleModel != ScaleModel.AUTO) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Info,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "优先匹配协议: ${uiState.selectedScaleModel.displayName}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "重置为自动",
                            onClick = {
                                viewModel.selectScaleModel(ScaleModel.AUTO)
                                viewModel.startPairingScan()
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            insideMargin = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            textStyle = MiuixTheme.textStyles.footnote2
                        )
                    }
                }
            }

            // ── 异常状态提示（蓝牙关闭 / 定位未开启） ──
            if (!uiState.isBluetoothEnabled) {
                BluetoothDisabledCard(
                    onEnableBluetooth = {
                        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        context.startActivity(intent)
                    }
                )
                return@Scaffold
            } else if (!uiState.isLocationEnabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                LocationDisabledCard(
                    onEnableLocation = {
                        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        context.startActivity(intent)
                    }
                )
                return@Scaffold
            }

            // ── 核心引导与波纹雷达动画区 ──
            RadarScanningHero(
                isScanning = uiState.connection == BleScaleClient.ConnectionState.SCANNING,
                hasDevices = confirmedScales.isNotEmpty()
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ── 设备列表展示 ──
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 0. 已记住的体脂秤（支持同时记住多台，此处可查看并切换当前使用设备）
                if (uiState.rememberedScales.isNotEmpty()) {
                    item(key = "remembered_header") {
                        Text(
                            text = "已记住的体脂秤 (${uiState.rememberedScales.size})：",
                            style = MiuixTheme.textStyles.footnote2,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                        )
                    }
                    items(uiState.rememberedScales, key = { "remembered_${it.mac}" }) { remembered ->
                        RememberedScaleCard(
                            scale = remembered,
                            isActive = sameMacAddress(remembered.mac, uiState.activeScaleMac),
                            onActivate = { viewModel.switchActiveScale(remembered.mac) }
                        )
                    }
                    item(key = "remembered_hint") {
                        Text(
                            text = "提示：踩上已记住的任意一台体脂秤都会自动连接并称重，无需在此手动切换；" +
                                "下方继续绑定即可追加新的体脂秤。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.outline
                        )
                    }
                }

                // 1. 如果有秤正在称重中，置顶高亮该秤！
                liveWeighingScale?.let { activeScale ->
                    item(key = "active_scale_${activeScale.address}") {
                        ActiveWeighingCard(
                            scale = activeScale,
                            isBinding = bindingMac == activeScale.address,
                            onBind = { onBindDevice(activeScale) }
                        )
                    }
                }

                // 2. 其它确认的体脂秤列表
                val normalConfirmedScales = confirmedScales.filter { it.address != liveWeighingScale?.address }
                if (normalConfirmedScales.isNotEmpty()) {
                    item(key = "header_confirmed") {
                        Text(
                            text = if (liveWeighingScale != null) "其它附近的体脂秤 (${normalConfirmedScales.size})：" else "发现附近体脂秤 (${normalConfirmedScales.size})：",
                            style = MiuixTheme.textStyles.footnote2,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                        )
                    }
                    items(normalConfirmedScales, key = { it.address }) { scale ->
                        ConfirmedScaleCard(
                            scale = scale,
                            isBinding = bindingMac == scale.address,
                            isBound = boundSuccess && bindingMac == scale.address,
                            onBind = { onBindDevice(scale) }
                        )
                    }
                }

                // 3. 当未搜到任何秤时呈现等待指引卡片
                if (confirmedScales.isEmpty()) {
                    item(key = "empty_guidance") {
                        SearchingGuidanceCard(onShowModelDialog = { showModelDialog = true })
                    }
                }

                // 4. 未识别的其它蓝牙设备折叠展示（兜底逃生通道，绝不干扰普通用户）
                if (otherDevices.isNotEmpty()) {
                    item(key = "other_devices_accordion") {
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            onClick = { showOtherDevices = !showOtherDevices },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.defaultColors(
                                color = MiuixTheme.colorScheme.surface,
                                contentColor = MiuixTheme.colorScheme.onSurface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "其它未识别的蓝牙设备 (${otherDevices.size} 台)",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                                )
                                Text(
                                    text = if (showOtherDevices) "收起 ▲" else "展开 ▼",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (showOtherDevices) {
                        items(otherDevices, key = { it.address }) { other ->
                            OtherDeviceCard(
                                device = other,
                                isBinding = bindingMac == other.address,
                                onBind = { onBindDevice(other) }
                            )
                        }
                    }
                }
            }
        }
    }

    // 型号偏好选择弹窗
    if (showModelDialog) {
        SelectScaleModelDialog(
            currentModel = uiState.selectedScaleModel,
            onModelSelected = { model ->
                viewModel.selectScaleModel(model)
                if (hasBlePermissions()) {
                    viewModel.startPairingScan()
                }
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false }
        )
    }
}

/**
 * 带有波纹动效的雷达与踩秤引导区域。
 */
@Composable
private fun RadarScanningHero(
    isScanning: Boolean,
    hasDevices: Boolean
) {
    if (hasDevices) {
        // 发现设备后，以紧凑精致的指示条呈现，将视觉重心让给设备卡片
        Surface(
            shape = CircleShape,
            color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "compact_pulse")
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = EaseInOutCubic),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "compact_alpha"
                )

                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4CAF50).copy(alpha = if (isScanning) pulseAlpha else 1f))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "已发现附近设备 · 踩上秤即可即时读取示数",
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    } else {
        // 未发现设备时，展示优雅的波纹雷达动效
        val infiniteTransition = rememberInfiniteTransition(label = "radar_pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.95f,
            targetValue = 1.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = EaseOutQuad),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse_scale"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 0.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = EaseOutQuad),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulse_alpha"
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Box(
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isScanning) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = pulseAlpha))
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MiuixTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = MiuixIcons.SearchDevice,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MiuixTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "正在寻找附近的体脂秤...",
                style = MiuixTheme.textStyles.title2,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "请双脚踩上秤面唤醒屏幕（屏幕亮起后发射蓝牙广播）",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 「已记住的体脂秤」列表卡片（配对页复用）。
 *
 * 整行可点击 → 切换为当前使用设备；已在使用的设备显示对勾与高亮底色。
 */
@Composable
private fun RememberedScaleCard(
    scale: RememberedScale,
    isActive: Boolean,
    onActivate: () -> Unit
) {
    Card(
        onClick = onActivate,
        modifier = Modifier.fillMaxWidth(),
        showIndication = true,
        colors = CardDefaults.defaultColors(
            color = if (isActive) {
                MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.25f)
            },
            contentColor = MiuixTheme.colorScheme.onSurface
        ),
        cornerRadius = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isActive) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.primaryContainer
                },
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = MiuixIcons.SearchDevice,
                        contentDescription = null,
                        tint = if (isActive) {
                            MiuixTheme.colorScheme.onPrimary
                        } else {
                            MiuixTheme.colorScheme.onPrimaryContainer
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = scale.name,
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MiuixTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "使用中",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${scale.detail.ifBlank { scale.model.displayName }} · ${scale.mac}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
            if (isActive) {
                Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = "设为当前",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 实时踩秤检测高亮卡片（即踩即显）。
 */
@Composable
private fun ActiveWeighingCard(
    scale: BleScaleClient.DiscoveredScaleDevice,
    isBinding: Boolean,
    onBind: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.8.dp, MiuixTheme.colorScheme.primary),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF4CAF50).copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "正在称重 · 即时读数",
                            style = MiuixTheme.textStyles.footnote2,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }

                Text(
                    text = "信号良好",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Text(
                        text = "${String.format("%.2f", scale.liveWeightKg ?: 0.0)} kg",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MiuixTheme.colorScheme.primary
                    )
                    Text(
                        text = scale.name,
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${scale.protocolName.ifBlank { "智能蓝牙广播" }} · ${scale.address}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onBind,
                    enabled = !isBinding,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    cornerRadius = 12.dp,
                    insideMargin = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    if (isBinding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                foregroundColor = MiuixTheme.colorScheme.onPrimary,
                                backgroundColor = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.3f)
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("绑定中", style = MiuixTheme.textStyles.button, maxLines = 1)
                    } else {
                        Icon(MiuixIcons.Basic.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("立即绑定", style = MiuixTheme.textStyles.button, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * 确认的体脂秤列表卡片。
 */
@Composable
private fun ConfirmedScaleCard(
    scale: BleScaleClient.DiscoveredScaleDevice,
    isBinding: Boolean,
    isBound: Boolean,
    onBind: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.35f),
            contentColor = MiuixTheme.colorScheme.onSurface
        ),
        cornerRadius = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MiuixTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = MiuixIcons.SearchDevice,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = scale.name,
                        style = MiuixTheme.textStyles.title2,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MiuixTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "体脂秤",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${scale.protocolName.ifBlank { scale.matchedModel.displayName }} · ${scale.address}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onBind,
                enabled = !isBinding && !isBound,
                colors = ButtonDefaults.buttonColorsPrimary(),
                cornerRadius = 12.dp,
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                if (isBinding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        colors = ProgressIndicatorDefaults.progressIndicatorColors(
                            foregroundColor = MiuixTheme.colorScheme.onPrimary,
                            backgroundColor = MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.3f)
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("绑定中", style = MiuixTheme.textStyles.footnote2, maxLines = 1)
                } else if (isBound) {
                    Icon(MiuixIcons.Basic.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("已绑定", style = MiuixTheme.textStyles.footnote2, maxLines = 1)
                } else {
                    Text("立即绑定", fontWeight = FontWeight.Bold, style = MiuixTheme.textStyles.footnote2, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 未搜到秤时的排查指引卡片。
 */
@Composable
private fun SearchingGuidanceCard(
    onShowModelDialog: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.25f),
            contentColor = MiuixTheme.colorScheme.onSurface
        ),
        cornerRadius = 12.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "体脂秤唤醒与排查提示",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "1. 大多数体脂秤熄屏时会自动休眠并关闭蓝牙，请双脚站上秤踩亮屏幕；\n" +
                       "2. 请保持手机与体脂秤在 3 米以内，且手机蓝牙与定位服务处于开启状态；\n" +
                       "3. 若使用的是薄荷健康/沃莱或小米体脂秤 2 代，可尝试手动指定型号。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onShowModelDialog,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(MiuixIcons.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("按品牌型号筛选 (薄荷健康 / 小米)", style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/**
 * 其它普通蓝牙设备卡片（折叠备用）。
 */
@Composable
private fun OtherDeviceCard(
    device: BleScaleClient.DiscoveredScaleDevice,
    isBinding: Boolean,
    onBind: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.15f),
            contentColor = MiuixTheme.colorScheme.onSurface
        ),
        cornerRadius = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${device.address} · ${device.rssi} dBm",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.outline
                )
            }
            TextButton(
                text = if (isBinding) "绑定中" else "绑定为此秤",
                onClick = onBind,
                enabled = !isBinding,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                textStyle = MiuixTheme.textStyles.footnote2
            )
        }
    }
}

@Composable
private fun BluetoothDisabledCard(onEnableBluetooth: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
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
            Icon(MiuixIcons.SearchDevice, contentDescription = null, tint = MiuixTheme.colorScheme.error, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("手机蓝牙已关闭", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(6.dp))
            Text("需要开启蓝牙才能搜索并连接体脂秤。", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onEnableBluetooth,
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.error,
                    contentColor = MiuixTheme.colorScheme.onError
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("开启手机蓝牙", style = MiuixTheme.textStyles.button)
            }
        }
    }
}

@Composable
private fun LocationDisabledCard(onEnableLocation: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
            contentColor = MiuixTheme.colorScheme.onSurface
        ),
        cornerRadius = 16.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(MiuixIcons.Report, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("定位服务未开启", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            Text("该系统版本需要开启定位服务才能扫描到低功耗蓝牙广播。", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onEnableLocation,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("前往开启系统定位", style = MiuixTheme.textStyles.button)
            }
        }
    }
}
