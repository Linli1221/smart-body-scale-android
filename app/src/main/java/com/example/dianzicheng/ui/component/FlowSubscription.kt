package com.example.dianzicheng.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.StateFlow

/**
 * 「可见才订阅」的 [StateFlow] 读取器（启动/运行期性能优化）。
 *
 * 隐藏的底部弹层如果常驻 collectAsState()，会一直订阅上游 StateFlow：
 * [com.example.dianzicheng.data.local.AppLogger] / [com.example.dianzicheng.data.ble.BleProtocolRecorder]
 * 的「无订阅不拷贝」优化因此失效，BLE 高频回调期间每条日志都要做一次 O(N) 快照拷贝，
 * 并驱动隐藏弹层持续重组。
 *
 * 语义：
 * - 弹层可见（[show] = true）：与 collectAsState() 等价，实时订阅；
 * - 弹层隐藏：立即退订，返回最后一次快照，保证 OverlayBottomSheet 离场动画期间内容不空白、不闪空。
 *
 * 隐藏期间上游暂停发布（AppLogger/BleProtocolRecorder 的订阅计数为 0），
 * 重新可见时由 ProfileViewModel 的「订阅即补发」机制（subscriptionCount > 0 → publishSnapshot）
 * 补齐最新数据，无需调用方额外刷新。
 *
 * 用法：val entries = collectAsStateWhenVisible(show, viewModel.logEntries)。
 */
@Composable
fun <T> collectAsStateWhenVisible(show: Boolean, flow: StateFlow<T>): T {
    var snapshot by remember(flow) { mutableStateOf(flow.value) }
    val live = if (show) flow.collectAsState().value else snapshot
    if (show) {
        // 快照在组合成功后同步：隐藏瞬间（离场动画）即可拿到最后一帧数据。
        SideEffect { snapshot = live }
    }
    return live
}
