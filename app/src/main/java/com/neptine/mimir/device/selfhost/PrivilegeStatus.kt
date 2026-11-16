package com.neptine.mimir.device.selfhost

/**
 * 特权服务的对外状态。
 *
 * 只描述"用户现在能做/该做什么"，不暴露内部机制：配对、无线调试、壳进程三件事
 * 在界面上是同一句提示的不同阶段。
 */
enum class PrivilegeStatus {

    /** 没连 Wi-Fi（或以太网），无线调试无法启用。 */
    NO_WIFI,

    /** 无线调试没打开。 */
    WIRELESS_OFF,

    /** 无线调试可用，但本机还没有 adb 密钥——需要配对一次。 */
    NEED_PAIR,

    /** 已配对、环境就绪，但壳进程还没起来。 */
    PAIRED_IDLE,

    /** 正在拉起壳进程。 */
    STARTING,

    /** binder 已接入，设备层可用。 */
    READY,

    /** 之前就绪过，现在断了（进程被杀或令牌失效）。 */
    SERVICE_DEAD,
}
