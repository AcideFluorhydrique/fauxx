package com.fauxx.engine.power

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.fauxx.service.EngineWakeupReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Keeps the CPU awake while the engine has work in hand (#335).
 *
 * A foreground service keeps the process alive but not the CPU. With the screen off the CPU
 * suspends within seconds, and the engine's coroutine delays (which run on a clock that stops
 * while suspended) freeze with it, so before this the engine only made progress while something
 * else had the device awake. The engine holds this lock for the length of one action plus the
 * bookkeeping after it, and lets go before every wait.
 */
interface EngineWakeLock {
    /** Hold the CPU awake for at most [timeoutMs]. Acquiring while held restarts the timeout. */
    fun acquire(timeoutMs: Long)

    /** Let the CPU sleep again. A no-op when not held. */
    fun release()

    companion object {
        /** No-op lock, the engine's default so tests need not stand one up. */
        val NONE: EngineWakeLock = object : EngineWakeLock {
            override fun acquire(timeoutMs: Long) {}
            override fun release() {}
        }
    }
}

/**
 * Wakes the CPU when the engine's next action is due (#335).
 *
 * The engine sleeps between actions for minutes at a time. While the screen is on, a coroutine
 * delay is enough; with it off, the delay's clock stops with the CPU, so the engine also sets an
 * alarm for the same moment on the elapsed-realtime clock, which keeps counting through sleep.
 * Whichever comes first ends the wait.
 *
 * In deep Doze Android rations allow-while-idle alarms (an hourly quota per app for apps targeting
 * Android 12 or later; one per nine minutes or so before that), so a wakeup can arrive late. The
 * engine measures what is left of its wait against elapsed realtime when it wakes, so a late alarm
 * only stretches the gap between two actions; it never makes the engine skip or double up.
 */
interface EngineWakeup {
    /** Arrange a CPU wakeup at [atElapsedRealtimeMs], replacing any earlier one. */
    fun schedule(atElapsedRealtimeMs: Long)

    /** Drop the pending wakeup, if any. */
    fun cancel()

    /**
     * Suspend for up to [timeoutMs] of coroutine time, or until a scheduled wakeup fires.
     * Returns true when a wakeup ended the wait.
     */
    suspend fun await(timeoutMs: Long): Boolean

    /**
     * Drop the short hold on the CPU a fired wakeup took to hand over to the engine. The engine
     * calls it once it holds the CPU itself or goes back to waiting, so a wakeup costs only as
     * long as the handover takes. A no-op when nothing is held.
     */
    fun releaseHandover() {}

    companion object {
        /** Plain delay with no alarm, the engine's default so tests keep virtual time. */
        val NONE: EngineWakeup = object : EngineWakeup {
            override fun schedule(atElapsedRealtimeMs: Long) {}
            override fun cancel() {}
            override suspend fun await(timeoutMs: Long): Boolean {
                delay(timeoutMs)
                return false
            }
        }
    }
}

/** Tag of the engine's action lock, shown in `dumpsys power` and battery stats. */
const val ENGINE_WAKE_LOCK_TAG = "fauxx:engine"

/** Tag of the short lock a wakeup takes to hand the CPU to the engine. */
const val WAKEUP_WAKE_LOCK_TAG = "fauxx:wakeup"

/**
 * Marks the short handover lock [AlarmEngineWakeup] takes when its alarm fires. A lock of its
 * own, so that taking it can never shorten the engine's action lock (#335): re-acquiring a held
 * non-reference-counted lock replaces its timeout.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WakeupHandoverLock

/** [EngineWakeLock] over one non-reference-counted partial wake lock tagged [tag]. */
class PowerManagerEngineWakeLock(
    private val context: Context,
    private val tag: String,
) : EngineWakeLock {

    private val lock: PowerManager.WakeLock? by lazy {
        runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
                // Not reference-counted: a single release() must let the CPU sleep whatever the
                // acquire count, and a re-acquire restarts the timeout.
                ?.apply { setReferenceCounted(false) }
        }.onFailure { Timber.w(it, "Wake lock $tag unavailable") }.getOrNull()
    }

    // Every acquire passes a timeout, which lint cannot see through the parameter.
    @SuppressLint("WakelockTimeout")
    override fun acquire(timeoutMs: Long) {
        runCatching { lock?.acquire(timeoutMs) }
            .onFailure { Timber.w(it, "Wake lock $tag acquire failed") }
    }

    override fun release() {
        runCatching { lock?.let { if (it.isHeld) it.release() } }
            .onFailure { Timber.w(it, "Wake lock $tag release failed") }
    }
}

/** Request code of the engine-wakeup alarm; distinct from the resume alarm's. */
private const val WAKEUP_REQUEST_CODE = 4243

/**
 * How long a wakeup holds the CPU for the engine to get scheduled and take its own lock. The
 * engine releases it through [EngineWakeup.releaseHandover] once it holds the CPU itself or goes
 * back to waiting; the timeout only bounds a wakeup that finds no engine to hand over to.
 */
internal const val WAKEUP_HOLD_MS = 10_000L

/**
 * [EngineWakeup] over an allow-while-idle alarm delivered to [EngineWakeupReceiver].
 *
 * Each schedule gets a sequence number that travels in the alarm's intent and in the signal. A
 * broadcast already on its way when its wait ended on the timer cannot be retracted by [cancel],
 * so without the number it would end the NEXT wait early and take the handover lock for nothing.
 */
@Singleton
class AlarmEngineWakeup @Inject constructor(
    @ApplicationContext private val context: Context,
    @WakeupHandoverLock private val handoverLock: EngineWakeLock,
) : EngineWakeup {

    /** Conflated: several signals before the engine looks are one signal. Carries the sequence. */
    private val wakeups = Channel<Long>(Channel.CONFLATED)

    private val sequence = AtomicLong(0L)

    /** Sequence of the alarm currently set; -1 when none. */
    @Volatile private var current: Long = -1L

    override fun schedule(atElapsedRealtimeMs: Long) {
        val seq = sequence.incrementAndGet()
        current = seq
        // A signal left over from an earlier wait must not end this one early.
        wakeups.tryReceive()
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // canScheduleExactAlarms() is API 31+; before Android 12 exact alarms need no permission.
        // The full flavor declares USE_EXACT_ALARM; the play flavor does not and gets the inexact
        // variant, which Android may batch but still delivers through Doze.
        val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        try {
            if (canScheduleExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedRealtimeMs, pendingIntent(seq)
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedRealtimeMs, pendingIntent(seq)
                )
            }
        } catch (e: SecurityException) {
            // Exact alarms revoked between the check and the call. The engine still wakes on its
            // own delay whenever the device is awake, so degrade quietly.
            Timber.w(e, "Engine wakeup alarm denied")
        }
    }

    override fun cancel() {
        current = -1L
        // PendingIntent matching ignores extras, so this cancels whichever sequence is set.
        runCatching { context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(0L)) }
    }

    override suspend fun await(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (wakeups.receive() != current) { /* a stale signal: keep waiting */ }
        } != null

    override fun releaseHandover() {
        handoverLock.release()
    }

    /** Called by [EngineWakeupReceiver] when the alarm for [seq] fires. */
    fun onAlarm(seq: Long) {
        if (seq != current) {
            Timber.d("Engine wakeup $seq is stale (current $current); ignored")
            return
        }
        // The system holds the CPU only for the broadcast itself. Hold it a little longer, on a
        // lock of its own, so the engine's coroutine gets to run and take over.
        handoverLock.acquire(WAKEUP_HOLD_MS)
        wakeups.trySend(seq)
    }

    private fun pendingIntent(seq: Long): PendingIntent {
        val intent = Intent(context, EngineWakeupReceiver::class.java).apply {
            action = EngineWakeupReceiver.ACTION_WAKEUP
            putExtra(EngineWakeupReceiver.EXTRA_SEQUENCE, seq)
            // Explicit target package, defensive against implicit-PendingIntent flags (CWE-927).
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            WAKEUP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
