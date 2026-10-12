package com.fauxx.engine.power

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import com.fauxx.service.EngineWakeupReceiver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** The engine's next-action alarm (#335). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AlarmEngineWakeupTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager

    /** The handover lock: separate from the engine's action lock by construction. */
    private val handover = object : EngineWakeLock {
        var acquiredFor: Long? = null
        override fun acquire(timeoutMs: Long) { acquiredFor = timeoutMs }
        override fun release() { acquiredFor = null }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        alarmManager = context.getSystemService(AlarmManager::class.java)
        // USE_EXACT_ALARM is a full-flavor permission; grant it as the full build would have it.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    /** The sequence number the currently scheduled alarm will deliver. */
    private fun scheduledSequence(): Long =
        shadowOf(shadowOf(alarmManager).nextScheduledAlarm!!.operation).savedIntent
            .getLongExtra(EngineWakeupReceiver.EXTRA_SEQUENCE, -1L)

    @Test
    fun `schedule sets an exact allow-while-idle alarm on elapsed realtime`() {
        val wakeup = AlarmEngineWakeup(context, handover)

        wakeup.schedule(123_456L)

        val alarm = shadowOf(alarmManager).nextScheduledAlarm!!
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.type)
        assertEquals(123_456L, alarm.triggerAtTime)
        assertTrue("must fire through Doze", alarm.isAllowWhileIdle)
        assertEquals(EngineWakeupReceiver.ACTION_WAKEUP, shadowOf(alarm.operation).savedIntent.action)
    }

    @Test
    fun `without exact alarms it falls back to the inexact allow-while-idle alarm`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val wakeup = AlarmEngineWakeup(context, handover)

        wakeup.schedule(5_000L)

        val alarm = shadowOf(alarmManager).nextScheduledAlarm!!
        assertEquals(5_000L, alarm.triggerAtTime)
        assertTrue(alarm.isAllowWhileIdle)
    }

    @Test
    fun `a new schedule replaces the old one and cancel clears it`() {
        val wakeup = AlarmEngineWakeup(context, handover)

        wakeup.schedule(1_000L)
        wakeup.schedule(2_000L)
        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
        assertEquals(2_000L, shadowOf(alarmManager).nextScheduledAlarm!!.triggerAtTime)

        wakeup.cancel()
        assertNull(shadowOf(alarmManager).nextScheduledAlarm)
    }

    @Test
    fun `the current alarm takes the handover lock briefly and ends the wait`() = runTest {
        val wakeup = AlarmEngineWakeup(context, handover)
        wakeup.schedule(1_000L)

        wakeup.onAlarm(scheduledSequence())

        assertEquals(WAKEUP_HOLD_MS, handover.acquiredFor)
        assertTrue(wakeup.await(1_000L))
    }

    @Test
    fun `releasing the handover lets go of the alarm's hold on the CPU`() {
        val wakeup = AlarmEngineWakeup(context, handover)
        wakeup.schedule(1_000L)
        wakeup.onAlarm(scheduledSequence())

        wakeup.releaseHandover()

        assertNull("the hold must not run out its full timeout once the engine has the CPU", handover.acquiredFor)
    }

    @Test
    fun `an alarm from an earlier wait is ignored`() = runTest {
        // Its wait ended on the timer and cancel() could not retract the broadcast already sent.
        // Acting on it would end the next wait early and spend that wait's trusted wakeup.
        val wakeup = AlarmEngineWakeup(context, handover)
        wakeup.schedule(1_000L)
        val stale = scheduledSequence()
        wakeup.schedule(10_000L)

        val logged = captureLogs { wakeup.onAlarm(stale) }

        assertNull("a stale alarm must not hold the CPU", handover.acquiredFor)
        assertFalse("nor end the current wait", wakeup.await(1_000L))
        assertTrue(logged.toString(), logged.any { "is stale" in it })
    }

    @Test
    fun `an alarm arriving after cancel is ignored`() = runTest {
        val wakeup = AlarmEngineWakeup(context, handover)
        wakeup.schedule(1_000L)
        val seq = scheduledSequence()
        wakeup.cancel()

        val logged = captureLogs { wakeup.onAlarm(seq) }

        assertNull(handover.acquiredFor)
        assertFalse(wakeup.await(1_000L))
        // The normal Doze path (#349): the engine woke on its own timer first. It lands in
        // exported logs, so it must not read like an error.
        assertTrue(logged.toString(), logged.any { "already awake or stopped" in it })
        assertTrue(logged.toString(), logged.none { "stale" in it })
    }

    /** Messages logged through Timber while [block] runs. */
    private fun captureLogs(block: () -> Unit): List<String> {
        val logged = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tree = object : timber.log.Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { logged += message }
        }
        timber.log.Timber.plant(tree)
        try {
            block()
        } finally {
            timber.log.Timber.uproot(tree)
        }
        return logged.toList()
    }

    @Test
    fun `a signal left over from an earlier wait does not end the next one`() = runTest {
        val wakeup = AlarmEngineWakeup(context, handover)
        wakeup.schedule(1_000L)
        wakeup.onAlarm(scheduledSequence()) // fired, but nobody was waiting any more
        wakeup.schedule(10_000L)

        assertFalse("the leftover signal must be dropped", wakeup.await(1_000L))
    }
}
