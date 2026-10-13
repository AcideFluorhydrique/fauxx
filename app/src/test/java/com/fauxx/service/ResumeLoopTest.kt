package com.fauxx.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.fauxx.di.PreferenceKeys
import com.fauxx.di.fauxxDataStore
import com.fauxx.engine.EngineState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.annotation.Config

/**
 * Closes the resume loop's previously-untested half. [EngineResumeSchedulerIntegrationTest]
 * proves the engine's long-pause signal reaches [ResumeScheduler] and enqueues the `fauxx_resume`
 * WorkManager job; this proves the rest of the wire:
 *   - [ResumeScheduler] enqueues exactly one uniquely-named job and [ResumeScheduler.cancel]
 *     removes it (so a manual resume before the worker fires doesn't leave a stale notification).
 *   - [ResumeWorker] posts the tap-to-resume notification when the engine is enabled, and posts
 *     nothing when the user disabled it while paused — the "don't nag a user who turned it off"
 *     contract, which is the single decision that keeps the whole loop from being a nuisance.
 *
 * The worker is driven directly via [TestListenableWorkerBuilder] (doWork runs synchronously in
 * the test coroutine) rather than through WorkManager's executor, whose CoroutineWorker bridge
 * hops to Dispatchers.Default and would race a state assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ResumeLoopTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        // ResumeWorker now consults the process-global running flag; clear any leak from a
        // sibling test in the shared fork so the "enabled → posts" case isn't suppressed.
        PhantomForegroundService.setRunningForTest(false)
    }

    @After
    fun tearDown() {
        notificationManager().cancelAll()
        // Reset the global exact-alarm capability so it doesn't leak into other tests.
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        PhantomForegroundService.setRunningForTest(false)
    }

    @Test
    fun `scheduler enqueues one resume job and cancel removes it`() {
        val scheduler = ResumeScheduler(context)
        val workManager = WorkManager.getInstance(context)

        scheduler.schedule(ResumeSpec.AtTime(epochMs = System.currentTimeMillis() + 60_000))
        val enqueued = workManager.getWorkInfosForUniqueWork(RESUME_WORK_NAME).get()
        assertEquals("schedule must enqueue exactly one resume job", 1, enqueued.size)
        assertTrue(
            "the job must be pending, not already failed/cancelled, got ${enqueued[0].state}",
            enqueued[0].state == WorkInfo.State.ENQUEUED || enqueued[0].state == WorkInfo.State.BLOCKED,
        )

        scheduler.cancel()
        val afterCancel = workManager.getWorkInfosForUniqueWork(RESUME_WORK_NAME).get()
        assertTrue(
            "cancel must cancel the pending resume job",
            afterCancel.all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    @Test
    fun `AtTime resume uses an exact alarm and skips workmanager when exact alarms are allowed`() {
        // The full flavor declares USE_EXACT_ALARM, so canScheduleExactAlarms() is true and the
        // AtTime quiet-hours resume auto-starts the FGS via an exact alarm (#126) instead of the
        // tap-to-resume notification. Force that state here.
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler = ResumeScheduler(context)
        val workManager = WorkManager.getInstance(context)

        scheduler.schedule(ResumeSpec.AtTime(epochMs = System.currentTimeMillis() + 60_000))

        assertNotNull(
            "an exact alarm must be scheduled for the AtTime resume",
            shadowOf(alarmManager).peekNextScheduledAlarm(),
        )
        assertTrue(
            "no WorkManager notification job is enqueued when the exact alarm is used",
            workManager.getWorkInfosForUniqueWork(RESUME_WORK_NAME).get().isEmpty(),
        )

        scheduler.cancel()
        assertNull(
            "cancel must clear the exact alarm",
            shadowOf(alarmManager).peekNextScheduledAlarm(),
        )
    }

    @Test
    fun `worker posts the resume notification when the engine is enabled`() = runBlocking {
        setEngineEnabled(true)

        val result = TestListenableWorkerBuilder<ResumeWorker>(context).build().doWork()

        assertTrue("worker must succeed", result is ListenableWorker.Result.Success)
        assertNotNull(
            "an enabled engine must get the tap-to-resume notification",
            shadowOf(notificationManager()).getNotification(RESUME_NOTIFICATION_ID),
        )
    }

    @Test
    fun `worker posts nothing when the engine is already running`() = runBlocking {
        // A constraint-met resume from a prior session must not double-notify over a live
        // engine (#156): if the service is already running, the prompt is suppressed.
        setEngineEnabled(true)
        PhantomForegroundService.setRunningForTest(true)

        val result = TestListenableWorkerBuilder<ResumeWorker>(context).build().doWork()

        assertTrue("worker must succeed", result is ListenableWorker.Result.Success)
        assertNull(
            "no tap-to-resume nag while the engine is already running",
            shadowOf(notificationManager()).getNotification(RESUME_NOTIFICATION_ID),
        )
    }

    @Test
    fun `worker posts nothing when the user disabled the engine while paused`() = runBlocking {
        setEngineEnabled(false)

        val result = TestListenableWorkerBuilder<ResumeWorker>(context).build().doWork()

        assertTrue("worker must still succeed (nothing to do)", result is ListenableWorker.Result.Success)
        assertNull(
            "a user who turned the engine off must not be nagged to resume",
            shadowOf(notificationManager()).getNotification(RESUME_NOTIFICATION_ID),
        )
    }

    @Test
    fun `schedule records the pending resume for the dashboard and cancel clears it`() = runBlocking {
        // The engine is destroyed once it resigns, so this record is all the dashboard has to say
        // "paused, back at 7:00" instead of "ACTIVE, engine stopped" (#346).
        context.fauxxDataStore.writePendingResume(null)
        val scheduler = ResumeScheduler(context)
        val resumeAt = System.currentTimeMillis() + 60_000

        scheduler.schedule(ResumeSpec.AtTime(resumeAt), EngineState.PAUSED_QUIET_HOURS)
        assertEquals(
            PendingResume(EngineState.PAUSED_QUIET_HOURS, resumeAt),
            withTimeout(5_000) { scheduler.pendingResume.first { it != null } },
        )

        // A constraint resume has no time; the new record replaces the old one whole.
        scheduler.schedule(ResumeSpec.WhenConstraintMet(batteryNotLow = true), EngineState.PAUSED_BATTERY)
        assertEquals(
            PendingResume(EngineState.PAUSED_BATTERY, null),
            withTimeout(5_000) { scheduler.pendingResume.first { it?.pausedState == EngineState.PAUSED_BATTERY } },
        )

        scheduler.cancel()
        assertNull(withTimeout(5_000) { scheduler.pendingResume.first { it == null } })
    }

    @Test
    fun `a cancel straight after a schedule never leaves the resume recorded`() = runBlocking {
        // The writes are async (cancel runs on the main thread), so they must land in call order.
        context.fauxxDataStore.writePendingResume(null)
        val scheduler = ResumeScheduler(context)
        repeat(20) {
            scheduler.schedule(ResumeSpec.AtTime(System.currentTimeMillis() + 60_000), EngineState.PAUSED_QUIET_HOURS)
            scheduler.cancel()
        }
        // Wait for a marker queued behind every write above, then check what they left.
        scheduler.schedule(ResumeSpec.WhenConstraintMet(batteryNotLow = true), EngineState.PAUSED_BATTERY)
        scheduler.cancel()
        withTimeout(5_000) { scheduler.pendingResume.first { it == null } }
        delay(200)
        assertNull(scheduler.pendingResume.first())
    }

    @Test
    fun `worker retires the recorded pending resume when it fires`() = runBlocking {
        setEngineEnabled(true)
        context.fauxxDataStore.writePendingResume(PendingResume(EngineState.PAUSED_WIFI, null))

        TestListenableWorkerBuilder<ResumeWorker>(context).build().doWork()

        // The resume has happened (as a tap-to-resume prompt), so the dashboard must stop
        // reading the engine as waiting for it.
        assertNull(context.fauxxDataStore.data.first().toPendingResume())
    }

    private fun setEngineEnabled(enabled: Boolean) = runBlocking {
        context.fauxxDataStore.edit { it[PreferenceKeys.ENABLED] = enabled }
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private companion object {
        const val RESUME_WORK_NAME = "fauxx_resume"
    }
}
