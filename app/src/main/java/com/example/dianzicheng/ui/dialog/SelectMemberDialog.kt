package com.example.dianzicheng.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Promotions
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 选择绑定成员弹窗（Window 层）。
 *
 * @param allowAutoMatch true=顶部成员入口流（含「全员智能自动匹配」选项）；
 *                       false=记录重绑流（只接受具体成员）。
 */
@Composable
fun SelectMemberDialog(
    members: List<FamilyMember>,
    currentMemberId: String?,
    allowAutoMatch: Boolean,
    onDismiss: () -> Unit,
    onSelect: (FamilyMember?) -> Unit
) {
    WindowDialog(
        show = true,
        title = "选择绑定成员",
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "选择成员后，系统将使用该成员的身高、年龄与性别重新计算准确的体脂率等指标并保存绑定。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (allowAutoMatch) {
                Surface(
                    onClick = { onSelect(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = if (currentMemberId == null) {
                        MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    } else {
                        Color.Transparent
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            MiuixIcons.Promotions,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("全员智能自动匹配", style = MiuixTheme.textStyles.body1)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            if (members.isEmpty()) {
                Text(
                    text = "暂无成员，请在“我的”页面添加家庭成员",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(members) { member ->
                        val isSelected = member.id == currentMemberId
                        Surface(
                            onClick = { onSelect(member) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) {
                                MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else {
                                Color.Transparent
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    MiuixIcons.Contacts,
                                    contentDescription = null,
                                    tint = if (isSelected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = member.name, style = MiuixTheme.textStyles.body1)
                                    Text(
                                        text = "${if (member.sex == Sex.MALE) "男" else "女"} · ${member.heightCm.toInt()}cm",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceSecondary
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        MiuixIcons.Basic.Check,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}
