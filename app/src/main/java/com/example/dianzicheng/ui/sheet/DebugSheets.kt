package com.example.dianzicheng.ui.sheet

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.data.ble.BleProtocolRecorder
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.ui.component.collectAsStateWhenVisible
import com.example.dianzicheng.ui.state.ProfileViewModel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 运行日志级别着色（INFO 蓝 / WARN 橙为应用自有语义色；ERROR 用 error）。 */
private val LogInfoBlue = Color(0xFF2196F3)
private val LogWarnOrange = Color(0xFFFF9800)

/** 抓包方向着色（应用自有语义色）。 */
private val CaptureInBlue = Color(0xFF2196F3)
private val CaptureOutOrange = Color(0xFFFF9800)
private val CaptureScanPurple = Color(0xFF9C27B0)

/** 运行日志查看器弹层（Debug 专用）。 */
@Composable
fun LogSheet(
    show: Boolean,
    profileViewModel: ProfileViewModel,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    // 可见才订阅（启动/运行期性能）：隐藏时退订，每条日志不再触发 O(N) 快照拷贝与隐藏列表重组；
    // 离场动画期间用最后一次快照渲染，内容不空白。
    val entries = collectAsStateWhenVisible(show, profileViewModel.logEntries)
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    // 有新日志时自动滚到最底部（仅可见时；打开瞬间「订阅即补发」补齐后随 entries.size 变化滚到底）
    LaunchedEffect(show, entries.size) {
        if (show && entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    OverlayBottomSheet(
        show = show,
        title = "运行日志",
        onDismissRequest = onDismissRequest
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 工具行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = "${entries.size} 条记录",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismissRequest) {
                    Icon(MiuixIcons.Basic.Close, contentDescription = "关闭")
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(profileViewModel.exportLogsText()))
                    Toast.makeText(context, "已复制全部日志", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(MiuixIcons.Copy, contentDescription = "复制")
                }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "AppLog")
                        putExtra(Intent.EXTRA_TEXT, profileViewModel.exportLogsText())
                    }
                    context.startActivity(Intent.createChooser(send, "分享日志"))
                }) {
                    Icon(MiuixIcons.Share, contentDescription = "分享")
                }
                IconButton(onClick = profileViewModel::clearLogs) {
                    Icon(
                        MiuixIcons.Delete,
                        contentDescription = "清空",
                        tint = MiuixTheme.colorScheme.error
                    )
                }
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = MiuixIcons.Notes,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "暂无日志",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    // key 带下标，避免同毫秒同文案日志重复 key 导致崩溃
                    items(
                        count = entries.size,
                        key = { "log_${entries[it].timestamp}_${it}" }
                    ) { index ->
                        LogEntryRow(entry = entries[index], timeFmt = timeFmt)
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
        AppLogger.Level.INFO -> LogInfoBlue to LogInfoBlue.copy(alpha = 0.07f)
        AppLogger.Level.WARN -> LogWarnOrange to LogWarnOrange.copy(alpha = 0.1f)
        AppLogger.Level.ERROR -> MiuixTheme.colorScheme.error to
            MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
    }
    Surface(
        color = levelBg,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = timeFmt.format(Date(entry.timestamp)),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.outline,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = entry.level.name.take(1),
                style = MiuixTheme.textStyles.footnote2,
                color = levelColor
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "[${entry.tag}] ${entry.message}",
                style = MiuixTheme.textStyles.footnote2,
                color = if (entry.level == AppLogger.Level.ERROR) MiuixTheme.colorScheme.error
                else MiuixTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** 蓝牙协议抓包查看器弹层（Debug 专用）。 */
@Composable
fun CaptureSheet(
    show: Boolean,
    profileViewModel: ProfileViewModel,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // 可见才订阅（启动/运行期性能）：隐藏时退订，抓包高频写入不再驱动隐藏弹层快照拷贝/重组。
    val entries = collectAsStateWhenVisible(show, profileViewModel.bleCaptureEntries)
    var isExporting by remember { mutableStateOf(false) }

    OverlayBottomSheet(
        show = show,
        title = "蓝牙协议抓包",
        onDismissRequest = onDismissRequest
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 工具行
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Text(
                    text = "${entries.size} 条报文 · Debug 专用",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismissRequest) {
                    Icon(MiuixIcons.Basic.Close, contentDescription = "关闭")
                }
                IconButton(onClick = {
                    if (!isExporting) {
                        isExporting = true
                        scope.launch {
                            val intent = profileViewModel.createBleCaptureShareIntent(context)
                            if (intent != null) {
                                context.startActivity(
                                    Intent.createChooser(intent, "分享蓝牙协议抓包")
                                )
                            }
                            isExporting = false
                        }
                    }
                }) {
                    if (isExporting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(MiuixIcons.Share, contentDescription = "导出文件并分享")
                    }
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(profileViewModel.exportBleCaptureJson()))
                    Toast.makeText(context, "已复制抓包 (JSON Lines)", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(MiuixIcons.File, contentDescription = "复制 JSONL")
                }
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(profileViewModel.exportBleCaptureText()))
                    Toast.makeText(context, "已复制抓包 (文本)", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(MiuixIcons.Copy, contentDescription = "复制文本")
                }
                IconButton(onClick = profileViewModel::clearBleCapture) {
                    Icon(
                        MiuixIcons.Delete,
                        contentDescription = "清空",
                        tint = MiuixTheme.colorScheme.error
                    )
                }
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp, horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "暂无蓝牙协议报文",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "返回「测量」页，点亮体脂秤并完成一次称重，这里会自动记录扫描广播、" +
                                "GATT 连接、特征订阅、握手包与体重/阻抗通知的完整原始字节。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary
                        )
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    item {
                        Text(
                            text = "SCAN=广播  IN=秤→手机  OUT=手机→秤  EVENT=连接/订阅状态机   " +
                                        "容量 ${BleProtocolRecorder.MAX_ENTRIES} 条（已滚出 ${BleProtocolRecorder.dropped()} 条）",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                    items(
                        count = entries.size,
                        key = { "cap_${entries[it].timeMs}_${entries[it].offsetMs}_$it" }
                    ) { index ->
                        CaptureEntryRow(
                            index = index,
                            entry = entries[index]
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureEntryRow(
    index: Int,
    entry: BleProtocolRecorder.Entry
) {
    val directionColor = when (entry.direction) {
        "IN" -> CaptureInBlue
        "OUT" -> CaptureOutOrange
        "SCAN" -> CaptureScanPurple
        else -> MiuixTheme.colorScheme.onSurfaceSecondary
    }
    val bytes = runCatching { BleProtocolRecorder.hexToBytes(entry.payloadHex) }.getOrDefault(ByteArray(0))

    Surface(
        color = MiuixTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = String.format(Locale.US, "%04d", index),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.outline,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    color = directionColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = entry.direction,
                        style = MiuixTheme.textStyles.footnote2,
                        color = directionColor,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = entry.event,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${bytes.size} 字节 +${entry.offsetMs}ms",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.outline
                )
            }
            Text(
                text = "${entry.address}  ${entry.name}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
            Text(
                text = entry.payloadHex,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = runCatching { BleProtocolRecorder.toAscii(bytes) }.getOrDefault(""),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.outline,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
