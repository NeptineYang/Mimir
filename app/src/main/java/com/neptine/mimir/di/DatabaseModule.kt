package com.neptine.mimir.di

import android.content.Context
import androidx.room.Room
import com.neptine.mimir.data.db.MimirDatabase
import com.neptine.mimir.data.db.dao.ActionIntentDao
import com.neptine.mimir.data.db.dao.AuditDao
import com.neptine.mimir.data.db.dao.ConfigDao
import com.neptine.mimir.data.db.dao.MemoryDao
import com.neptine.mimir.data.db.dao.RiskGrantDao
import com.neptine.mimir.data.db.dao.ScheduledTaskDao
import com.neptine.mimir.data.db.dao.TaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 数据库装配：单实例库 + 六张表的 DAO。 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * 功能：创建 Room 数据库单实例
     * 参数：context 应用上下文
     * 返回：MimirDatabase 实例
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MimirDatabase =
        Room.databaseBuilder(context, MimirDatabase::class.java, "mimir.db")
            .addMigrations(
                MimirDatabase.MIGRATION_1_2,
                MimirDatabase.MIGRATION_2_3,
                MimirDatabase.MIGRATION_3_4,
                MimirDatabase.MIGRATION_4_5,
                MimirDatabase.MIGRATION_5_6,
                MimirDatabase.MIGRATION_6_7,
                MimirDatabase.MIGRATION_7_8,
                MimirDatabase.MIGRATION_8_9,
            )
            .build()

    /**
     * 功能：提供任务表访问口
     * 参数：database 数据库实例
     * 返回：TaskDao 实例
     */
    @Provides
    fun provideTaskDao(database: MimirDatabase): TaskDao = database.taskDao()

    /**
     * 功能：提供写入意图表访问口
     * 参数：database 数据库实例
     * 返回：ActionIntentDao 实例
     */
    @Provides
    fun provideActionIntentDao(database: MimirDatabase): ActionIntentDao = database.actionIntentDao()

    /**
     * 功能：提供一次性授权表访问口
     * 参数：database 数据库实例
     * 返回：RiskGrantDao 实例
     */
    @Provides
    fun provideRiskGrantDao(database: MimirDatabase): RiskGrantDao = database.riskGrantDao()

    /**
     * 功能：提供审计表访问口
     * 参数：database 数据库实例
     * 返回：AuditDao 实例
     */
    @Provides
    fun provideAuditDao(database: MimirDatabase): AuditDao = database.auditDao()

    /**
     * 功能：提供记忆表访问口
     * 参数：database 数据库实例
     * 返回：MemoryDao 实例
     */
    @Provides
    fun provideMemoryDao(database: MimirDatabase): MemoryDao = database.memoryDao()

    /**
     * 功能：提供定时任务表访问口
     * 参数：database 数据库实例
     * 返回：ScheduledTaskDao 实例
     */
    @Provides
    fun provideScheduledTaskDao(database: MimirDatabase): ScheduledTaskDao = database.scheduledTaskDao()

    /**
     * 功能：提供配置与设备能力表访问口
     * 参数：database 数据库实例
     * 返回：ConfigDao 实例
     */
    @Provides
    fun provideConfigDao(database: MimirDatabase): ConfigDao = database.configDao()
}
