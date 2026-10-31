package com.example.dianzicheng.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.dianzicheng.ui.page.MeasurePage
import com.example.dianzicheng.ui.sheet.CaptureSheet
import com.example.dianzicheng.ui.sheet.HistorySheet
import com.example.dianzicheng.ui.sheet.LogSheet
import com.example.dianzicheng.ui.sheet.PairingSheet
import com.example.dianzicheng.ui.sheet.SettingsSheet
import com.example.dianzicheng.ui.state.HistoryViewModel
import com.example.dianzicheng.ui.state.ProfileViewModel
import com.example.dianzicheng.ui.state.ScaleViewModel
import com.example.dianzicheng.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 单页面 + 弹层架构下唯一的弹层出口（同一时刻只展示一个底部弹层）。 */
private enum class ActiveOverlay {
    History, Settings, Pairing, LogViewer, CaptureViewer
}

/**
 * 应用根内容：**单页面架构**。
 *
 * - 默认单页面 = 测量主页；历史数据、我的/设置、配对、日志与抓包查看器
 *   全部以底部弹层（OverlayBottomSheet）承载，无页面导航栈；
 * - 测量完成（稳定 → 解锁且产生新记录）后自动从底部弹出历史数据弹层；
 * - `isPairingComplete` 三态门控保持旧行为：null=偏好未读出（纯背景，防误触发扫描）、
 *   false=强制配对引导（配对弹层不可关闭，「跳过」后进入正常态）、true=正常单页面。
 */
@Composable
fun ScaleApp(
    scaleViewModel: ScaleViewModel,
    historyViewModel: HistoryViewModel,
    profileViewModel: ProfileViewModel,
    isPairingComplete: Boolean?,
    onPairingComplete: () -> Unit
) {
    AppTheme {
        if (isPairingComplete == null) {
            // DataStore 尚未读出配对位：只渲染纯色背景，杜绝误挂配对弹层并触发扫描
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background)
            )
            return@AppTheme
        }

        val context = LocalContext.current
        val uiState by scaleViewModel.uiState.collectAsState()
        val history by historyViewModel.history.collectAsState()
        val members by historyViewModel.members.collectAsState()
        val metricOrder by historyViewModel.bodyMetricOrder.collectAsState()
        val statusMessage by profileViewModel.statusMessage.collectAsState()

        var activeOverlay by remember { mutableStateOf<ActiveOverlay?>(null) }

        // 首次配对流程：未完成配对时自动弹出配对弹层（不可关闭，直到绑定或跳过）
        LaunchedEffect(isPairingComplete) {
            if (isPairingComplete == false) {
                activeOverlay = ActiveOverlay.Pairing
            }
        }

        // 产品要求：测量完成后从底部弹出历史数据（稳定 → 解锁跳变且产生了新记录）
        var previousStable by remember { mutableStateOf(false) }
        var promptedMeasurementId by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(uiState.isStable) {
            val finishedNow = previousStable && !uiState.isStable
            val measurement = uiState.currentMeasurement
            if (finishedNow && measurement != null && measurement.id != promptedMeasurementId) {
                promptedMeasurementId = measurement.id
                activeOverlay = ActiveOverlay.History
            }
            previousStable = uiState.isStable
        }

        // 一次性反馈（复制/分享/云同步/重算等）统一走 Toast，消费即清
        LaunchedEffect(statusMessage) {
            statusMessage?.let {
                Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                profileViewModel.clearStatusMessage()
            }
        }

        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = "体重秤",
                    actions = {
                        IconButton(onClick = { activeOverlay = ActiveOverlay.History }) {
                            Icon(
                                imageVector = MiuixIcons.Recent,
                                contentDescription = "历史数据"
                            )
                        }
                        IconButton(onClick = { activeOverlay = ActiveOverlay.Settings }) {
                            Icon(
                                imageVector = MiuixIcons.Settings,
                                contentDescription = "我的"
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            MeasurePage(
                uiState = uiState,
                onStartScan = scaleViewModel::startScanning,
                onOpenPairing = { activeOverlay = ActiveOverlay.Pairing },
                onSelectMember = scaleViewModel::selectMember,
                onBindMember = scaleViewModel::bindCurrentMeasurementToMember,
                onDismissAlert = scaleViewModel::dismissAlert,
                modifier = Modifier.padding(innerPadding)
            )
        }

        // ── 底部弹层：同一时刻只展示一个 ────────────────────────────────────
        HistorySheet(
            show = activeOverlay == ActiveOverlay.History,
            history = history,
            members = members,
            metricOrder = metricOrder,
            onSaveMetricOrder = historyViewModel::saveBodyMetricOrder,
            onDelete = historyViewModel::deleteMeasurement,
            onDismissRequest = { activeOverlay = null }
        )

        SettingsSheet(
            show = activeOverlay == ActiveOverlay.Settings,
            profileViewModel = profileViewModel,
            onOpenPairing = { activeOverlay = ActiveOverlay.Pairing },
            onOpenLogViewer = { activeOverlay = ActiveOverlay.LogViewer },
            onOpenCapture = { activeOverlay = ActiveOverlay.CaptureViewer },
            onDismissRequest = { activeOverlay = null }
        )

        PairingSheet(
            show = activeOverlay == ActiveOverlay.Pairing,
            mandatory = isPairingComplete == false,
            uiState = uiState,
            onStartPairingScan = scaleViewModel::startPairingScan,
            onStopPairingScan = scaleViewModel::stopPairingScan,
            onBindDevice = scaleViewModel::pairAndConnectDevice,
            onSwitchActiveScale = scaleViewModel::switchActiveScale,
            onSelectScaleModel = scaleViewModel::selectScaleModel,
            onFinished = {
                activeOverlay = null
                if (isPairingComplete == false) onPairingComplete()
            },
            onDismissRequest = { activeOverlay = null }
        )

        LogSheet(
            show = activeOverlay == ActiveOverlay.LogViewer,
            profileViewModel = profileViewModel,
            onDismissRequest = { activeOverlay = null }
        )

        CaptureSheet(
            show = activeOverlay == ActiveOverlay.CaptureViewer,
            profileViewModel = profileViewModel,
            onDismissRequest = { activeOverlay = null }
        )
    }
}
