package com.neptine.mimir.di

import android.content.Context
import com.neptine.mimir.adblink.AdbController
import com.neptine.mimir.device.DeviceTransport
import com.neptine.mimir.device.ShellDeviceTransport
import com.neptine.mimir.device.selfhost.AdbPairingHub
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 设备层装配：自建特权通道优先，外置 Shizuku 作为退路（§3.4）。 */
@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {

    /**
     * 功能：提供设备能力总入口
     * 参数：transport 通用实现（内部按"自建优先、Shizuku 兜底"路由）
     * 返回：DeviceTransport 实例
     */
    @Provides
    @Singleton
    fun provideDeviceTransport(transport: ShellDeviceTransport): DeviceTransport = transport

    /**
     * 功能：提供应用内 adb 通道
     * 参数：context 应用上下文
     * 返回：AdbController；与配对流程共用同一个实例，密钥只有一份
     */
    @Provides
    @Singleton
    fun provideAdbController(@ApplicationContext context: Context): AdbController =
        AdbPairingHub.controller(context)
}
