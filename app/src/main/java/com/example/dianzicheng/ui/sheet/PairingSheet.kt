package com.example.dianzicheng.ui.sheet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.example.dianzicheng.domain.ScaleModel
import com.example.dianzicheng.ui.component.HealthGreen
import com.example.dianzicheng.ui.dialog.SelectScaleModelDialog
import com.example.dianzicheng.ui.state.ScaleUiState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import top.yukonga.miuix.kmp.icon.extended.Tune
import java.util.Locale
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 应用自有语义色：正在称重 pill 的深绿文字。 */
private val HealthGreenDeep = Color(0xFF2E7D32)

/** 是否已有所需蓝牙权限：Android 12+ 需 SCAN+CONNECT；低版本需定位（二取一）。 */
private fun hasBlePermissions(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

/**
 * 配对 / 设备管理底部弹层。
 *
 * - 打开即进入配对扫描（只收集候选、绝不自动连接），关闭即停止扫描；
 * - [mandatory] = true 时为首次配对引导：不可关闭，提供「跳过 / 暂不绑定」出口；
 * - 支持多台已记住设备：点选切换当前设备、继续绑定追加新设备。
 */
@Composable
fun PairingSheet(
    show: Boolean,
    mandatory: Boolean,
    uiState: ScaleUiState,
    onStartPairingScan: () -> Unit,
    onStopPairingScan: () -> Unit,
    onBindDevice: (BleScaleClient.DiscoveredScaleDevice) -> Unit,
    onSwitchActiveScale: (String) -> Unit,
    onSelectScaleModel: (ScaleModel) -> Unit,
    onFinished: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    var bindingMac by remember { mutableStateOf<String?>(null) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showOtherDevices by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasBlePermissions(context)) onStartPairingScan()
    }
    val startScanWithPermission = {
        if (hasBlePermissions(context)) {
            onStartPairingScan()
        } else {
            val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
            permissionLauncher.launch(required)
        }
    }

    // 打开进入配对扫描；关闭停止扫描（保持旧页面 onDispose 语义）
    val currentOnStop by rememberUpdatedState(onStopPairingScan)
    LaunchedEffect(show) {
        if (show) {
            startScanWithPermission()
        } else {
            currentOnStop()
        }
    }
    DisposableEffect(Unit) {
        onDispose { currentOnStop() }
    }

    // 列表派生：实时称重中的秤置顶，其余确认秤随后，未识别设备折叠兜底
    val confirmedScales = uiState.discoveredScales.filter { it.isConfirmedScale }
    val otherDevices = uiState.discoveredScales.filter { !it.isConfirmedScale }
    val liveWeighingScale = confirmedScales.firstOrNull { (it.liveWeightKg ?: 0.0) > 0.0 }

    OverlayBottomSheet(
        show = show,
        title = if (uiState.rememberedScales.isEmpty()) "绑定体脂秤" else "体脂秤管理",
        endAction = {
            IconButton(onClick = { showModelDialog = true }) {
                Icon(
                    imageVector = MiuixIcons.Tune,
                    contentDescription = "选择特定型号偏好",
                    tint = if (uiState.selectedScaleModel != ScaleModel.AUTO) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    }
                )
            }
        },
        onDismissRequest = if (mandatory) null else onDismissRequest,
        allowDismiss = !mandatory
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            // ① 型号偏好指示条
            if (uiState.selectedScaleModel != ScaleModel.AUTO) {
                item {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MiuixTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
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
                                    onSelectScaleModel(ScaleModel.AUTO)
                                    startScanWithPermission()
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    textColor = MiuixTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }

            // ② 异常引导（命中即不再展示扫描区）
            if (!uiState.isBluetoothEnabled) {
                item {
                    DisabledGuideCard(
                        title = "手机蓝牙已关闭",
                        body = "需要开启蓝牙才能搜索并连接体脂秤。",
                        actionText = "开启手机蓝牙",
                        onAction = {
                            context.startActivity(
                                android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            )
                        }
                    )
                }
                return@LazyColumn
            }
            if (!uiState.isLocationEnabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                item {
                    DisabledGuideCard(
                        title = "定位服务未开启",
                        body = "该系统版本需要开启定位服务才能扫描到低功耗蓝牙广播。",
                        actionText = "前往开启系统定位",
                        onAction = {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            )
                        }
                    )
                }
                return@LazyColumn
            }

            // ③ 扫描状态 hero
            item { ScanningHero(hasDevices = uiState.discoveredScales.isNotEmpty()) }

            // ④ 已记住设备区
            if (uiState.rememberedScales.isNotEmpty()) {
                item {
                    SmallTitle(text = "已记住的体脂秤 (${uiState.rememberedScales.size})：")
                }
                items(
                    count = uiState.rememberedScales.size,
                    key = { "remembered_${uiState.rememberedScales[it].mac}" }
                ) { index ->
                    val scale = uiState.rememberedScales[index]
                    RememberedScaleCard(
                        scale = scale,
                        isActive = scale.mac == uiState.activeScaleMac,
                        onClick = { onSwitchActiveScale(scale.mac) },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
                item {
                    Text(
                        text = "提示：踩上已记住的任意一台体脂秤都会自动连接并称重，无需在此手动切换；下方继续绑定即可追加新的体脂秤。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            // ⑤ 实时称重中的秤（置顶）
            if (liveWeighingScale != null) {
                item(key = "live_${liveWeighingScale.address}") {
                    ActiveWeighingCard(
                        device = liveWeighingScale,
                        isBinding = bindingMac == liveWeighingScale.address,
                        onBind = {
                            bindingMac = liveWeighingScale.address
                            onBindDevice(liveWeighingScale)
                        },
                        onBound = onFinished,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }

            // ⑥ 其余确认秤
            val otherConfirmed = confirmedScales.filter { it != liveWeighingScale }
            if (otherConfirmed.isNotEmpty()) {
                item {
                    SmallTitle(
                        text = if (liveWeighingScale != null) {
                            "其它附近的体脂秤 (${otherConfirmed.size})："
                        } else {
                            "发现附近体脂秤 (${otherConfirmed.size})："
                        }
                    )
                }
                items(
                    count = otherConfirmed.size,
                    key = { "confirmed_${otherConfirmed[it].address}" }
                ) { index ->
                    val device = otherConfirmed[index]
                    ConfirmedScaleCard(
                        device = device,
                        isBinding = bindingMac == device.address,
                        isBound = uiState.rememberedScales.any { it.mac == device.address },
                        onBind = {
                            bindingMac = device.address
                            onBindDevice(device)
                        },
                        onBound = onFinished,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }

            // ⑦ 空态引导
            if (confirmedScales.isEmpty()) {
                item {
                    SearchingGuidanceCard(
                        onFilterByModel = { showModelDialog = true },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }

            // ⑧ 未识别蓝牙设备（折叠兜底区）
            if (otherDevices.isNotEmpty()) {
                item {
                    Surface(
                        onClick = { showOtherDevices = !showOtherDevices },
                        shape = RoundedCornerShape(16.dp),
                        color = MiuixTheme.colorScheme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "其它未识别的蓝牙设备 (${otherDevices.size} 台)",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (showOtherDevices) "收起 ▲" else "展开 ▼",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions
                            )
                        }
                    }
                }
                if (showOtherDevices) {
                    items(
                        count = otherDevices.size,
                        key = { "other_${otherDevices[it].address}" }
                    ) { index ->
                        val device = otherDevices[index]
                        OtherDeviceCard(
                            device = device,
                            isBinding = bindingMac == device.address,
                            onBind = {
                                bindingMac = device.address
                                onBindDevice(device)
                            },
                            onBound = onFinished,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // ⑨ 操作区
            item {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                    Button(
                        onClick = startScanWithPermission,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            MiuixIcons.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("重新搜索设备", style = MiuixTheme.textStyles.button)
                    }
                    if (mandatory) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            text = "暂不绑定，先去主页",
                            onClick = onFinished,
                            colors = ButtonDefaults.textButtonColors(
                                textColor = MiuixTheme.colorScheme.onSurfaceSecondary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    if (showModelDialog) {
        SelectScaleModelDialog(
            currentModel = uiState.selectedScaleModel,
            onModelSelected = { model ->
                onSelectScaleModel(model)
                if (hasBlePermissions(context)) onStartPairingScan()
            },
            onDismiss = { showModelDialog = false }
        )
    }
}

// ── 配对页小件 ─────────────────────────────────────────────────────────────

/** 蓝牙 / 定位未开启的引导卡。 */
@Composable
private fun DisabledGuideCard(
    title: String,
    body: String,
    actionText: String,
    onAction: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = MiuixIcons.SearchDevice,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.error,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = title, style = MiuixTheme.textStyles.title3, color = MiuixTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = body,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    color = MiuixTheme.colorScheme.error,
                    contentColor = MiuixTheme.colorScheme.onError
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(actionText, style = MiuixTheme.textStyles.button)
            }
        }
    }
}

/** 扫描状态 hero：有设备显示即时读数提示，无设备显示雷达扫描动画。 */
@Composable
private fun ScanningHero(hasDevices: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (hasDevices) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MiuixTheme.colorScheme.primaryContainer
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    PulsingDot(color = HealthGreen)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "已发现附近设备 · 踩上秤即可即时读取示数",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        } else {
            Box(contentAlignment = Alignment.Center) {
                val transition = rememberInfiniteTransition(label = "radar")
                val scale by transition.animateFloat(
                    initialValue = 0.95f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
                    label = "radar-scale"
                )
                val alpha by transition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
                    label = "radar-alpha"
                )
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(scale)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.25f))
                )
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = MiuixIcons.SearchDevice,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "正在寻找附近的体脂秤...",
                style = MiuixTheme.textStyles.title2,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "请双脚踩上秤面唤醒屏幕（屏幕亮起后发射蓝牙广播）",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }
    }
}

/** 呼吸圆点（扫描/称重进行中的绿色指示）。 */
@Composable
private fun PulsingDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "dot-alpha"
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

/** 已记住体脂秤卡片：点击切换当前使用设备。 */
@Composable
private fun RememberedScaleCard(
    scale: com.example.dianzicheng.domain.RememberedScale,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) MiuixTheme.colorScheme.primaryContainer
        else MiuixTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.secondaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MiuixIcons.SearchDevice,
                    contentDescription = null,
                    tint = if (isActive) MiuixTheme.colorScheme.onPrimary
                    else MiuixTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = scale.name, style = MiuixTheme.textStyles.title3)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = scale.detail.ifBlank { scale.model.displayName } + " · " + scale.mac,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
            if (isActive) {
                Surface(
                    color = MiuixTheme.colorScheme.primary,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Basic.Check,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "使用中",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onPrimary
                        )
                    }
                }
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

/** 正在称重的设备卡片：展示即时体重与「立即绑定」。 */
@Composable
private fun ActiveWeighingCard(
    device: BleScaleClient.DiscoveredScaleDevice,
    isBinding: Boolean,
    onBind: () -> Unit,
    onBound: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MiuixTheme.colorScheme.primaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .border(1.8.dp, MiuixTheme.colorScheme.primary, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = HealthGreen.copy(alpha = 0.15f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        PulsingDot(color = HealthGreenDeep)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "正在称重 · 即时读数",
                            style = MiuixTheme.textStyles.footnote2,
                            color = HealthGreenDeep
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "信号良好",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = String.format(Locale.US, "%.2f kg", device.liveWeightKg ?: 0.0),
                style = MiuixTheme.textStyles.headline1.copy(fontSize = 34.sp),
                fontWeight = FontWeight.ExtraBold,
                color = MiuixTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = device.name, style = MiuixTheme.textStyles.body1)
            Text(
                text = (device.protocolName.ifBlank { "智能蓝牙广播" }) + " · " + device.address,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    onBind()
                    // 与旧实现一致：立即存盘 + 对勾反馈，不等待 GATT 结果，400ms 后收尾
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ onBound() }, 400)
                },
                enabled = !isBinding,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isBinding) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("绑定中", style = MiuixTheme.textStyles.button)
                } else {
                    Icon(MiuixIcons.Basic.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("立即绑定", style = MiuixTheme.textStyles.button)
                }
            }
        }
    }
}

/** 发现的体脂秤卡片：绑定按钮三态（绑定中 / 已绑定 / 立即绑定）。 */
@Composable
private fun ConfirmedScaleCard(
    device: BleScaleClient.DiscoveredScaleDevice,
    isBinding: Boolean,
    isBound: Boolean,
    onBind: () -> Unit,
    onBound: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MiuixTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MiuixIcons.SearchDevice,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.name,
                        style = MiuixTheme.textStyles.body1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = MiuixTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "体脂秤",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = (device.protocolName.ifBlank { device.matchedModel.displayName }) + " · " + device.address,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            when {
                isBinding -> {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                isBound -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = MiuixIcons.Basic.Check,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "已绑定",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                }
                else -> {
                    Button(onClick = {
                        onBind()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ onBound() }, 400)
                    }) {
                        Text("立即绑定", style = MiuixTheme.textStyles.footnote1)
                    }
                }
            }
        }
    }
}

/** 空态：体脂秤唤醒与排查提示。 */
@Composable
private fun SearchingGuidanceCard(
    onFilterByModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MiuixTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
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
                    style = MiuixTheme.textStyles.body1
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "1. 大多数体脂秤熄屏时会自动休眠并关闭蓝牙，请双脚站上秤踩亮屏幕；\n" +
                    "2. 请保持手机与体脂秤在 3 米以内，且手机蓝牙与定位服务处于开启状态；\n" +
                    "3. 若使用的是薄荷健康/沃莱或小米体脂秤 2 代，可尝试手动指定型号。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(text = "按品牌型号筛选 (薄荷健康 / 小米)", onClick = onFilterByModel)
        }
    }
}

/** 未识别蓝牙设备卡片（兜底逃生通道：可手动绑定为此秤）。 */
@Composable
private fun OtherDeviceCard(
    device: BleScaleClient.DiscoveredScaleDevice,
    isBinding: Boolean,
    onBind: () -> Unit,
    onBound: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MiuixTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = device.name, style = MiuixTheme.textStyles.body2)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${device.address} · ${device.rssi} dBm",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
            if (isBinding) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                TextButton(
                    text = "绑定为此秤",
                    onClick = {
                        onBind()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ onBound() }, 400)
                    }
                )
            }
        }
    }
}
