package com.fauxx.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Posts the tap-to-resume notification when fired by [ResumeScheduler].
 *
 * Triggered by:
 * - Quiet-hours end (initial-delay scheduled at start of next allowed window)
 * - Constraint satisfaction (network/battery, after a prolonged no-usable-network or battery pause)
 *
 * Honours [PreferenceKeys.ENABLED] so a user who disabled the engine while paused
 * doesn't get nagged, and skips the prompt when the service is already running so a
 * constraint-met resume from a prior session can't double-notify over a live engine
 * (#156). Plain [CoroutineWorker] — no Hilt deps needed; falls through the
 * [androidx.hilt.work.HiltWorkerFactory] to WorkManager's default factory.
 */
class ResumeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // This job is the pending resume, and it has now fired, so the dashboard must stop
        // reading the engine as "paused until..." (#346). Whatever happens next (the user taps
        // the notification, or the engine resigns again) records its own state.
        runCatching { applicationContext.fauxxDataStore.writePendingResume(null) }
            .onFailure { Timber.w(it, "ResumeWorker: failed to clear the pending resume") }
        val enabled = try {
            applicationContext.fauxxDataStore.data.first()[PreferenceKeys.ENABLED] ?: false
        } catch (e: Exception) {
            Timber.w(e, "ResumeWorker: failed to read ENABLED flag")
            false
        }
        if (!enabled) {
            Timber.i("ResumeWorker: engine disabled by user; no notification posted")
            return Result.success()
        }
        if (PhantomForegroundService.isRunning) {
            Timber.i("ResumeWorker: engine already running; no notification posted")
            return Result.success()
        }
        Timber.i("ResumeWorker: posting tap-to-resume notification")
        postResumeNotification(applicationContext)
        return Result.success()
    }
}
