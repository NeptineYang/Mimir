package com.neptine.mimir.di

import com.neptine.mimir.agent.MemoryRetriever
import com.neptine.mimir.data.memory.RoomMemoryRetriever
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Agent 层装配：把记忆检索接口绑到真实实现（§2.5.6）。 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    /**
     * 功能：提供记忆检索实现
     * 参数：impl Room 实现（单例）
     * 返回：记忆检索接口
     *
     * 一期这里是恒返回空列表的空实现；二期换成读 mem_procedure 的真实检索，
     * 表为空时行为与空实现一致，不影响主链路。
     */
    @Provides
    @Singleton
    fun provideMemoryRetriever(impl: RoomMemoryRetriever): MemoryRetriever = impl
}
