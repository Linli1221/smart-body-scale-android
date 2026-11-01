package com.example.dianzicheng

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle

/** Health Connect 授权页面与系统权限使用页面的用途说明。 */
class HealthPermissionsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle("系统健康数据使用说明")
            .setMessage(
                "开启同步并授权后，应用会将所有成员的体重及可用体脂数据写入系统 Health Connect。" +
                    "手动同步历史记录时也会写入所有成员的数据。应用不读取系统健康中的其他数据。\n\n" +
                    "可在应用设置中关闭自动同步，也可在系统健康设置中撤销授权；已写入的数据由系统健康管理。"
            )
            .setPositiveButton("知道了") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
