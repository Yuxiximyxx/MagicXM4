package io.github.yuxiximyxx.magicxm4

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.yuxiximyxx.magicxm4.ui.MagicXM4AppCompose
import io.github.yuxiximyxx.magicxm4.ui.MagicXM4Theme

/**
 * 模块设置入口（LSPosed 管理器的 MODULE_SETTINGS）跳板：
 * 3.0.12 起照 MagicPods 改成直达耳机详情页（此前渲染整套 MagicXM4AppCompose 首页）。
 * 本 Activity 不渲染任何界面，onCreate 里立即转发并 finish，避免闪旧界面。
 * 桌面启动器走 manifest 的 LauncherActivity alias（同样指向详情页）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, EarphoneDetailActivity::class.java))
        finish()
    }
}

class PopupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        setContent {
            MagicXM4Theme {
                MagicXM4AppCompose()
            }
        }
    }
}
