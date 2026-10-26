package com.xiaoliu.yuno.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.xiaoliu.yuno.core.network.YunoLog

/**
 * 把运行日志通过系统分享（微信 / 文件传输助手 / 邮件…）发出去 ——
 * 排查问题不用连电脑：App 内点「导出日志」→ 选个 App 发走。
 */
fun shareYunoLog(context: Context) {
    val file = YunoLog.fileForShare()
    if (file == null) {
        Toast.makeText(context, "日志还没准备好", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "com.xiaoliu.yuno.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Yuno 运行日志")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "导出 Yuno 日志"))
}
