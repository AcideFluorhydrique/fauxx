package com.fauxx.di

import android.content.Context
import com.fauxx.engine.power.AlarmEngineWakeup
import com.fauxx.engine.power.ENGINE_WAKE_LOCK_TAG
import com.fauxx.engine.power.EngineWakeLock
import com.fauxx.engine.power.EngineWakeup
import com.fauxx.engine.power.PowerManagerEngineWakeLock
import com.fauxx.engine.power.WAKEUP_WAKE_LOCK_TAG
import com.fauxx.engine.power.WakeupHandoverLock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The engine's CPU wake lock, the separate short lock its wakeup alarm hands over with, and the
 * next-action wakeup itself (#335).
 */
@Module
@InstallIn(SingletonComponent::class)
object PowerModule {

    @Provides
    @Singleton
    fun provideEngineWakeLock(@ApplicationContext context: Context): EngineWakeLock =
        PowerManagerEngineWakeLock(context, ENGINE_WAKE_LOCK_TAG)

    @Provides
    @Singleton
    @WakeupHandoverLock
    fun provideWakeupHandoverLock(@ApplicationContext context: Context): EngineWakeLock =
        PowerManagerEngineWakeLock(context, WAKEUP_WAKE_LOCK_TAG)

    @Provides
    @Singleton
    fun provideEngineWakeup(impl: AlarmEngineWakeup): EngineWakeup = impl
}
