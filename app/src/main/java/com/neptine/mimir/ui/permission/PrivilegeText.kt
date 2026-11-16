package com.neptine.mimir.ui.permission

import com.neptine.mimir.device.selfhost.PrivilegeStatus
import com.neptine.mimir.ui.i18n.tStatic

/**
 * 功能：把特权服务状态翻成一句短标签
 * 参数：status 特权服务状态
 * 返回：中文短标签（英文界面走 [tStatic] 的词条）
 */
fun privilegeStatusText(status: PrivilegeStatus): String = when (status) {
    PrivilegeStatus.NO_WIFI -> "没连 Wi-Fi".tStatic()
    PrivilegeStatus.WIRELESS_OFF -> "无线调试未打开".tStatic()
    PrivilegeStatus.NEED_PAIR -> "还没配对".tStatic()
    PrivilegeStatus.PAIRED_IDLE -> "服务未启动".tStatic()
    PrivilegeStatus.STARTING -> "正在启动".tStatic()
    PrivilegeStatus.READY -> "已就绪".tStatic()
    PrivilegeStatus.SERVICE_DEAD -> "服务已断开".tStatic()
}

/**
 * 功能：把特权服务状态翻成一句"该怎么处理"
 * 参数：status 特权服务状态
 * 返回：面向用户的处置说明
 */
fun privilegeStatusDetail(status: PrivilegeStatus): String = when (status) {
    PrivilegeStatus.NO_WIFI -> "无线调试需要先连一个 Wi-Fi（或以太网）。".tStatic()
    PrivilegeStatus.WIRELESS_OFF -> "在开发者选项里打开「无线调试」。".tStatic()
    PrivilegeStatus.NEED_PAIR -> "在「无线调试 → 使用配对码配对设备」里取码，再到通知栏回复。".tStatic()
    PrivilegeStatus.PAIRED_IDLE -> "已配对，回到这里会自动启动服务。".tStatic()
    PrivilegeStatus.STARTING -> "正在拉起特权进程，请稍候。".tStatic()
    PrivilegeStatus.READY -> "设备层已可用，可以下发任务。".tStatic()
    PrivilegeStatus.SERVICE_DEAD -> "服务被系统回收了；确认省电策略为「无限制」后会自动重连。".tStatic()
}
