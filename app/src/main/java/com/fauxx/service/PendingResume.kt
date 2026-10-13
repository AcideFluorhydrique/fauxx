package com.fauxx.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.fauxx.di.PreferenceKeys
import com.fauxx.engine.EngineState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A resume the engine is waiting on after it resigned from the foreground service (#346).
 *
 * Once the engine resigns (quiet hours, a long network or battery pause, a loop failure) the
 * service and the engine are destroyed, so [com.fauxx.engine.PoisonEngine.engineState] reads
 * [EngineState.STOPPED] and nothing in memory remembers why or for how long. [ResumeScheduler]
 * records this alongside the alarm or WorkManager job so the dashboard can say "paused, back at
 * 7:00" instead of "ACTIVE, engine stopped".
 *
 * @property pausedState the engine state at the moment it resigned: the pause that caused it,
 *   or [EngineState.STOPPED] for a loop-failure restart.
 * @property resumeAtMs wall-clock epoch ms of a time-based resume ([ResumeSpec.AtTime]), or null
 *   for a constraint-based one ([ResumeSpec.WhenConstraintMet]), which has no known time.
 */
data class PendingResume(val pausedState: EngineState, val resumeAtMs: Long?)

/** Where the dashboard reads the pending resume from (#346); [ResumeScheduler] in production. */
interface PendingResumeSource {
    /** The resume currently pending, or null when none is. Emits on every change. */
    val pendingResume: Flow<PendingResume?>

    companion object {
        /** Never anything pending; for tests and fakes. */
        val NONE: PendingResumeSource = object : PendingResumeSource {
            override val pendingResume: Flow<PendingResume?> = flowOf(null)
        }
    }
}

/** Decodes the persisted pending resume, or null when none is recorded (or the record is unreadable). */
internal fun Preferences.toPendingResume(): PendingResume? {
    val state = this[PreferenceKeys.PENDING_RESUME_STATE]
        ?.let { name -> runCatching { EngineState.valueOf(name) }.getOrNull() }
        ?: return null
    return PendingResume(state, this[PreferenceKeys.PENDING_RESUME_AT])
}

/** Persists [pending], or clears the record when it is null. */
internal suspend fun DataStore<Preferences>.writePendingResume(pending: PendingResume?) {
    edit { prefs ->
        if (pending == null) {
            prefs.remove(PreferenceKeys.PENDING_RESUME_STATE)
            prefs.remove(PreferenceKeys.PENDING_RESUME_AT)
        } else {
            prefs[PreferenceKeys.PENDING_RESUME_STATE] = pending.pausedState.name
            if (pending.resumeAtMs != null) {
                prefs[PreferenceKeys.PENDING_RESUME_AT] = pending.resumeAtMs
            } else {
                prefs.remove(PreferenceKeys.PENDING_RESUME_AT)
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PendingResumeModule {
    @Binds
    abstract fun bindPendingResumeSource(impl: ResumeScheduler): PendingResumeSource
}
