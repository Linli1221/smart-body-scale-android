package com.example.dianzicheng.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.RememberedScale
import com.example.dianzicheng.domain.Sex
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 添加新成员弹窗。
 *
 * 与旧实现一致：不做表单校验，全部兜底——姓名空白取「新成员」、身高解析失败取 170.0、
 * 出生日期固定为当前时间减 25 年、初始参考体重 0.0。
 */
@Composable
fun AddMemberDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, sex: Sex, heightCm: Double, birthDateEpochMs: Long, weightKg: Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var isMale by remember { mutableStateOf(true) }
    var heightText by remember { mutableStateOf("170") }

    WindowDialog(
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
            Text(
                text = "性别",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isMale, onClick = { isMale = true })
                Spacer(modifier = Modifier.width(4.dp))
                Text("男生", style = MiuixTheme.textStyles.body1)
                Spacer(modifier = Modifier.width(16.dp))
                RadioButton(selected = !isMale, onClick = { isMale = false })
                Spacer(modifier = Modifier.width(4.dp))
                Text("女生", style = MiuixTheme.textStyles.body1)
            }
            TextField(
                value = heightText,
                onValueChange = { heightText = it },
                label = "身高 (cm)",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
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
                TextButton(
                    text = "确定",
                    onClick = {
                        val safeName = name.trim().ifBlank { "新成员" }
                        val height = heightText.trim().toDoubleOrNull() ?: 170.0
                        val birth = System.currentTimeMillis() - 25L * 365 * 24 * 3600 * 1000
                        onConfirm(safeName, if (isMale) Sex.MALE else Sex.FEMALE, height, birth, 0.0)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}

/** 删除成员确认弹窗。 */
@Composable
fun DeleteMemberConfirmDialog(
    member: FamilyMember,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    WindowDialog(
        show = true,
        title = "确认删除成员",
        summary = "确定要删除成员「${member.name}」吗？\n删除后该成员的历史测量记录将保留，但不再关联到此成员。",
        onDismissRequest = onDismiss
    ) {
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
            TextButton(
                text = "删除",
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error)
            )
        }
    }
}

/** 移除已记住体脂秤的确认弹窗。 */
@Composable
fun RemoveScaleConfirmDialog(
    scale: RememberedScale,
    rememberedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    WindowDialog(
        show = true,
        title = "移除这台体脂秤？",
        summary = "确定要从已记住的列表中移除「${scale.name}」吗？\n\n" +
            "当前共记住 $rememberedCount 台体脂秤，移除后其余设备仍可自动连接；" +
            "若全部移除，App 将暂停自动连接，直到您重新手动搜索并绑定设备。",
        onDismissRequest = onDismiss
    ) {
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
            TextButton(
                text = "移除",
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error)
            )
        }
    }
}

/**
 * WebDAV 云同步配置弹窗：测试连接（不保存不关闭）与保存（写配置并关闭）。
 * 无本地校验，结果统一走 statusMessage 提示。
 */
@Composable
fun WebDavConfigDialog(
    initialUrl: String,
    initialUsername: String,
    initialPassword: String,
    isOperating: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onTest: (String, String, String) -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }

    WindowDialog(
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
                    onClick = {
                        onSave(url, username, password)
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("保存")
                }
            }
        }
    }
}
