package com.fauxx.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.fauxx.di.fauxxDataStore
import com.fauxx.engine.EngineState
import com.fauxx.util.Clock
import com.fauxx.util.SystemClockImpl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Specification for when a [ResumeWorker] should fire and post the tap-to-resume
 * notification. Produced by [com.fauxx.engine.PoisonEngine] when it decides to stop
 * the FGS during a long pause, consumed by [PhantomForegroundService] which calls
 * [ResumeScheduler] before tearing itself down.
 */
sealed class ResumeSpec {
    /** Fire at a specific wall-clock time (epoch ms). Used for quiet-hours resume. */
    data class AtTime(val epochMs: Long) : ResumeSpec()

    /** Fire when constraints are met. Used for wifi/battery pauses. */
    data class WhenConstraintMet(
        val network: NetworkType? = null,
        val batteryNotLow: Boolean = false
    ) : ResumeSpec()
}

/** Unique WorkManager name for the tap-to-resume job. Shared so [EngineReconcileWorker]
 *  can tell whether a constraint-based resume is already pending. */
internal const val RESUME_WORK_NAME = "fauxx_resume"
private const val ALARM_REQUEST_CODE = 4242

/**
 * Schedules a [ResumeWorker] to fire under a given [ResumeSpec].
 *
 * Single uniquely-named WorkManager entry (`fauxx_resume`) with [ExistingWorkPolicy.REPLACE]
 * — only one pending resume notification exists at a time. If the user opens the app and
 * resumes manually before the scheduler fires, [cancel] should be called from the
 * service start path so a stale notification doesn't surface later.
 *
 * Also records the pending resume in DataStore and exposes it as [pendingResume] (#346): the
 * engine is destroyed once it resigns, so this record is the only thing that can tell the
 * dashboard the engine is paused rather than stopped, and when it comes back.
 */
@Singleton
class ResumeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock = SystemClockImpl(),
    private val dataStore: DataStore<Preferences> = context.fauxxDataStore,
) : PendingResumeSource {

    /**
     * Record writes run in call order on one thread, each finishing before the next starts, so a
     * [schedule] followed at once by a [cancel] can never land as "still pending". Off the
     * caller's thread: [cancel] is called on the main thread from the service's stop path.
     */
    private val recordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val recordLock = Mutex()

    override val pendingResume: Flow<PendingResume?> = dataStore.data
        .map { it.toPendingResume() }
        .catch { e ->
            Timber.w(e, "ResumeScheduler: could not read the pending resume")
            emit(null)
        }
        .distinctUntilChanged()

    /**
     * Schedule the resume for [spec]. [pausedState] is the engine state the engine resigned in,
     * recorded only for the dashboard (#346); it does not affect when or how the resume fires.
     */
    fun schedule(spec: ResumeSpec, pausedState: EngineState = EngineState.STOPPED) {
        when (spec) {
            is ResumeSpec.AtTime -> scheduleAtTime(spec.epochMs)
            is ResumeSpec.WhenConstraintMet -> scheduleWhenConstraintMet(spec)
        }
        record(PendingResume(pausedState, (spec as? ResumeSpec.AtTime)?.epochMs))
    }

    /**
     * Time-based (quiet-hours) resume. Prefers an EXACT ALARM that auto-starts the FGS at the
     * scheduled time with no user interaction (#126) — an exact-alarm broadcast is an allowed
     * FGS-start context on Android 14+. Falls back to the WorkManager tap-to-resume notification
     * when exact alarms aren't available.
     */
    private fun scheduleAtTime(epochMs: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // canScheduleExactAlarms() is API 31+; before Android 12 exact alarms need no permission
        // and are always schedulable.
        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (canScheduleExact) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, alarmPendingIntent())
                Timber.d("ResumeScheduler: exact alarm set for $epochMs")
                return
            } catch (e: SecurityException) {
                Timber.w(e, "ResumeScheduler: exact alarm denied; falling back to notification")
            }
        }
        val delayMs = (epochMs - clock.currentTimeMillis()).coerceAtLeast(0L)
        Timber.d("ResumeScheduler: scheduling resume notification at $epochMs (in ${delayMs / 1000}s)")
        enqueueResumeWorker(
            OneTimeWorkRequestBuilder<ResumeWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
        )
    }

    /** Constraint-based (wifi/battery) resume. Stays on WorkManager — only time-based resumes can use an alarm. */
    private fun scheduleWhenConstraintMet(spec: ResumeSpec.WhenConstraintMet) {
        val constraintsBuilder = Constraints.Builder()
        spec.network?.let { constraintsBuilder.setRequiredNetworkType(it) }
        if (spec.batteryNotLow) constraintsBuilder.setRequiresBatteryNotLow(true)
        Timber.d("ResumeScheduler: scheduling when constraint met (network=${spec.network}, batteryNotLow=${spec.batteryNotLow})")
        enqueueResumeWorker(
            OneTimeWorkRequestBuilder<ResumeWorker>()
                .setConstraints(constraintsBuilder.build())
                .build()
        )
    }

    private fun enqueueResumeWorker(request: OneTimeWorkRequest) {
        WorkManager.getInstance(context).enqueueUniqueWork(RESUME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun alarmPendingIntent(): PendingIntent {
        val intent = Intent(context, AlarmResumeReceiver::class.java).apply {
            action = AlarmResumeReceiver.ACTION_RESUME
            // Explicit target package — defensive against implicit-PendingIntent flags (CWE-927).
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Cancel any pending resume — both the WorkManager notification and the exact alarm. Called
     * once the engine clears its constraint gate (it has taken over from the pending resume) or
     * when the user disables the engine — NOT merely on service start, so a death during engine
     * init can't wipe a still-needed resume (#156).
     */
    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(RESUME_WORK_NAME)
        runCatching {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmPendingIntent())
        }
        record(null)
        Timber.d("ResumeScheduler: cancelled")
    }

    private fun record(pending: PendingResume?) {
        recordScope.launch {
            recordLock.withLock {
                runCatching { dataStore.writePendingResume(pending) }
                    .onFailure { Timber.w(it, "ResumeScheduler: could not record the pending resume") }
            }
        }
    }
}
