package com.verbalogix.assistant.di

import android.content.Context
import androidx.room.Room
import com.verbalogix.assistant.data.LocalmindDatabase
import com.verbalogix.assistant.data.MessageDao
import com.verbalogix.assistant.data.ProviderDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LocalmindDatabase =
        Room.databaseBuilder(context, LocalmindDatabase::class.java, LocalmindDatabase.NAME)
            .addMigrations(
                LocalmindDatabase.MIGRATION_1_2,
                LocalmindDatabase.MIGRATION_2_3,
                LocalmindDatabase.MIGRATION_3_4,
            )
            // No fallbackToDestructiveMigration. That flag silently wipes the user's
            // conversation when a migration is missing, turning a loud build-time
            // problem into quiet data loss in the field. A missing migration should
            // crash in testing, not delete history in production.
            .build()

    @Provides
    fun provideMessageDao(db: LocalmindDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideProviderDao(db: LocalmindDatabase): ProviderDao = db.providerDao()
}
