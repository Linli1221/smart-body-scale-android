package com.example.dianzicheng.ui.sheet

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.activity.compose.rememberLauncherForActivityResult
import com.example.dianzicheng.BuildConfig
import com.example.dianzicheng.R
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.RememberedScale
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.ui.component.collectAsStateWhenVisible
import com.example.dianzicheng.ui.dialog.AddMemberDialog
import com.example.dianzicheng.ui.dialog.DeleteMemberConfirmDialog
import com.example.dianzicheng.ui.dialog.RemoveScaleConfirmDialog
import com.example.dianzicheng.ui.dialog.SelectBiaModelDialog
import com.example.dianzicheng.ui.dialog.SelectScaleModelDialog
import com.example.dianzicheng.ui.dialog.WebDavConfigDialog
import com.example.dianzicheng.ui.state.ProfileViewModel
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.FavoritesFill
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 应用自有语义色：小米健康品牌橙（仅用于品牌说明栏淡底）。 */
private val MiHealthOrange = Color(0xFFFF6900)

/** 设置弹层当前打开的弹窗（Window 层，可安全叠加在弹层之上）。 */
private enum class SettingsDialog {
    AddMember, WebDavConfig, ScaleModel, BiaModel, About
}

/**
 * 「我的 / 设置」底部弹层：成员管理、Health Connect、小米健康导入、WebDAV 备份、应用设置。
 */
@Composable
fun SettingsSheet(
    show: Boolean,
    profileViewModel: ProfileViewModel,
    onOpenPairing: () -> Unit,
    onOpenLogViewer: () -> Unit,
    onOpenCapture: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current

    // 可见才订阅（启动/运行期性能）：隐藏的设置弹层不常驻订阅 DataStore/StateFlow，
    // 也不触发 AppLogger/BleProtocolRecorder 的日志快照拷贝；离场动画用最后一次快照渲染。
    val members = collectAsStateWhenVisible(show, profileViewModel.members)
    val rememberedScales = collectAsStateWhenVisible(show, profileViewModel.rememberedScales)
    val pairedMac = collectAsStateWhenVisible(show, profileViewModel.pairedMac)
    val logEntries = collectAsStateWhenVisible(show, profileViewModel.logEntries)
    val bleCaptureEntries = collectAsStateWhenVisible(show, profileViewModel.bleCaptureEntries)
    val selectedScaleModel = collectAsStateWhenVisible(show, profileViewModel.selectedScaleModel)
    val selectedBiaModel = collectAsStateWhenVisible(show, profileViewModel.selectedBiaModel)
    val healthConnectEnabled = collectAsStateWhenVisible(show, profileViewModel.healthConnectEnabled)
    val webdavUrl = collectAsStateWhenVisible(show, profileViewModel.webdavUrl)
    val webdavUsername = collectAsStateWhenVisible(show, profileViewModel.webdavUsername)
    val webdavPassword = collectAsStateWhenVisible(show, profileViewModel.webdavPassword)
    val lastBackupTime = collectAsStateWhenVisible(show, profileViewModel.lastBackupTime)
    val isOperating = collectAsStateWhenVisible(show, profileViewModel.isOperating)
    val syncProgress = collectAsStateWhenVisible(show, profileViewModel.syncProgress)
    val recalcProgress = collectAsStateWhenVisible(show, profileViewModel.recalcProgress)

    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var memberToDelete by remember { mutableStateOf<FamilyMember?>(null) }
    var scaleToRemove by remember { mutableStateOf<RememberedScale?>(null) }

    val backupDateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // Health Connect 权限申请（与旧实现一致：开启开关即申请，结果 Toast 提示）
    val healthPermissions = remember {
        setOf(
            HealthPermission.getWritePermission(WeightRecord::class),
            HealthPermission.getWritePermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class)
        )
    }
    val hcPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        Toast.makeText(
            context,
            if (granted.isNotEmpty()) "已获得系统健康权限！" else "未获得健康读写权限",
            Toast.LENGTH_SHORT
        ).show()
    }
    val requestHealthPermissions = {
        if (profileViewModel.isHealthConnectAvailable()) {
            hcPermissionLauncher.launch(healthPermissions)
        } else {
            Toast.makeText(context, "当前设备不支持或未安装 Health Connect", Toast.LENGTH_SHORT).show()
        }
    }

    OverlayBottomSheet(
        show = show,
        title = "我的",
        endAction = {
            IconButton(onClick = { dialog = SettingsDialog.AddMember }) {
                Icon(
                    imageVector = MiuixIcons.Add,
                    contentDescription = "添加成员"
                )
            }
        },
        onDismissRequest = onDismissRequest
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            // ── 成员管理 ──────────────────────────────────────────────────
            item {
                SmallSectionTitle("成员管理")
                Card12 {
                    if (members.isEmpty()) {
                        Text(
                            text = "暂无成员，点击右上角图标添加",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                            modifier = Modifier.padding(20.dp)
                        )
                    } else {
                        members.forEach { member ->
                            MemberRow(
                                member = member,
                                onDelete = { memberToDelete = member }
                            )
                        }
                    }
                }
            }

            // ── 系统健康同步（Health Connect）────────────────────────────
            item {
                SmallSectionTitle("系统健康同步 (Health Connect)")
                Card12 {
                    SwitchPreference(
                        checked = healthConnectEnabled,
                        onCheckedChange = { enabled ->
                            profileViewModel.setHealthConnectEnabled(enabled)
                            if (enabled) requestHealthPermissions()
                        },
                        title = "同步至系统健康",
                        summary = "将体重与体脂数据同步至系统 Health Connect",
                        startAction = { SettingsIconTile(icon = MiuixIcons.FavoritesFill) }
                    )
                    if (healthConnectEnabled) {
                        SettingsItem(
                            title = "申请健康中心读写权限",
                            icon = MiuixIcons.Lock,
                            onClick = requestHealthPermissions
                        )
                    }
                }
            }

            // ── 导入小米健康（Mi Health）─────────────────────────────────
            item {
                SmallSectionTitle("导入小米健康 (Mi Health)")
                Card12 {
                    // 品牌说明栏（应用自有语义色淡底）
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        SettingsIconTile(
                            icon = MiuixIcons.Favorites,
                            containerColor = MiHealthOrange.copy(alpha = 0.15f),
                            contentColor = MiHealthOrange
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "同步历史数据到小米健康",
                                style = MiuixTheme.textStyles.body1
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "小米健康通过 Health Connect 读取数据。请先开启上方\"同步至系统健康\"并授权，" +
                                    "再点击\"立即同步\"将全部历史记录一键导入小米健康。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary
                            )
                            if (syncProgress != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = syncProgress ?: "",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    SettingsItem(
                        title = "立即同步全部历史数据",
                        subtitle = "体重与体脂写入 Health Connect，小米健康可直接读取",
                        icon = MiuixIcons.Update,
                        isLoading = syncProgress != null,
                        onClick = profileViewModel::batchSyncToHealthConnect
                    )
                    SettingsItem(
                        title = if (profileViewModel.isMiHealthInstalled()) "打开小米健康" else "安装小米健康",
                        subtitle = "在小米健康 > 设置 > 数据来源 中启用 Health Connect",
                        icon = MiuixIcons.Link,
                        onClick = profileViewModel::openMiHealth
                    )
                    SettingsItem(
                        title = "管理 Health Connect 权限",
                        icon = MiuixIcons.Settings,
                        onClick = profileViewModel::openHealthConnectSettings
                    )
                }
            }

            // ── WebDAV 云端备份 ──────────────────────────────────────────
            item {
                SmallSectionTitle("WebDAV 云端备份")
                Card12 {
                    SettingsItem(
                        title = if (webdavUrl.isBlank()) {
                            "配置 WebDAV 服务器"
                        } else {
                            "WebDAV 已配置 (${if (webdavUrl.length > 24) webdavUrl.take(24) + "..." else webdavUrl})"
                        },
                        icon = MiuixIcons.CloudFill,
                        onClick = { dialog = SettingsDialog.WebDavConfig }
                    )
                    SettingsItem(
                        title = "立即备份到 WebDAV",
                        subtitle = if (lastBackupTime > 0) {
                            "上次备份：${backupDateFormat.format(Date(lastBackupTime))}"
                        } else {
                            "从未备份"
                        },
                        icon = MiuixIcons.UploadCloud,
                        isLoading = isOperating,
                        onClick = profileViewModel::backupData
                    )
                    SettingsItem(
                        title = "从 WebDAV 恢复数据",
                        icon = MiuixIcons.Download,
                        isLoading = isOperating,
                        onClick = profileViewModel::restoreData
                    )
                }
            }

            // ── 应用设置 ─────────────────────────────────────────────────
            item {
                SmallSectionTitle("应用设置")
                Card12 {
                    if (rememberedScales.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                        ) {
                            Text(
                                text = "已记住的体脂秤 (${rememberedScales.size})",
                                style = MiuixTheme.textStyles.footnote1,
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
                            RememberedScaleRow(
                                scale = scale,
                                isActive = scale.mac == pairedMac,
                                onSelect = { profileViewModel.setActiveScale(scale.mac) },
                                onRemove = { scaleToRemove = scale }
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
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
                        onClick = onOpenPairing
                    )
                    SettingsItem(
                        title = "体脂秤型号与品牌",
                        subtitle = "${selectedScaleModel.displayName} · 点击切换型号偏好",
                        icon = MiuixIcons.Tune,
                        onClick = { dialog = SettingsDialog.ScaleModel }
                    )
                    SettingsItem(
                        title = "体脂估算模型",
                        subtitle = recalcProgress
                            ?: "${selectedBiaModel.displayName} · 切换后自动重算历史记录",
                        icon = MiuixIcons.Tasks,
                        isLoading = recalcProgress != null,
                        onClick = { dialog = SettingsDialog.BiaModel }
                    )
                    if (BuildConfig.DEBUG && profileViewModel.isLogViewerSupported) {
                        SettingsItem(
                            title = "查看运行日志",
                            subtitle = if (logEntries.isEmpty()) "暂无日志" else "共 ${logEntries.size} 条记录",
                            icon = MiuixIcons.Notes,
                            onClick = onOpenLogViewer
                        )
                    }
                    if (BuildConfig.DEBUG && profileViewModel.isBleCaptureSupported) {
                        SettingsItem(
                            title = "蓝牙协议抓包 (Debug)",
                            subtitle = if (bleCaptureEntries.isEmpty()) {
                                "暂无报文 · 连接体脂秤并称重后自动记录完整协议包"
                            } else {
                                "已捕获 ${bleCaptureEntries.size} 条报文 · 点击查看与导出"
                            },
                            icon = MiuixIcons.Scan,
                            onClick = onOpenCapture
                        )
                    }
                    SettingsItem(
                        title = "关于软件",
                        subtitle = "版本 ${BuildConfig.VERSION_NAME}",
                        icon = MiuixIcons.Info,
                        onClick = { dialog = SettingsDialog.About }
                    )
                }
            }
        }
    }

    // ── 弹窗（Window 层）─────────────────────────────────────────────────
    when (dialog) {
        SettingsDialog.AddMember -> AddMemberDialog(
            onDismiss = { dialog = null },
            onConfirm = { name, sex, height, birth, weight ->
                profileViewModel.addMember(name, sex, height, birth, weight)
            }
        )
        SettingsDialog.WebDavConfig -> WebDavConfigDialog(
            initialUrl = webdavUrl,
            initialUsername = webdavUsername,
            initialPassword = webdavPassword,
            isOperating = isOperating,
            onDismiss = { dialog = null },
            onSave = profileViewModel::saveWebdavConfig,
            onTest = profileViewModel::testWebdavConnection
        )
        SettingsDialog.ScaleModel -> SelectScaleModelDialog(
            currentModel = selectedScaleModel,
            onModelSelected = profileViewModel::selectScaleModel,
            onDismiss = { dialog = null }
        )
        SettingsDialog.BiaModel -> SelectBiaModelDialog(
            currentModel = selectedBiaModel,
            onModelSelected = profileViewModel::selectBiaModel,
            onDismiss = { dialog = null }
        )
        SettingsDialog.About -> AboutDialog(
            onDismiss = { dialog = null }
        )
        null -> Unit
    }

    memberToDelete?.let { member ->
        DeleteMemberConfirmDialog(
            member = member,
            onDismiss = { memberToDelete = null },
            onConfirm = { profileViewModel.deleteMember(member) }
        )
    }

    scaleToRemove?.let { scale ->
        RemoveScaleConfirmDialog(
            scale = scale,
            rememberedCount = rememberedScales.size,
            onDismiss = { scaleToRemove = null },
            onConfirm = { profileViewModel.removeScale(scale.mac) }
        )
    }
}

// ── 设置页小件 ─────────────────────────────────────────────────────────────

/** 分组标题（SmallTitle 自带 subtitle 字阶与内边距）。 */
@Composable
private fun SmallSectionTitle(text: String) {
    top.yukonga.miuix.kmp.basic.SmallTitle(text = text)
}

/** 分组卡片容器（卡外水平 12dp 边距）。 */
@Composable
private fun Card12(content: @Composable () -> Unit) {
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        content()
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

/** 设置行（ArrowPreference + 图标圆底）；加载中禁点并显示加载环。 */
@Composable
private fun SettingsItem(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    isLoading: Boolean = false,
    onClick: () -> Unit
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

/** 成员行：图标 + 姓名/性别身高 + 删除。 */
@Composable
private fun MemberRow(
    member: FamilyMember,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        SettingsIconTile(
            icon = MiuixIcons.Contacts,
            containerColor = MiuixTheme.colorScheme.secondaryContainer,
            contentColor = MiuixTheme.colorScheme.onSecondaryContainer
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = member.name, style = MiuixTheme.textStyles.title3)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${if (member.sex == Sex.MALE) "男" else "女"} · ${member.heightCm.toInt()}cm",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = MiuixIcons.Delete,
                contentDescription = "删除",
                tint = MiuixTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.errorContainer)
                    .padding(6.dp)
            )
        }
    }
}

/** 已记住体脂秤行：整行点击切换当前使用设备，尾部移除。 */
@Composable
private fun RememberedScaleRow(
    scale: RememberedScale,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SettingsIconTile(
            icon = MiuixIcons.SearchDevice,
            containerColor = if (isActive) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.secondaryContainer,
            contentColor = if (isActive) MiuixTheme.colorScheme.onPrimary
            else MiuixTheme.colorScheme.onSecondaryContainer
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = scale.name,
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isActive) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = MiuixTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "使用中",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = scale.detail.ifBlank { scale.model.displayName } + " · " + scale.mac,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = MiuixIcons.Delete,
                contentDescription = "移除 ${scale.name}",
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions
            )
        }
    }
}

/** 关于软件弹窗：应用名 + 版本信息。 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    WindowDialog(
        show = true,
        title = "关于软件",
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.app_name),
                style = MiuixTheme.textStyles.title3,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}
