package com.neptine.mimir.device

/**
 * 设备能力总入口（设计文档 §3.4）。
 *
 * 三种宿主的差异只在"命令怎么发出去"：真机走 Shizuku UserService，模拟器走 adb，
 * 开发期可走 PC adb over TCP。控制层本身就是命令式的，换宿主不改变语义，因此抽象不增加成本。
 */
interface DeviceTransport {

    /** 虚拟屏端口。 */
    val displays: DisplayPort

    /** 拉起与前台校验端口。 */
    val activities: ActivityPort

    /** 定向注入端口。 */
    val inputs: InputPort

    /** 文本输入端口；P5 未通过时上层不得调用。 */
    val ime: ImePort

    /** 应用枚举端口。 */
    val packages: PackagePort

    /**
     * 功能：判断当前宿主是否可用（Shizuku binder 与授权是否就绪）
     * 参数：无
     * 返回：可用为 true
     */
    suspend fun isAvailable(): Boolean
}
