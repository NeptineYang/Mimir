package com.neptine.mimir

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.neptine.mimir.ui.MimirApp
import com.neptine.mimir.ui.i18n.LanguagePrefs
import com.neptine.mimir.ui.i18n.UiLanguageState
import com.neptine.mimir.ui.theme.MimirTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 壳入口：Compose 宿主。
 *
 * 继承 ComponentActivity：既是 Hilt @AndroidEntryPoint 的要求，
 * 也是 setContent 与 Compose 生命周期的载体。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * 交接通知带过来的任务标识；由 MimirApp 跳到接管页后清空。
     * 放在 Activity 而不是 ViewModel：它来自 Intent，且必须在页面重建时仍然有效。
     */
    private val pendingTakeover = mutableStateOf<String?>(null)

    /**
     * 功能：装配主题与导航图
     * 参数：savedInstanceState 系统保存的实例状态，可为空
     * 返回：无
     *
     * targetSdk 36 在 Android 15+ 会强制全面屏，所以显式开启并统一声明浅色系统栏，
     * 免得各版本行为不一致。内边距由各页面自己按 statusBars / navigationBars 处理。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // 在首帧之前恢复语言，避免先闪一下中文再切英文
        UiLanguageState.current = LanguagePrefs.read(this)
        pendingTakeover.value = intent?.getStringExtra(EXTRA_TAKEOVER_TASK_ID)
        setContent {
            MimirTheme {
                MimirApp(
                    pendingTakeover = pendingTakeover,
                    onTakeoverHandled = { pendingTakeover.value = null },
                )
            }
        }
    }

    /**
     * 功能：接收再次点击通知时的新意图
     * 参数：intent 新意图
     * 返回：无
     *
     * 需要 launchMode=singleTop：应用已在前台时，通知点击复用同一个 Activity，
     * 只把新的任务标识交给 Composable 去跳转。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTakeover.value = intent.getStringExtra(EXTRA_TAKEOVER_TASK_ID)
    }

    companion object {
        /** 附加的任务标识：存在时进入接管页。 */
        const val EXTRA_TAKEOVER_TASK_ID = "takeover_task_id"
    }
}
