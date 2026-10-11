package com.fauxx.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fauxx.engine.power.AlarmEngineWakeup
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/**
 * Receives the alarm [AlarmEngineWakeup] sets for the engine's next action (#335) and hands the
 * wakeup to the running engine. Does nothing else: if the engine is not running there is no wait
 * to end, and the short wake lock the handover takes times out on its own.
 */
class EngineWakeupReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WakeupEntryPoint {
        fun engineWakeup(): AlarmEngineWakeup
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WAKEUP) return
        runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, WakeupEntryPoint::class.java)
                .engineWakeup()
                .onAlarm(intent.getLongExtra(EXTRA_SEQUENCE, -1L))
        }.onFailure { Timber.w(it, "EngineWakeupReceiver: could not reach the engine") }
    }

    companion object {
        const val ACTION_WAKEUP = "com.fauxx.ENGINE_WAKEUP"

        /** Which scheduled wait the alarm belongs to, so a stale one is ignored. */
        const val EXTRA_SEQUENCE = "com.fauxx.extra.WAKEUP_SEQUENCE"
    }
}
