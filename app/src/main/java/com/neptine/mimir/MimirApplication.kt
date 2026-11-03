package com.neptine.mimir

import android.app.Application
import com.neptine.mimir.agent.TaskRecovery
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 应用入口。
 *
 * 选择 Hilt 的理由见设计文档 §2.1：设备层（shell uid 进程）与 Agent 层生命周期差异大，
 * 手写单例会失控，而 Koin 的运行期报错在设备层问题上代价太高。
 */
@HiltAndroidApp
class MimirApplication : Application() {

    @Inject
    lateinit var recovery: TaskRecovery

    /**
     * 功能：进程启动时做一次中断任务恢复
     * 参数：无
     * 返回：无
     *
     * 必须在 super.onCreate() 之后：Hilt 的属性注入在父类 onCreate 里完成。
     * 恢复本身是异步的，不阻塞冷启动。
     */
    override fun onCreate() {
        super.onCreate()
        recovery.launch()
    }
}
