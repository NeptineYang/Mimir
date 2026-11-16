package com.neptine.mimir.device

import com.neptine.mimir.device.shizuku.ShizukuActivityPort
import com.neptine.mimir.device.shizuku.ShizukuDisplayPort
import com.neptine.mimir.device.shizuku.ShizukuImePort
import com.neptine.mimir.device.shizuku.ShizukuInputPort
import com.neptine.mimir.device.shizuku.ShizukuPackagePort
import com.neptine.mimir.device.selfhost.RoutingShellSession
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设备能力总入口的通用实现。
 *
 * 五个端口的语义与宿主无关，差别只在 binder 从哪来：外置 Shizuku 由它自己的
 * UserService 交付，自建通道由壳进程经 provider 交付。两种来源都收敛在
 * [RoutingShellSession] 里，端口类不需要知道区别。
 */
@Singleton
class ShellDeviceTransport @Inject constructor(
    @ApplicationContext context: Context,
    private val session: RoutingShellSession,
) : DeviceTransport {

    /** 虚拟屏端口。 */
    override val displays: DisplayPort = ShizukuDisplayPort(session)

    /** 拉起与前台校验端口。 */
    override val activities: ActivityPort = ShizukuActivityPort(session)

    /** 定向注入端口。 */
    override val inputs: InputPort = ShizukuInputPort(session)

    /** 输入法端口；P5 未通过时上层不得调用 select()。 */
    override val ime: ImePort = ShizukuImePort(context, session)

    /** 应用枚举端口。 */
    override val packages: PackagePort = ShizukuPackagePort(session)

    /**
     * 功能：判断当前是否有可用的特权服务
     * 参数：无
     * 返回：任一宿主就绪为 true
     */
    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) { session.isReady() }
}
