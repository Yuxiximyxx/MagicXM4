package io.github.yuxiximyxx.magicxm4

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.yuxiximyxx.magicxm4.ui.MagicXM4AppCompose
import io.github.yuxiximyxx.magicxm4.ui.MagicXM4Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MagicXM4Theme {
                MagicXM4AppCompose()
            }
        }
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
