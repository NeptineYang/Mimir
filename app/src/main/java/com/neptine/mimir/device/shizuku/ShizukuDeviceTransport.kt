package com.neptine.mimir.device.shizuku

import android.content.Context
import com.neptine.mimir.device.ActivityPort
import com.neptine.mimir.device.DeviceTransport
import com.neptine.mimir.device.DisplayPort
import com.neptine.mimir.device.ImePort
import com.neptine.mimir.device.InputPort
import com.neptine.mimir.device.PackagePort
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shizuku（shell uid）宿主实现（设计文档 §2.2 / §3.4）。
 *
 * 分工：建屏、取帧、注入、拉起 App、ime 切换全部跑在 Shizuku UserService（shell uid）里，
 * 因为这几件事需要的 CAPTURE_VIDEO_OUTPUT / INJECT_EVENTS / START_ANY_ACTIVITY 只有 shell 持有。
 *
 * 与 §2.2 的一处实现差异：设计文档提到注入可以在 app 进程用 binder 包装完成以省一次往返；
 * 实测路径改成统一走 UserService——注入需要反射 InputEvent.setDisplayId 这一 @hide 成员，
 * 放在 shell 进程可避免 app 进程的 hidden API 限制，代价只是每步多一次 AIDL 转发（亚毫秒）。
 */
class ShizukuDeviceTransport @Inject constructor(
    @ApplicationContext context: Context,
) : DeviceTransport {

    private val shell = ShizukuShell(context)

    /** 虚拟屏端口。 */
    override val displays: DisplayPort = ShizukuDisplayPort(shell)

    /** 拉起与前台校验端口。 */
    override val activities: ActivityPort = ShizukuActivityPort(shell)

    /** 定向注入端口。 */
    override val inputs: InputPort = ShizukuInputPort(shell)

    /** 输入法端口；P5 未通过时上层不得调用 select()。 */
    override val ime: ImePort = ShizukuImePort(context, shell)

    /** 应用枚举端口。 */
    override val packages: PackagePort = ShizukuPackagePort(shell)

    /**
     * 功能：判断 Shizuku binder 与授权是否就绪
     * 参数：无
     * 返回：就绪为 true
     */
    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) { shell.isReady() }
}
