package com.example.dianzicheng.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 应用根主题（Miuix / HyperOS 设计）。
 *
 * - 明暗模式始终跟随系统（ThemeController 内部使用 isSystemInDarkTheme）。
 * - Android 12+ 使用系统动态取色（Monet），低版本回落到 Miuix 默认配色。
 * - 全应用只应在此处创建一次 MiuixTheme，页面内不要再嵌套主题。
 */
@Composable
fun 电子秤Theme(
    content: @Composable () -> Unit
) {
    val controller = remember { ThemeController(ColorSchemeMode.MonetSystem) }
    MiuixTheme(controller = controller) {
        content()
    }
}
