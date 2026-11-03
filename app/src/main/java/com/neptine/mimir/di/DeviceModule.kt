package com.neptine.mimir.di

import com.neptine.mimir.device.DeviceTransport
import com.neptine.mimir.device.shizuku.ShizukuDeviceTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 设备层装配：默认绑定 Shizuku 宿主（模拟器 adb 宿主在二期接入，§3.4）。 */
@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {

    /**
     * 功能：提供设备能力总入口
     * 参数：transport Shizuku 宿主实现
     * 返回：DeviceTransport 实例
     */
    @Provides
    @Singleton
    fun provideDeviceTransport(transport: ShizukuDeviceTransport): DeviceTransport = transport
}
