package com.example.dianzicheng.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.health.connect.client.PermissionController
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.data.ble.BleProtocolRecorder
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.domain.BiaModel
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.RememberedScale
import com.example.dianzicheng.domain.ScaleModel
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.domain.sameMacAddress
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── 应用自有语义色（MIUIX_STYLE §2）：主题 token 无法表达的品牌/调试语义色，收敛为命名常量 ──

/** 小米健康品牌橙（应用自有语义色：品牌识别色，主题 token 无法表达） */
private val MiHealthBrandColor = Color(0xFFFF6900)

/** 运行日志 INFO 级别色（应用自有语义色：日志级别语义，无对应主题 token） */
private val LogInfoColor = Color(0xFF2196F3)

/** 运行日志 WARN 级别色（应用自有语义色：非错误的警示状态，Miuix 无 warning token，不借用 error） */
private val LogWarnColor = Color(0xFFFF9800)

/** 抓包 SCAN 广播方向色（应用自有语义色：调试工具方向语义） */
private val CaptureScanColor = Color(0xFF9C27B0)

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
    /** 当前选中的电阻抗（BIA）体成分估算模型 */
    selectedBiaModel: BiaModel = BiaModel.DEFAULT,
    /** 切换电阻抗估算模型（对下一次称重生效） */
    onSelectBiaModel: (BiaModel) -> Unit = {},
    healthConnectEnabled: Boolean,
    onToggleHealthConnect: (Boolean) -> Unit,
    onRequestHealthConnectPermissions: () -> Unit,
    onBatchSyncToHealthConnect: () -> Unit,
    onOpenMiHealth: () -> Unit,
    onOpenHealthConnectSettings: () -> Unit,
    syncProgress: String?,
    /** 历史记录重算进度描述（切换体脂估算模型时触发），null 表示空闲 */
    recalcProgress: String? = null,
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
    var showBiaModelDialog by remember { mutableStateOf(false) }
    var memberToDelete by remember { mutableStateOf<FamilyMember?>(null) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "我的",
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = MiuixIcons.Add,
                            contentDescription = "添加成员"
                        )
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
            // HyperOS 分组版式（SettingsPage/DialogSection 组合证据）：卡外水平 12dp；
            // SmallTitle 自带 28dp 标题缩进（SmallTitleDefaults.InsideMargin），组间由 spacedBy + 标题自带留白形成节奏
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 成员管理：SmallTitle（卡外）+ 成员卡 ──
            item {
                SmallTitle(text = "成员管理")
            }

            if (members.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        Text(
                            "暂无成员，点击右上角图标添加",
                            modifier = Modifier.padding(24.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                }
            } else {
                items(members) { member ->
                    // 成员卡自带 Card；12dp 水平留白与各分组卡片对齐（Example 卡外 12dp 模式）
                    Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                        MemberCard(
                            member = member,
                            onDelete = { memberToDelete = member }
                        )
                    }
                }
            }

            // ── 系统健康同步（Health Connect）：SmallTitle + 一张 Card（相关 Preference 行同卡）──
            item {
                SmallTitle(text = "系统健康同步 (Health Connect)")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        checked = healthConnectEnabled,
                        onCheckedChange = onToggleHealthConnect,
                        title = "同步至系统健康",
                        summary = "将体重与体脂数据同步至系统 Health Connect",
                        startAction = {
                            SettingsIconTile(icon = MiuixIcons.FavoritesFill)
                        }
                    )

                    if (healthConnectEnabled) {
                        SettingsItem(
                            title = "申请健康中心读写权限",
                            icon = MiuixIcons.Lock,
                            onClick = onRequestHealthConnectPermissions
                        )
                    }
                }
            }

            // ── 导入小米健康 (Mi Health)：SmallTitle + 一张 Card ──
            item {
                SmallTitle(text = "导入小米健康 (Mi Health)")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    // 说明栏（自定义行：组件无对应 Preference 形态）。
                    // 行内几何对齐 BasicComponent：16dp 边距（BasicComponentDefaults.InsideMargin）、8dp 图文间距
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        SettingsIconTile(
                            icon = MiuixIcons.Favorites,
                            // 品牌色淡底：真实透明度需求（渐淡底色），非层次伪造
                            containerColor = MiHealthBrandColor.copy(alpha = 0.15f),
                            contentColor = MiHealthBrandColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "同步历史数据到小米健康",
                                style = MiuixTheme.textStyles.body1
                            )
                            Text(
                                text = "小米健康通过 Health Connect 读取数据。请先开启上方\"同步至系统健康\"并授权，再点击\"立即同步\"将全部历史记录一键导入小米健康。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                            if (syncProgress != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = syncProgress,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    SettingsItem(
                        title = "立即同步全部历史数据",
                        subtitle = "体重与体脂写入 Health Connect，小米健康可直接读取",
                        icon = MiuixIcons.Update,
                        onClick = onBatchSyncToHealthConnect,
                        isLoading = syncProgress != null
                    )
                    SettingsItem(
                        title = if (isMiHealthInstalled) "打开小米健康" else "安装小米健康",
                        subtitle = "在小米健康 > 设置 > 数据来源 中启用 Health Connect",
                        icon = MiuixIcons.Link,
                        onClick = onOpenMiHealth
                    )
                    SettingsItem(
                        title = "管理 Health Connect 权限",
                        icon = MiuixIcons.Settings,
                        onClick = onOpenHealthConnectSettings
                    )
                }
            }

            // ── WebDAV 云端备份：SmallTitle + 一张 Card ──
            item {
                SmallTitle(text = "WebDAV 云端备份")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    SettingsItem(
                        title = if (webdavUrl.isBlank()) "配置 WebDAV 服务器" else "WebDAV 已配置 (${if (webdavUrl.length > 24) webdavUrl.take(24) + "..." else webdavUrl})",
                        icon = MiuixIcons.CloudFill,
                        onClick = { showWebdavDialog = true }
                    )
                    SettingsItem(
                        title = "立即备份到 WebDAV",
                        subtitle = if (lastBackupTime > 0) "上次备份：${dateFormat.format(Date(lastBackupTime))}" else "从未备份",
                        icon = MiuixIcons.UploadCloud,
                        onClick = onBackupData,
                        isLoading = isOperating
                    )
                    SettingsItem(
                        title = "从 WebDAV 恢复数据",
                        icon = MiuixIcons.Download,
                        onClick = onRestoreData,
                        isLoading = isOperating
                    )
                }
            }

            // ── 应用设置：SmallTitle + 一张 Card（相关 Preference 行同卡）──
            item {
                SmallTitle(text = "应用设置")
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    // ── 已记住的体脂秤列表（支持同时记住多台：点击切换当前使用设备，右侧按钮移除） ──
                    if (rememberedScales.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 卡内子分组标签沿用 SmallTitle 的 subtitle 字阶（自带层级，不再手写字号/字重）
                            Text(
                                text = "已记住的体脂秤 (${rememberedScales.size})",
                                style = MiuixTheme.textStyles.subtitle,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "踩任意一台即可自动连接",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.outline
                            )
                        }
                        rememberedScales.forEach { scale ->
                            RememberedScaleItem(
                                scale = scale,
                                isActive = sameMacAddress(scale.mac, pairedMac),
                                onActivate = { onSelectScale(scale.mac) },
                                onRemove = { scalePendingRemoval = scale }
                            )
                        }
                        // 分隔「已记住列表」与下方「添加新的体脂秤」入口（分组边界，产品约定保留 Divider）；
                        // 必须放在分支内，否则列表为空时卡片顶部会出现一条多余的孤立分隔线
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    SettingsItem(
                        title = if (rememberedScales.isEmpty()) "绑定体脂秤" else "添加新的体脂秤",
                        subtitle = if (rememberedScales.isEmpty()) {
                            "暂未绑定，点击手动搜索并连接"
                        } else {
                            "支持同时记住多台体脂秤（当前 ${rememberedScales.size} 台），随时切换当前使用设备"
                        },
                        icon = MiuixIcons.SearchDevice,
                        onClick = onNavigateToPairing
                    )
                    SettingsItem(
                        title = "体脂秤型号与品牌",
                        subtitle = "${selectedScaleModel.displayName} · 点击切换型号偏好",
                        icon = MiuixIcons.Tune,
                        onClick = { showModelDialog = true }
                    )
                    // ── 电阻抗估算模型切换入口 ──
                    SettingsItem(
                        title = "体脂估算模型",
                        subtitle = if (recalcProgress != null) {
                            recalcProgress
                        } else {
                            "${selectedBiaModel.displayName} · 切换后自动重算历史记录"
                        },
                        icon = MiuixIcons.Tasks,
                        onClick = { showBiaModelDialog = true },
                        isLoading = recalcProgress != null
                    )
                    // ── Debug 专用：运行日志查看器入口 ──
                    // isLogViewerSupported 来自 BuildConfig，Release 构建恒为 false。
                    if (isLogViewerSupported) {
                        SettingsItem(
                            title = "查看运行日志",
                            subtitle = if (logEntries.isEmpty()) "暂无日志" else "共 ${logEntries.size} 条记录",
                            icon = MiuixIcons.Notes,
                            onClick = { showLogDialog = true }
                        )
                    }
                    // ── Debug 专用：蓝牙协议完整抓包入口 ──
                    // isBleCaptureSupported 来自 BuildConfig，Release 构建恒为 false，此区块整体不渲染。
                    if (isBleCaptureSupported) {
                        SettingsItem(
                            title = "蓝牙协议抓包 (Debug)",
                            subtitle = if (bleCaptureEntries.isEmpty()) {
                                "暂无报文 · 连接体脂秤并称重后自动记录完整协议包"
                            } else {
                                "已捕获 ${bleCaptureEntries.size} 条报文 · 点击查看与导出"
                            },
                            icon = MiuixIcons.Scan,
                            onClick = { showBleCaptureDialog = true }
                        )
                    }
                    SettingsItem(
                        title = "关于软件",
                        subtitle = "版本 1.4.1",
                        icon = MiuixIcons.Info,
                        onClick = { }
                    )
                }
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
        OverlayDialog(
            show = true,
            title = "确认删除成员",
            summary = "确定要删除成员「${target.name}」吗？\n删除后该成员的历史测量记录将保留，但不再关联到此成员。",
            onDismissRequest = { memberToDelete = null }
        ) {
            // 弹窗操作行（DialogSection 按钮行模式）：取消=次操作、删除=破坏性（error 语义）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = "取消",
                    onClick = { memberToDelete = null },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = "删除",
                    onClick = {
                        onDeleteMember(target)
                        memberToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        textColor = MiuixTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    val scalePendingRemovalTarget = scalePendingRemoval
    if (scalePendingRemovalTarget != null) {
        OverlayDialog(
            show = true,
            title = "移除这台体脂秤？",
            summary = "确定要从已记住的列表中移除「${scalePendingRemovalTarget.name}」吗？\n\n" +
                "当前共记住 ${rememberedScales.size} 台体脂秤，移除后其余设备仍可自动连接；" +
                "若全部移除，App 将暂停自动连接，直到您重新手动搜索并绑定设备。",
            onDismissRequest = { scalePendingRemoval = null }
        ) {
            // 弹窗操作行（DialogSection 按钮行模式）：取消=次操作、移除=破坏性（error 语义）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = "取消",
                    onClick = { scalePendingRemoval = null },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = "移除",
                    onClick = {
                        val mac = scalePendingRemovalTarget.mac
                        scalePendingRemoval = null
                        onRemoveScale(mac)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        textColor = MiuixTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
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

    if (showBiaModelDialog) {
        SelectBiaModelDialog(
            currentModel = selectedBiaModel,
            onModelSelected = onSelectBiaModel,
            onDismiss = { showBiaModelDialog = false }
        )
    }
}

/**
 * 「已记住的体脂秤」列表项。
 *
 * 整行可点击 → 切换为当前使用设备；右侧删除按钮 → 从已记住列表中移除该台。
 * 当前使用中的设备以实心图标 + 「使用中」标签高亮。
 */
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
        // 自定义行几何对齐 BasicComponent：16dp 边距、8dp 图文间距
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsIconTile(
                icon = MiuixIcons.SearchDevice,
                containerColor = if (isActive) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.primaryContainer
                },
                contentColor = if (isActive) {
                    MiuixTheme.colorScheme.onPrimary
                } else {
                    MiuixTheme.colorScheme.onPrimaryContainer
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = scale.name,
                        style = MiuixTheme.textStyles.body1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        // 「使用中」标签：自定义徽标 chip（Miuix Badge 是导航角标语义，不适合状态标签）；
                        // 半径按小件/徽标档 8dp 靠拢，色对沿用 primaryContainer/onPrimaryContainer
                        Surface(
                            shape = RoundedCornerShape(8.dp),
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
                Text(
                    text = listOfNotNull(
                        scale.detail.takeIf { it.isNotBlank() },
                        scale.mac
                    ).joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = "移除 ${scale.name}",
                    modifier = Modifier.size(18.dp),
                    // 行尾操作图标用动作角色 token
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                )
            }
        }
    }
}

/**
 * 设置页图标圆底（自持件）：40dp 圆底 + 20dp 图标/加载环。
 * 色对走主题 token（默认 primaryContainer/onPrimaryContainer）；加载环经 ProgressIndicatorDefaults 取色。
 */
@Composable
private fun SettingsIconTile(
    icon: ImageVector,
    isLoading: Boolean = false,
    containerColor: Color = MiuixTheme.colorScheme.primaryContainer,
    contentColor: Color = MiuixTheme.colorScheme.onPrimaryContainer
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = contentColor,
                    backgroundColor = Color.Transparent
                ),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = contentColor
            )
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
    ArrowPreference(
        title = title,
        summary = subtitle,
        startAction = {
            SettingsIconTile(icon = icon, isLoading = isLoading)
        },
        onClick = onClick,
        enabled = !isLoading
    )
}

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

    OverlayDialog(
        show = true,
        title = "运行日志",
        summary = "${entries.size} 条记录",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = MiuixIcons.Basic.Close,
                        contentDescription = "关闭"
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val text = AppLogger.exportText()
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, "已复制全部日志", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        imageVector = MiuixIcons.Copy,
                        contentDescription = "复制"
                    )
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
                    Icon(
                        imageVector = MiuixIcons.Share,
                        contentDescription = "分享"
                    )
                }
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = "清空",
                        // 清空=破坏性操作（语义化着色）
                        tint = MiuixTheme.colorScheme.error
                    )
                }
            }
            if (entries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = MiuixIcons.Notes,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MiuixTheme.colorScheme.dividerLine
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "暂无日志",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MiuixTheme.colorScheme.surface),
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
        AppLogger.Level.DEBUG -> MiuixTheme.colorScheme.onSurfaceSecondary to Color.Transparent
        AppLogger.Level.INFO  -> LogInfoColor to LogInfoColor.copy(alpha = 0.07f) // 级别淡底：真实透明度需求
        AppLogger.Level.WARN  -> LogWarnColor to LogWarnColor.copy(alpha = 0.07f) // 级别淡底：真实透明度需求
        AppLogger.Level.ERROR -> MiuixTheme.colorScheme.error to MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.15f) // 级别淡底：真实透明度需求
    }

    // 字阶归位：行内容 footnote1、元信息 footnote2（替代裸 fontSize 10/11sp）；
    // 等宽字体为日志对齐排版需要，保留为日志排版约定
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(levelBg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeFmt.format(Date(entry.timestamp)),
            style = MiuixTheme.textStyles.footnote2,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 1.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = entry.level.name.take(1),
            style = MiuixTheme.textStyles.footnote2,
            fontFamily = FontFamily.Monospace,
            color = levelColor,
            modifier = Modifier.padding(top = 1.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "[${entry.tag}] ${entry.message}",
            style = MiuixTheme.textStyles.footnote1,
            fontFamily = FontFamily.Monospace,
            color = if (entry.level == AppLogger.Level.DEBUG)
                MiuixTheme.colorScheme.onSurfaceSecondary
            else
                MiuixTheme.colorScheme.onSurface,
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

    OverlayDialog(
        show = true,
        title = "蓝牙协议抓包",
        summary = "${entries.size} 条报文 · Debug 专用",
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = MiuixIcons.Basic.Close,
                        contentDescription = "关闭"
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
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
                        Icon(
                            imageVector = MiuixIcons.Share,
                            contentDescription = "导出文件并分享"
                        )
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
                    Icon(
                        imageVector = MiuixIcons.File,
                        contentDescription = "复制 JSONL"
                    )
                }
                // 3) 复制人类可读文本
                IconButton(
                    enabled = entries.isNotEmpty(),
                    onClick = {
                        clipboardManager.setText(AnnotatedString(onExportText()))
                        Toast.makeText(context, "已复制抓包 (文本)", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = MiuixIcons.Copy,
                        contentDescription = "复制文本"
                    )
                }
                IconButton(
                    enabled = entries.isNotEmpty(),
                    onClick = onClear
                ) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = "清空",
                        // 清空=破坏性操作（语义化着色）
                        tint = MiuixTheme.colorScheme.error
                    )
                }
            }
            if (entries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Scan,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MiuixTheme.colorScheme.dividerLine
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "暂无蓝牙协议报文",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "返回「测量」页，点亮体脂秤并完成一次称重，这里会自动记录扫描广播、" +
                                "GATT 连接、特征订阅、握手包与体重/阻抗通知的完整原始字节。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MiuixTheme.colorScheme.surface),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // 顶部固定说明当前抓包的方向图例与容量
                    item(key = "capture_header") {
                        Text(
                            text = "SCAN=广播  IN=秤→手机  OUT=手机→秤  EVENT=连接/订阅状态机   " +
                                "容量 ${BleProtocolRecorder.MAX_ENTRIES} 条" +
                                if (BleProtocolRecorder.dropped() > 0) "（已滚出 ${BleProtocolRecorder.dropped()} 条）" else "",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
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
        "IN" -> LogInfoColor       // 秤 → 手机
        "OUT" -> LogWarnColor      // 手机 → 秤
        "SCAN" -> CaptureScanColor // 广播
        else -> MiuixTheme.colorScheme.onSurfaceSecondary
    }

    // 字阶归位：抓包行为高密度调试数据，统一 footnote2（替代裸 fontSize 10/11sp）；
    // 等宽字体为十六进制对齐排版需要，保留为日志排版约定
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (entry.direction == "IN" || entry.direction == "OUT") dirColor.copy(alpha = 0.06f) // 方向淡底：真实透明度需求
                else Color.Transparent
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = index.toString().padStart(4, '0'),
                style = MiuixTheme.textStyles.footnote2,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = entry.direction,
                style = MiuixTheme.textStyles.footnote2,
                fontFamily = FontFamily.Monospace,
                color = dirColor
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = entry.event,
                style = MiuixTheme.textStyles.footnote2,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (entry.payloadHex.isEmpty()) {
                    "+${entry.offsetMs}ms"
                } else {
                    "${entry.payloadHex.split(' ').size}B +${entry.offsetMs}ms"
                },
                style = MiuixTheme.textStyles.footnote2,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.outline
            )
        }

        if (entry.address.isNotEmpty()) {
            Text(
                text = buildString {
                    append(entry.address)
                    if (entry.name.isNotEmpty()) append("  ").append(entry.name)
                },
                style = MiuixTheme.textStyles.footnote2,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(start = 26.dp)
            )
        }

        if (entry.payloadHex.isNotEmpty()) {
            Text(
                text = entry.payloadHex,
                style = MiuixTheme.textStyles.footnote2,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 26.dp)
            )
            Text(
                text = BleProtocolRecorder.toAscii(BleProtocolRecorder.hexToBytes(entry.payloadHex)),
                style = MiuixTheme.textStyles.footnote2,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.outline,
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

    OverlayDialog(
        show = true,
        title = "配置 WebDAV 云同步",
        summary = "支持坚果云、Nextcloud、OwnCloud、NAS 等任意标准 WebDAV 服务。",
        onDismissRequest = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = url,
                onValueChange = { url = it },
                label = "服务器 URL (如 https://dav.jianguoyun.com/dav/)",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            TextField(
                value = username,
                onValueChange = { username = it },
                label = "账号 / 用户名",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            TextField(
                value = password,
                onValueChange = { password = it },
                label = "密码 / 应用授权码",
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            // 弹窗操作行（DialogSection 按钮行模式：等宽槽位 + 20dp 间隔）：
            // 测试连接=次操作（Button 内容槽承载加载环）、取消=次操作、保存=主操作（主色）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { onTest(url, username, password) },
                    enabled = !isOperating,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isOperating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("测试连接")
                    }
                }
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
                Button(
                    onClick = { onSave(url, username, password) },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
fun MemberCard(member: FamilyMember, onDelete: () -> Unit) {
    // 卡片用组件默认形态（16dp squircle + surfaceContainer 底色），不自定 cornerRadius/底色
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MiuixIcons.Contacts,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MiuixTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                // 字阶归位：成员名是卡片主标题，title3（20sp）就近原 titleLarge 视觉层级，字重由字阶承担
                Text(
                    text = member.name,
                    style = MiuixTheme.textStyles.title3
                )
                Text(
                    text = "${if (member.sex == Sex.MALE) "男" else "女"} · ${member.heightCm.toInt()}cm",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary
                )
            }
            // 删除=破坏性语义：errorContainer/onErrorContainer 语义色对（不以 alpha 淡底伪造层次）
            IconButton(
                onClick = onDelete,
                backgroundColor = MiuixTheme.colorScheme.errorContainer
            ) {
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onErrorContainer
                )
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

    OverlayDialog(
        show = true,
        title = "添加新成员",
        onDismissRequest = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = "姓名",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // 表单分组标签：body2 字阶 + 次要文字 token（不再用按钮字阶/主色强调）
            Text(
                text = "性别",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { sex = Sex.MALE }
                ) {
                    RadioButton(
                        selected = sex == Sex.MALE,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "男生", style = MiuixTheme.textStyles.body1)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { sex = Sex.FEMALE }
                ) {
                    RadioButton(
                        selected = sex == Sex.FEMALE,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "女生", style = MiuixTheme.textStyles.body1)
                }
            }

            TextField(
                value = height,
                onValueChange = { height = it },
                label = "身高 (cm)",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // 弹窗操作行（DialogSection 按钮行模式）：取消=次操作、确定=主操作（主色）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(20.dp))
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
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("确定")
                }
            }
        }
    }
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
    val recalcProgress by viewModel.recalcProgress.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val pairedMac by viewModel.pairedMac.collectAsState()
    val pairedDeviceName by viewModel.pairedDeviceName.collectAsState()
    val rememberedScales by viewModel.rememberedScales.collectAsState()
    val selectedScaleModel by viewModel.selectedScaleModel.collectAsState()
    val selectedBiaModel by viewModel.selectedBiaModel.collectAsState()
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
        selectedBiaModel = selectedBiaModel,
        onSelectBiaModel = { viewModel.selectBiaModel(it) },
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
        recalcProgress = recalcProgress,
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
