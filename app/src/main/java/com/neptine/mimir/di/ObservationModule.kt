package com.neptine.mimir.di

import com.neptine.mimir.device.CapabilityProbe
import com.neptine.mimir.device.DeviceTransport
import com.neptine.mimir.device.shizuku.ShizukuCapabilityProbe
import com.neptine.mimir.observation.AccessibilityTreeProvider
import com.neptine.mimir.observation.ConsistentObservationAssembler
import com.neptine.mimir.observation.ElementNumberer
import com.neptine.mimir.observation.FallbackScreenshotProvider
import com.neptine.mimir.observation.ObservationAssembler
import com.neptine.mimir.observation.OcrProvider
import com.neptine.mimir.observation.ScreenStability
import com.neptine.mimir.observation.ScreenshotProvider
import com.neptine.mimir.observation.TreeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 设备探针与观测层装配（设计文档 §2.2.2 / §2.3）。 */
@Module
@InstallIn(SingletonComponent::class)
object ObservationModule {

    /**
     * 功能：提供控件树采集实现
     * 参数：无
     * 返回：无障碍窗口实现
     */
    @Provides
    @Singleton
    fun provideTreeProvider(): TreeProvider = AccessibilityTreeProvider()

    /**
     * 功能：提供截图采集实现
     * 参数：transport 设备能力总入口
     * 返回：PixelCopy 主路径 + 无障碍兜底的实现
     */
    @Provides
    @Singleton
    fun provideScreenshotProvider(transport: DeviceTransport): ScreenshotProvider =
        FallbackScreenshotProvider(transport.displays)

    /**
     * 功能：提供元素编号器
     * 参数：无
     * 返回：编号器实例
     */
    @Provides
    @Singleton
    fun provideElementNumberer(): ElementNumberer = ElementNumberer()

    /**
     * 功能：提供一致观察装配实现
     * 参数：trees 控件树；screenshots 截图；numberer 编号器；ocr 端侧文字识别
     * 返回：一致观察装配器
     */
    @Provides
    @Singleton
    fun provideObservationAssembler(
        trees: TreeProvider,
        screenshots: ScreenshotProvider,
        numberer: ElementNumberer,
        ocr: OcrProvider,
    ): ObservationAssembler = ConsistentObservationAssembler(trees, screenshots, numberer, ocr)

    /**
     * 功能：提供安静判定
     * 参数：trees 控件树；screenshots 截图
     * 返回：安静判定器
     */
    @Provides
    @Singleton
    fun provideScreenStability(trees: TreeProvider, screenshots: ScreenshotProvider): ScreenStability =
        ScreenStability(trees, screenshots)

    /**
     * 功能：提供启动期能力探针
     * 参数：transport 设备能力总入口；trees 控件树；screenshots 截图
     * 返回：探针实现
     */
    @Provides
    @Singleton
    fun provideCapabilityProbe(
        transport: DeviceTransport,
        trees: TreeProvider,
        screenshots: ScreenshotProvider,
    ): CapabilityProbe = ShizukuCapabilityProbe(transport, trees, screenshots)
}
