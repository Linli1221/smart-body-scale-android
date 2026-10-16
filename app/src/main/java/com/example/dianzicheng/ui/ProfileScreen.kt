package com.example.dianzicheng.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.health.connect.client.PermissionController
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.dianzicheng.data.ble.BleProtocolRecorder
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.RememberedScale
import com.example.dianzicheng.domain.ScaleModel
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.domain.sameMacAddress
import com.example.dianzicheng.ui.theme.电子秤Theme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileContent(
    members: List<FamilyMember>,
    onAddMember: (String, Sex, Double, Long, Double) -> Unit,
    onDeleteMember: (FamilyMember) -> Unit,
    onNavigateToPairing: () -> Unit = {},
    pairedMac: String? = null,
    pairedDeviceName: String? = null,
    /** 全部已记住的体脂秤（最近使用优先）；支持同时记住多台并切换当前使用设备 */
    rememberedScales: List<RememberedScale> = emptyList(),
    /** 点击列表中的某台设备：把它切换为当前使用设备 */
    onSelectScale: (String) -> Unit = {},
    /** 点击某台设备右侧的删除按钮：把它从已记住列表中移除 */
    onRemoveScale: (String) -> Unit = {},
    selectedScaleModel: ScaleModel = ScaleModel.AUTO,
    onSelectScaleModel: (ScaleModel) -> Unit = {},
    healthConnectEnabled: Boolean,
    onToggleHealthConnect: (Boolean) -> Unit,
    onRequestHealthConnectPermissions: () -> Unit,
    onBatchSyncToHealthConnect: () -> Unit,
    onOpenMiHealth: () -> Unit,
    onOpenHealthConnectSettings: () -> Unit,
    syncProgress: String?,
    isMiHealthInstalled: Boolean,
    webdavUrl: String,
    webdavUsername: String,
    webdavPassword: String,
    lastBackupTime: Long,
    onSaveWebdavConfig: (String, String, String) -> Unit,
    onTestWebdavConnection: (String, String, String) -> Unit,
    onBackupData: () -> Unit,
    onRestoreData: () -> Unit,
    isOperating: Boolean,
    logEntries: List<AppLogger.LogEntry> = emptyList(),
    onClearLogs: () -> Unit = {},
    onShareLogs: () -> Unit = {},
    /** Debug 专用：当前构建是否提供「查看运行日志」入口（Release 恒为 false，入口与弹窗整体隐藏） */
    isLogViewerSupported: Boolean = false,
    /** Debug 专用：当前构建是否开启蓝牙协议抓包导出（Release 恒为 false，入口整体隐藏） */
    isBleCaptureSupported: Boolean = false,
    /** Debug 专用：蓝牙协议抓包记录 */
    bleCaptureEntries: List<BleProtocolRecorder.Entry> = emptyList(),
    /** Debug 专用：清空抓包 */
    onClearBleCapture: () -> Unit = {},
    /** Debug 专用：导出抓包文件并返回可分享的 Intent（返回 null 表示无数据或失败） */
    onShareBleCapture: suspend () -> Intent? = { null },
    /** Debug 专用：抓包 JSON Lines 文本 */
    onExportBleCaptureJson: () -> String = { "" },
    /** Debug 专用：抓包人类可读文本 */
    onExportBleCaptureText: () -> String = { "" },
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showWebdavDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showBleCaptureDialog by remember { mutableStateOf(false) }
    var scalePendingRemoval by remember { mutableStateOf<RememberedScale?>(null) }
    var showModelDialog by remember { mutableStateOf(false) }
    var memberToDelete by remember { mutableStateOf<FamilyMember?>(null) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "我的",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "添加成员")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Member Section
            item {
                Text(
                    text = "成员管理",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (members.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(
                            "暂无成员，点击右上角图标添加",
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(members) { member ->
                    MemberCard(
                        member = member,
                        onDelete = { memberToDelete = member }
                    )
                }
            }

            // System Health Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "系统健康同步 (Health Connect)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "同步至系统健康", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                text = "将体重与体脂数据同步至系统 Health Connect",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = healthConnectEnabled,
                            onCheckedChange = onToggleHealthConnect
                        )
                    }

                    if (healthConnectEnabled) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            title = "申请健康中心读写权限",
                            icon = Icons.Default.Security,
                            onClick = onRequestHealthConnectPermissions
                        )
                    }
                }
            }

            // Xiaomi Health Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "导入小米健康 (Mi Health)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    // 说明栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(androidx.compose.ui.graphics.Color(0xFFFF6900).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = androidx.compose.ui.graphics.Color(0xFFFF6900)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "同步历史数据到小米健康",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "小米健康通过 Health Connect 读取数据。请先开启上方\"同步至系统健康\"并授权，再点击\"立即同步\"将全部历史记录一键导入小米健康。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (syncProgress != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = syncProgress,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "立即同步全部历史数据",
                        subtitle = "体重与体脂写入 Health Connect，小米健康可直接读取",
                        icon = Icons.Default.CloudSync,
                        onClick = onBatchSyncToHealthConnect,
                        isLoading = syncProgress != null
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = if (isMiHealthInstalled) "打开小米健康" else "安装小米健康",
                        subtitle = "在小米健康 > 设置 > 数据来源 中启用 Health Connect",
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        onClick = onOpenMiHealth
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "管理 Health Connect 权限",
                        icon = Icons.Default.ManageAccounts,
                        onClick = onOpenHealthConnectSettings
                    )
                }
            }

            // WebDAV Backup Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "WebDAV 云端备份",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    SettingsItem(
                        title = if (webdavUrl.isBlank()) "配置 WebDAV 服务器" else "WebDAV 已配置 (${if (webdavUrl.length > 24) webdavUrl.take(24) + "..." else webdavUrl})",
                        icon = Icons.Default.Cloud,
                        onClick = { showWebdavDialog = true }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "立即备份到 WebDAV",
                        subtitle = if (lastBackupTime > 0) "上次备份：${dateFormat.format(Date(lastBackupTime))}" else "从未备份",
                        icon = Icons.Default.CloudUpload,
                        onClick = onBackupData,
                        isLoading = isOperating
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "从 WebDAV 恢复数据",
                        icon = Icons.Default.CloudDownload,
                        onClick = onRestoreData,
                        isLoading = isOperating
                    )
                }
            }

            // Settings Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "应用设置",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    // ── 已记住的体脂秤列表（支持同时记住多台：点击切换当前使用设备，右侧按钮移除） ──
                    if (rememberedScales.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "已记住的体脂秤 (${rememberedScales.size})",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "踩任意一台即可自动连接",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        rememberedScales.forEach { scale ->
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                            RememberedScaleItem(
                                scale = scale,
                                isActive = sameMacAddress(scale.mac, pairedMac),
                                onActivate = { onSelectScale(scale.mac) },
                                onRemove = { scalePendingRemoval = scale }
                            )
                        }
                        // 分隔「已记住列表」与下方「添加新的体脂秤」入口；
                        // 必须放在分支内，否则列表为空时卡片顶部会出现一条多余的孤立分隔线
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                    SettingsItem(
                        title = if (rememberedScales.isEmpty()) "绑定体脂秤" else "添加新的体脂秤",
                        subtitle = if (rememberedScales.isEmpty()) {
                            "暂未绑定，点击手动搜索并连接"
                        } else {
                            "支持同时记住多台体脂秤（当前 ${rememberedScales.size} 台），随时切换当前使用设备"
                        },
                        icon = Icons.Default.Bluetooth,
                        onClick = onNavigateToPairing
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "体脂秤型号与品牌",
                        subtitle = "${selectedScaleModel.displayName} · 点击切换型号偏好",
                        icon = Icons.Default.Tune,
                        onClick = { showModelDialog = true }
                    )
                    // ── Debug 专用：运行日志查看器入口 ──
                    // isLogViewerSupported 来自 BuildConfig，Release 构建恒为 false。
                    // 分隔线与入口一起放进 if 内，避免 Release 下出现两条相邻分隔线。
                    if (isLogViewerSupported) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            title = "查看运行日志",
                            subtitle = if (logEntries.isEmpty()) "暂无日志" else "共 ${logEntries.size} 条记录",
                            icon = Icons.AutoMirrored.Filled.Article,
                            onClick = { showLogDialog = true }
                        )
                    }
                    // ── Debug 专用：蓝牙协议完整抓包入口 ──
                    // isBleCaptureSupported 来自 BuildConfig，Release 构建恒为 false，此区块整体不渲染。
                    if (isBleCaptureSupported) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        SettingsItem(
                            title = "蓝牙协议抓包 (Debug)",
                            subtitle = if (bleCaptureEntries.isEmpty()) {
                                "暂无报文 · 连接体脂秤并称重后自动记录完整协议包"
                            } else {
                                "已捕获 ${bleCaptureEntries.size} 条报文 · 点击查看与导出"
                            },
                            icon = Icons.Default.BluetoothSearching,
                            onClick = { showBleCaptureDialog = true }
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    SettingsItem(
                        title = "关于软件",
                        subtitle = "版本 1.4.1",
                        icon = Icons.Default.Info,
                        onClick = { }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    // 两个弹窗都再叠加一次 BuildConfig 开关：即使状态被意外置位，Release 也不会渲染出来。
    if (isLogViewerSupported && showLogDialog) {
        LogViewerDialog(
            entries = logEntries,
            onDismiss = { showLogDialog = false },
            onClear = onClearLogs,
            onShare = onShareLogs
        )
    }

    if (isBleCaptureSupported && showBleCaptureDialog) {
        BleProtocolCaptureDialog(
            entries = bleCaptureEntries,
            onDismiss = { showBleCaptureDialog = false },
            onClear = onClearBleCapture,
            onShareFile = onShareBleCapture,
            onExportJson = onExportBleCaptureJson,
            onExportText = onExportBleCaptureText
        )
    }

    if (showAddDialog) {
        AddMemberDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, sex, height, birth, weight ->
                onAddMember(name, sex, height, birth, weight)
                showAddDialog = false
            }
        )
    }

    if (memberToDelete != null) {
        val target = memberToDelete!!
        AlertDialog(
            onDismissRequest = { memberToDelete = null },
            title = { Text("确认删除成员", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定要删除成员「${target.name}」吗？\n删除后该成员的历史测量记录将保留，但不再关联到此成员。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteMember(target)
                        memberToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("删除", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    val scalePendingRemovalTarget = scalePendingRemoval
    if (scalePendingRemovalTarget != null) {
        AlertDialog(
            onDismissRequest = { scalePendingRemoval = null },
            title = { Text("移除这台体脂秤？", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "确定要从已记住的列表中移除「${scalePendingRemovalTarget.name}」吗？\n\n" +
                        "当前共记住 ${rememberedScales.size} 台体脂秤，移除后其余设备仍可自动连接；" +
                        "若全部移除，App 将暂停自动连接，直到您重新手动搜索并绑定设备。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val mac = scalePendingRemovalTarget.mac
                        scalePendingRemoval = null
                        onRemoveScale(mac)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("移除", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { scalePendingRemoval = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (showWebdavDialog) {
        WebDavConfigDialog(
            initialUrl = webdavUrl,
            initialUsername = webdavUsername,
            initialPassword = webdavPassword,
            onDismiss = { showWebdavDialog = false },
            onSave = { url, user, pass ->
                onSaveWebdavConfig(url, user, pass)
                showWebdavDialog = false
            },
            onTest = { url, user, pass ->
                onTestWebdavConnection(url, user, pass)
            },
            isOperating = isOperating
        )
    }

    if (showModelDialog) {
        SelectScaleModelDialog(
            currentModel = selectedScaleModel,
            onModelSelected = onSelectScaleModel,
            onDismiss = { showModelDialog = false }
        )
    }
}

/**
 * 「已记住的体脂秤」列表项。
 *
 * 整行可点击 → 切换为当前使用设备；右侧删除按钮 → 从已记住列表中移除该台。
 * 当前使用中的设备以实心图标 + 「使用中」标签高亮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RememberedScaleItem(
    scale: RememberedScale,
    isActive: Boolean,
    onActivate: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        onClick = onActivate,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isActive) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = scale.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "使用中",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    text = listOfNotNull(
                        scale.detail.takeIf { it.isNotBlank() },
                        scale.mac
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "移除 ${scale.name}",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun SettingsItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    onClick: () -> Unit,
    isLoading: Boolean = false
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = androidx.compose.ui.graphics.Color.Transparent,
        enabled = !isLoading
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerDialog(
    entries: List<AppLogger.LogEntry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    // 每次有新日志时自动滚动到最底部
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("运行日志", fontWeight = FontWeight.Bold)
                            Text(
                                "${entries.size} 条记录",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val text = AppLogger.exportText()
                            clipboardManager.setText(AnnotatedString(text))
                            Toast.makeText(context, "已复制全部日志", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制")
                        }
                        IconButton(onClick = {
                            val text = AppLogger.exportText()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                                putExtra(Intent.EXTRA_SUBJECT, "AppLog")
                            }
                            context.startActivity(Intent.createChooser(intent, "分享日志"))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "分享")
                        }
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.Delete, contentDescription = "清空")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { innerPadding ->
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.Article,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "暂无日志",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(MaterialTheme.colorScheme.surface),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(entries, key = { "${it.timestamp}_${it.message.hashCode()}" }) { entry ->
                        LogEntryRow(entry = entry, timeFmt = timeFmt)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(
    entry: AppLogger.LogEntry,
    timeFmt: SimpleDateFormat
) {
    val (levelColor, levelBg) = when (entry.level) {
        AppLogger.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant to Color.Transparent
        AppLogger.Level.INFO  -> Color(0xFF2196F3) to Color(0xFF2196F3).copy(alpha = 0.07f)
        AppLogger.Level.WARN  -> Color(0xFFFF9800) to Color(0xFFFF9800).copy(alpha = 0.07f)
        AppLogger.Level.ERROR -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(levelBg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeFmt.format(Date(entry.timestamp)),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 1.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = entry.level.name.take(1),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = levelColor,
            modifier = Modifier.padding(top = 1.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "[${entry.tag}] ${entry.message}",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = if (entry.level == AppLogger.Level.DEBUG)
                MaterialTheme.colorScheme.onSurfaceVariant
            else
                MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 蓝牙协议完整抓包查看与导出弹窗（**Debug 专用**）。
 *
 * 三种导出方式覆盖不同排查场景：
 * - **导出文件**：写入 cache 并通过 FileProvider 分享 `.jsonl`，适合把完整抓包（含每条报文原始字节）带到电脑上离线分析；
 * - **复制 JSONL**：每行一个 JSON 对象，可直接粘进脚本处理；
 * - **复制文本**：人类可读的对齐格式，含完整十六进制载荷与 ASCII 对照，适合直接贴进 Issue。
 *
 * 全部导出内容均来自 [BleProtocolRecorder]，与 AppLogger 的日志缓冲相互独立，不会被日志刷掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BleProtocolCaptureDialog(
    entries: List<BleProtocolRecorder.Entry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onShareFile: suspend () -> Intent?,
    onExportJson: () -> String,
    onExportText: () -> String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var isExporting by remember { mutableStateOf(false) }

    // 新报文到达时自动滚动到最底部，等价于查看实时协议流
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp),
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("蓝牙协议抓包", fontWeight = FontWeight.Bold)
                            Text(
                                "${entries.size} 条报文 · Debug 专用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        // 1) 导出为文件并分享（推荐：完整、可离线分析）
                        IconButton(
                            enabled = entries.isNotEmpty() && !isExporting,
                            onClick = {
                                scope.launch {
                                    isExporting = true
                                    val intent = onShareFile()
                                    isExporting = false
                                    // 失败原因由 ViewModel 通过 statusMessage 统一提示，此处仅在成功时拉起分享面板
                                    if (intent != null) {
                                        context.startActivity(Intent.createChooser(intent, "分享蓝牙协议抓包"))
                                    }
                                }
                            }
                        ) {
                            if (isExporting) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Share, contentDescription = "导出文件并分享")
                            }
                        }
                        // 2) 复制 JSON Lines
                        IconButton(
                            enabled = entries.isNotEmpty(),
                            onClick = {
                                clipboardManager.setText(AnnotatedString(onExportJson()))
                                Toast.makeText(context, "已复制抓包 (JSON Lines)", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.DataObject, contentDescription = "复制 JSONL")
                        }
                        // 3) 复制人类可读文本
                        IconButton(
                            enabled = entries.isNotEmpty(),
                            onClick = {
                                clipboardManager.setText(AnnotatedString(onExportText()))
                                Toast.makeText(context, "已复制抓包 (文本)", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制文本")
                        }
                        IconButton(
                            enabled = entries.isNotEmpty(),
                            onClick = onClear
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "清空")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { innerPadding ->
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Icon(
                            Icons.Default.BluetoothSearching,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "暂无蓝牙协议报文",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "返回「测量」页，点亮体脂秤并完成一次称重，这里会自动记录扫描广播、" +
                                "GATT 连接、特征订阅、握手包与体重/阻抗通知的完整原始字节。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(MaterialTheme.colorScheme.surface),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 顶部固定说明当前抓包的方向图例与容量
                    item(key = "capture_header") {
                        Text(
                            text = "SCAN=广播  IN=秤→手机  OUT=手机→秤  EVENT=连接/订阅状态机   " +
                                "容量 ${BleProtocolRecorder.MAX_ENTRIES} 条" +
                                if (BleProtocolRecorder.dropped() > 0) "（已滚出 ${BleProtocolRecorder.dropped()} 条）" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                    itemsIndexed(entries) { index, entry ->
                        BleCaptureEntryRow(index = index, entry = entry)
                    }
                }
            }
        }
    }
}

/** 单条抓包记录行：方向着色 + 事件名 + 完整十六进制载荷。 */
@Composable
private fun BleCaptureEntryRow(index: Int, entry: BleProtocolRecorder.Entry) {
    val dirColor = when (entry.direction) {
        "IN" -> Color(0xFF2196F3)     // 秤 → 手机
        "OUT" -> Color(0xFFFF9800)    // 手机 → 秤
        "SCAN" -> Color(0xFF9C27B0)   // 广播
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (entry.direction == "IN" || entry.direction == "OUT") dirColor.copy(alpha = 0.06f)
                else Color.Transparent
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = index.toString().padStart(4, '0'),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = entry.direction,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = dirColor
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = entry.event,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (entry.payloadHex.isEmpty()) {
                    "+${entry.offsetMs}ms"
                } else {
                    "${entry.payloadHex.split(' ').size}B +${entry.offsetMs}ms"
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline
            )
        }

        if (entry.address.isNotEmpty()) {
            Text(
                text = buildString {
                    append(entry.address)
                    if (entry.name.isNotEmpty()) append("  ").append(entry.name)
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 26.dp)
            )
        }

        if (entry.payloadHex.isNotEmpty()) {
            Text(
                text = entry.payloadHex,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 26.dp)
            )
            Text(
                text = BleProtocolRecorder.toAscii(BleProtocolRecorder.hexToBytes(entry.payloadHex)),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 26.dp)
            )
        }
    }
}

@Composable
fun WebDavConfigDialog(
    initialUrl: String,
    initialUsername: String,
    initialPassword: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onTest: (String, String, String) -> Unit,
    isOperating: Boolean
) {
    var url by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 WebDAV 云同步") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "支持坚果云、Nextcloud、OwnCloud、NAS 等任意标准 WebDAV 服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务器 URL (如 https://dav.jianguoyun.com/dav/)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("账号 / 用户名") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码 / 应用授权码") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(url, username, password) },
                shape = MaterialTheme.shapes.medium
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onTest(url, username, password) },
                    enabled = !isOperating,
                    shape = MaterialTheme.shapes.medium
                ) {
                    if (isOperating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("测试连接")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}

@Composable
fun MemberCard(member: FamilyMember, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = member.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = "${if (member.sex == Sex.MALE) "男" else "女"} · ${member.heightCm.toInt()}cm",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDelete,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun AddMemberDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Sex, Double, Long, Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf(Sex.MALE) }
    var height by remember { mutableStateOf("170") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加新成员") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )

                Text("性别", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FilterChip(
                        selected = sex == Sex.MALE,
                        onClick = { sex = Sex.MALE },
                        label = { Text("男生") },
                        leadingIcon = if (sex == Sex.MALE) { { Icon(Icons.Default.Check, null) } } else null
                    )
                    FilterChip(
                        selected = sex == Sex.FEMALE,
                        onClick = { sex = Sex.FEMALE },
                        label = { Text("女生") },
                        leadingIcon = if (sex == Sex.FEMALE) { { Icon(Icons.Default.Check, null) } } else null
                    )
                }

                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it },
                    label = { Text("身高 (cm)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = if (name.isBlank()) "新成员" else name.trim()
                    onConfirm(
                        finalName,
                        sex,
                        height.toDoubleOrNull() ?: 170.0,
                        System.currentTimeMillis() - (1000L * 60 * 60 * 24 * 365 * 25),
                        0.0
                    )
                    onDismiss()
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    modifier: Modifier = Modifier,
    onNavigateToPairing: () -> Unit = {}
) {
    val context = LocalContext.current
    val members by viewModel.members.collectAsState()
    val healthConnectEnabled by viewModel.healthConnectEnabled.collectAsState()
    val webdavUrl by viewModel.webdavUrl.collectAsState()
    val webdavUsername by viewModel.webdavUsername.collectAsState()
    val webdavPassword by viewModel.webdavPassword.collectAsState()
    val lastBackupTime by viewModel.lastBackupTime.collectAsState()
    val isOperating by viewModel.isOperating.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val pairedMac by viewModel.pairedMac.collectAsState()
    val pairedDeviceName by viewModel.pairedDeviceName.collectAsState()
    val rememberedScales by viewModel.rememberedScales.collectAsState()
    val selectedScaleModel by viewModel.selectedScaleModel.collectAsState()
    // Debug 专用：蓝牙协议抓包（Release 构建下 isBleCaptureSupported 恒为 false，入口不渲染）
    val bleCaptureEntries by viewModel.bleCaptureEntries.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.isNotEmpty()) {
            Toast.makeText(context, "已获得系统健康权限！", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "未获得健康读写权限", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearStatusMessage()
        }
    }

    ProfileContent(
        members = members,
        onAddMember = { name, sex, height, birth, weight -> viewModel.addMember(name, sex, height, birth, weight) },
        onDeleteMember = { viewModel.deleteMember(it) },
        onNavigateToPairing = onNavigateToPairing,
        pairedMac = pairedMac,
        pairedDeviceName = pairedDeviceName,
        rememberedScales = rememberedScales,
        onSelectScale = { viewModel.setActiveScale(it) },
        onRemoveScale = { viewModel.removeScale(it) },
        selectedScaleModel = selectedScaleModel,
        onSelectScaleModel = { viewModel.selectScaleModel(it) },
        healthConnectEnabled = healthConnectEnabled,
        onToggleHealthConnect = { enabled ->
            viewModel.setHealthConnectEnabled(enabled)
            if (enabled && viewModel.isHealthConnectAvailable()) {
                permissionLauncher.launch(
                    setOf(
                        androidx.health.connect.client.permission.HealthPermission.getWritePermission(androidx.health.connect.client.records.WeightRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getWritePermission(androidx.health.connect.client.records.BodyFatRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.WeightRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.BodyFatRecord::class)
                    )
                )
            }
        },
        onRequestHealthConnectPermissions = {
            if (viewModel.isHealthConnectAvailable()) {
                permissionLauncher.launch(
                    setOf(
                        androidx.health.connect.client.permission.HealthPermission.getWritePermission(androidx.health.connect.client.records.WeightRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getWritePermission(androidx.health.connect.client.records.BodyFatRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.WeightRecord::class),
                        androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.BodyFatRecord::class)
                    )
                )
            } else {
                Toast.makeText(context, "当前设备不支持或未安装 Health Connect", Toast.LENGTH_SHORT).show()
            }
        },
        onBatchSyncToHealthConnect = { viewModel.batchSyncToHealthConnect() },
        onOpenMiHealth = { viewModel.openMiHealth() },
        onOpenHealthConnectSettings = { viewModel.openHealthConnectSettings() },
        syncProgress = syncProgress,
        isMiHealthInstalled = viewModel.isMiHealthInstalled(),
        webdavUrl = webdavUrl,
        webdavUsername = webdavUsername,
        webdavPassword = webdavPassword,
        lastBackupTime = lastBackupTime,
        onSaveWebdavConfig = { url, user, pass -> viewModel.saveWebdavConfig(url, user, pass) },
        onTestWebdavConnection = { url, user, pass -> viewModel.testWebdavConnection(url, user, pass) },
        onBackupData = { viewModel.backupData() },
        onRestoreData = { viewModel.restoreData() },
        isOperating = isOperating,
        logEntries = logEntries,
        onClearLogs = { viewModel.clearLogs() },
        onShareLogs = {
            val text = viewModel.exportLogsText()
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "AppLog")
            }
            context.startActivity(android.content.Intent.createChooser(intent, "分享日志"))
        },
        isLogViewerSupported = viewModel.isLogViewerSupported,
        isBleCaptureSupported = viewModel.isBleCaptureSupported,
        bleCaptureEntries = bleCaptureEntries,
        onClearBleCapture = { viewModel.clearBleCapture() },
        onShareBleCapture = { viewModel.createBleCaptureShareIntent(context) },
        onExportBleCaptureJson = { viewModel.exportBleCaptureJson() },
        onExportBleCaptureText = { viewModel.exportBleCaptureText() },
        modifier = modifier
    )
}

