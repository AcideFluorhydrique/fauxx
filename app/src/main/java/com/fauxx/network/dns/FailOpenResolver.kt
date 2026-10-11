package com.fauxx.network.dns

import com.fauxx.util.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.IOException

/**
 * Strict mode refused to send a lookup to the system resolver (#333). An [IOException] so it reads
 * as the resolver failing, which is what it is, never as "no such host".
 */
class StrictDnsException : IOException("Strict DNS: the system resolver is not used")

/** What is keeping custom DNS from working, so the dashboard can say what to do (#333). */
enum class DegradeCause {
    /** The resolver is unreachable, failing, or not the server first trusted. */
    RESOLVER,

    /** Fauxx's WebView cannot be routed through its proxy right now. */
    ROUTING,

    /** The saved settings cannot be used as they are (an invalid or incomplete address). */
    SETTINGS,
}

/** How custom DNS is doing, for the dashboard's quiet notice (#227, U2). */
sealed interface DnsHealth {
    /** No custom resolver configured; the system resolver is in use by choice. */
    data object Off : DnsHealth

    /** The custom resolver is answering. */
    data object Healthy : DnsHealth

    /**
     * Custom DNS is not doing its job since [sinceMs] (wall clock). Two causes need their own
     * dashboard wording: [intercepted], where answers ARE arriving, from another app that captures
     * port 53 rather than the user's server; and [certificateChanged], where the user's self-signed
     * server presented a key other than the one first trusted, which they fix themselves if they
     * replaced it.
     */
    data class Degraded(
        val sinceMs: Long,
        val reason: String,
        val intercepted: Boolean = false,
        val certificateChanged: Boolean = false,
        /**
         * Strict mode (#333): lookups that the custom resolver cannot answer FAIL instead of going
         * to the system resolver, so pages do not load until it recovers. Otherwise they fall back.
         */
        val blocked: Boolean = false,
        val cause: DegradeCause = DegradeCause.RESOLVER,
    ) : DnsHealth
}

/**
 * Fail-OPEN wrapper around the user's custom resolver (owner decision, #227): when the custom
 * resolver cannot answer, fall back to the system resolver so the engine keeps running, and report
 * it through [health] so the dashboard can say so quietly.
 *
 * - An ANSWER is never second-guessed. [Resolution.NoSuchHost] (NXDOMAIN) and sinkhole addresses
 *   are the custom resolver doing its job; only [Resolution.Failure] falls back. Otherwise a
 *   filtering custom resolver would silently become the blocking system one.
 * - A SLOW resolver is treated like a dead one, behind a three-state breaker. CLOSED: lookups go
 *   to the custom resolver; [failureThreshold] consecutive failures or slow answers OPEN it.
 *   OPEN: lookups go straight to the system resolver for [cooldownMs], so pages do not wait out a
 *   timeout each. Then exactly ONE lookup probes the custom resolver while every other lookup
 *   keeps using the system resolver; a good probe closes the breaker, a bad one reopens it. A page
 *   fans out dozens of lookups at once, so letting them all probe a still-dead resolver would stall
 *   every one of them for the full timeout, once per cooldown.
 * - A success from a lookup that started before the breaker opened is used but does not close it:
 *   only the probe decides that, so health does not flap.
 *
 * - With [fallsBack] false (strict mode, #333) the breaker works the same, but every lookup that
 *   would have gone to [system] fails instead, and [system] is never called. An open breaker then
 *   fails lookups at once rather than making each page wait out a dead resolver's timeout.
 *
 * Every lookup that does NOT get the custom resolver's answer is logged, at most once per
 * [LOG_INTERVAL_MS] with a count, so a user asking "where did my lookups go?" can see it.
 *
 * Thread-safe: the proxy resolves from many connection threads at once.
 */
class FailOpenResolver(
    private val custom: HostResolver,
    private val system: HostResolver,
    private val clock: Clock,
    private val failureThreshold: Int = 3,
    private val cooldownMs: Long = 60_000L,
    private val slowMs: Long = 3_000L,
    fallsBack: Boolean = true,
) : HostResolver {

    /**
     * False in strict mode. Settable on a live resolver: when the user turns strict mode on, the
     * proxy still serving pages is made strict at once, before its replacement is in place (#333).
     */
    @Volatile var fallsBack: Boolean = fallsBack

    private val _health = MutableStateFlow<DnsHealth>(DnsHealth.Healthy)
    val health: StateFlow<DnsHealth> = _health.asStateFlow()

    private val lock = Any()
    private var consecutiveFailures = 0
    private var open = false
    private var openUntilMs = 0L
    private var probeInFlight = false

    private enum class Route { CUSTOM, PROBE, SYSTEM }

    /** Lookups not answered by the custom resolver since the last log line, and when that was. */
    private var unloggedFallbacks = 0
    private var lastLogMs = Long.MIN_VALUE

    override fun resolve(host: String): Resolution {
        val route = synchronized(lock) {
            when {
                !open -> Route.CUSTOM
                clock.elapsedRealtime() < openUntilMs || probeInFlight -> Route.SYSTEM
                else -> {
                    probeInFlight = true
                    Route.PROBE
                }
            }
        }
        if (route == Route.SYSTEM) return fallback(host, "breaker open")

        val started = clock.elapsedRealtime()
        val result = try {
            custom.resolve(host)
        } catch (e: RuntimeException) {
            Resolution.Failure(e)
        }
        val slow = clock.elapsedRealtime() - started > slowMs

        return when {
            result is Resolution.Failure -> {
                // The exception CLASS only, never its message: OkHttp's DoH puts the hostname
                // being looked up in it, and this ends up in logs and in [DnsHealth.Degraded].
                val reason = "unreachable (${result.cause.javaClass.simpleName})"
                recordFailure(route, reason)
                fallback(host, reason)
            }
            slow && fallsBack -> {
                // A slow answer is still an answer; use it, but count it toward the breaker.
                // Not in strict mode: there, an open breaker fails every lookup, so a merely
                // slow resolver would cost a full outage per cooldown for nothing better.
                recordFailure(route, "slow answers")
                result
            }
            else -> {
                recordSuccess(route)
                result
            }
        }
    }

    /** The system resolver's answer, or in strict mode a failure that never asks it. */
    private fun fallback(host: String, reason: String): Resolution {
        val fallsBack = fallsBack
        logFallback(reason, fallsBack)
        return if (fallsBack) system.resolve(host) else Resolution.Failure(StrictDnsException())
    }

    private fun logFallback(reason: String, fallsBack: Boolean) {
        val count = synchronized(lock) {
            unloggedFallbacks++
            val now = clock.elapsedRealtime()
            if (lastLogMs != Long.MIN_VALUE && now - lastLogMs < LOG_INTERVAL_MS) return
            lastLogMs = now
            unloggedFallbacks.also { unloggedFallbacks = 0 }
        }
        if (fallsBack) {
            Timber.i("Custom DNS: %d lookup(s) went to the system resolver (%s)", count, reason)
        } else {
            Timber.i("Custom DNS: %d lookup(s) failed; strict mode keeps them off the system resolver (%s)", count, reason)
        }
    }

    private fun recordFailure(route: Route, reason: String) = synchronized(lock) {
        if (route == Route.PROBE) probeInFlight = false
        consecutiveFailures++
        if (route == Route.PROBE || consecutiveFailures >= failureThreshold) {
            open = true
            openUntilMs = clock.elapsedRealtime() + cooldownMs
            if (_health.value !is DnsHealth.Degraded) {
                _health.value = DnsHealth.Degraded(clock.currentTimeMillis(), reason, blocked = !fallsBack)
            }
        }
    }

    private companion object {
        const val LOG_INTERVAL_MS = 60_000L
    }

    private fun recordSuccess(route: Route) = synchronized(lock) {
        if (route == Route.PROBE) probeInFlight = false
        // A straggler that began before the breaker opened does not get to close it.
        if (open && route != Route.PROBE) return@synchronized
        consecutiveFailures = 0
        open = false
        _health.value = DnsHealth.Healthy
    }
}
