package com.fauxx.engine.webview

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import com.fauxx.network.dns.AddressScope
import com.fauxx.util.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** What one system-resolver lookup returned (#341). */
sealed interface SystemLookupResult {
    data class Resolved(val addresses: List<InetAddress>) : SystemLookupResult

    /** No answer in time, NXDOMAIN, no data, or any other error. */
    data object Failed : SystemLookupResult

    /** The lookup could not be made at all (no thread free for it). Voids the probe's verdict. */
    data object Unavailable : SystemLookupResult
}

/**
 * A lookup through the SYSTEM resolver, from Fauxx's own process (#341), so it takes the same DNS
 * path as Fauxx's browsing when custom DNS is off.
 */
fun interface SystemDnsLookup {
    suspend fun lookup(host: String, timeoutMs: Long): SystemLookupResult

    companion object {
        /** [DnsResolver] on API 29+, a small bounded thread pool below that. */
        val SYSTEM: SystemDnsLookup = AndroidSystemDnsLookup()
    }
}

/** How Fauxx's traffic is routed right now (#341). */
data class VpnState(
    /** A VPN carries Fauxx's traffic. */
    val active: Boolean,
    /**
     * That VPN declares DNS servers of its own. When it declares none, Android sends Fauxx's DNS
     * to the underlying network's resolvers, outside the VPN, so a DNS-level block then comes from
     * the network, not from the VPN app. DuckDuckGo App Tracking Protection is such a VPN.
     */
    val declaresDns: Boolean,
) {
    companion object {
        val NONE = VpnState(active = false, declaresDns = false)
    }
}

/** Reads [VpnState] (#341). A binder call: never on the main thread. */
fun interface VpnStatus {
    fun current(): VpnState

    companion object {
        /**
         * The active network as Fauxx's process sees it. A per-app VPN that excludes Fauxx does not
         * show up here, and one that includes it does, which is exactly the question.
         */
        fun system(context: Context): VpnStatus = VpnStatus {
            runCatching {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val network = cm.activeNetwork
                val active = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                VpnState(active, active && cm.getLinkProperties(network)?.dnsServers?.isNotEmpty() == true)
            }.getOrDefault(VpnState.NONE)
        }
    }
}

/** Outcome of one probe that reached a verdict. */
enum class TrackerProbeVerdict { OK, BLOCKED }

data class TrackerProbeResult(
    val verdict: TrackerProbeVerdict,
    /** How Fauxx's traffic was routed when the probe ran. */
    val vpn: VpnState,
    /** [Clock.elapsedRealtime] when the probe finished. */
    val atElapsedMs: Long,
)

/**
 * The active half of #341: look up three tracker hostnames and one control hostname through the
 * system resolver, and say whether the trackers are being blocked at the DNS level.
 *
 * The passive count in [TrackerReachability] only sees a block when the failed request comes back
 * while the visit is still open. A lookup with its own timeout sees DNS-level blockers directly:
 * Pi-hole, NextDNS, Private DNS, a router, and VPN-based DNS filters that declare their own DNS
 * server (AdGuard, Rethink, Blokada). It cannot see DuckDuckGo App Tracking Protection: that VPN
 * declares no DNS server, so Android sends Fauxx's lookups to the underlying network outside it,
 * and it blocks by the name in the TLS hello instead. The dashboard's DuckDuckGo hint covers that.
 *
 * DNS lookups only. Nothing here ever connects to, or requests anything from, a tracker: an
 * OkHttp request would carry OkHttp's TLS fingerprint, the #168/#169 tell.
 *
 * Verdict:
 * - The control does not resolve to a real public address, or a lookup could not be made at all:
 *   no verdict (offline, captive portal, a broken network). Never "blocked".
 * - The control resolves and at least [BLOCKED_AT_LEAST] trackers fail, or resolve only to a
 *   sinkhole (0.0.0.0, `::`, loopback, a private or link-local address):
 *   [TrackerProbeVerdict.BLOCKED]. Not all three, so a list that happens to miss one host still
 *   counts; not one, so a single lookup lost on a flaky network does not.
 * - Otherwise [TrackerProbeVerdict.OK].
 *
 * At most one probe runs at a time; a second caller gets no verdict.
 */
@Singleton
class TrackerDnsProbe @Inject constructor(
    private val lookup: SystemDnsLookup,
    private val vpn: VpnStatus,
    private val clock: Clock,
) {
    private val inFlight = AtomicBoolean(false)

    /** Run one probe. Null when it reached no verdict, or one was already running. */
    suspend fun probe(): TrackerProbeResult? {
        if (!inFlight.compareAndSet(false, true)) return null
        try {
            val vpnState = vpn.current()
            val (control, trackers) = coroutineScope {
                val control = async { lookup.lookup(CONTROL_HOST, TIMEOUT_MS) }
                val trackers = TRACKER_HOSTS.map { async { lookup.lookup(it, TIMEOUT_MS) } }
                control.await() to trackers.map { it.await() }
            }
            val verdict = verdictFor(control, trackers)
            // Counts only, never which lookups.
            Timber.i(
                "Tracker DNS check: %s (%d of %d tracker lookups blocked, vpn=%s, vpnDns=%s)",
                verdict ?: "no verdict",
                trackers.count { !it.isReal() }, trackers.size, vpnState.active, vpnState.declaresDns,
            )
            return verdict?.let { TrackerProbeResult(it, vpnState, clock.elapsedRealtime()) }
        } finally {
            inFlight.set(false)
        }
    }

    internal companion object {
        /**
         * One ad host from each of three companies (Google, Amazon, X). Each is on StevenBlack,
         * the AdGuard DNS filter, OISD, Hagezi and DuckDuckGo's tracker list. Hosts such as
         * www.googletagmanager.com or connect.facebook.net are not: several big lists leave them
         * out on purpose because blocking them breaks sites. Their parent domains are in
         * `tracker_hosts.json`; a test keeps them there.
         */
        val TRACKER_HOSTS = listOf("googleads.g.doubleclick.net", "aax.amazon-adsystem.com", "static.ads-twitter.com")

        const val BLOCKED_AT_LEAST = 2

        /**
         * Wikipedia: a large first-party content site that no tracker or ad list blocks, with
         * stable, globally anycast DNS. A big ad company's own homepage (google.com) would be a
         * worse control: some aggressive lists and parental filters do block those.
         */
        const val CONTROL_HOST = "www.wikipedia.org"

        const val TIMEOUT_MS = 6_000L

        fun verdictFor(control: SystemLookupResult, trackers: List<SystemLookupResult>): TrackerProbeVerdict? {
            if (trackers.isEmpty() || !control.isReal()) return null
            if (trackers.any { it is SystemLookupResult.Unavailable }) return null
            val blocked = trackers.count { !it.isReal() }
            return if (blocked >= minOf(BLOCKED_AT_LEAST, trackers.size)) TrackerProbeVerdict.BLOCKED else TrackerProbeVerdict.OK
        }

        /** Resolved to at least one address a real server could have. */
        fun SystemLookupResult.isReal(): Boolean =
            this is SystemLookupResult.Resolved && addresses.any(::isRealAddress)

        fun isRealAddress(address: InetAddress): Boolean =
            !address.isAnyLocalAddress && !address.isMulticastAddress && !address.isLinkLocalAddress &&
                !AddressScope.isPrivateNetwork(address)
    }
}

/**
 * Production [SystemDnsLookup]. [sdkInt] and [fallbackPool] are parameters only so tests can reach
 * the API 26-28 path and its pool-full case.
 */
internal class AndroidSystemDnsLookup(
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val fallbackPool: ExecutorService = FALLBACK_POOL,
) : SystemDnsLookup {

    override suspend fun lookup(host: String, timeoutMs: Long): SystemLookupResult =
        withTimeoutOrNull(timeoutMs) {
            // Both checks: lint follows only the second, tests steer with the first.
            if (sdkInt >= Build.VERSION_CODES.Q && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolveAsync(host)
            } else {
                resolveBlocking(host)
            }
        } ?: SystemLookupResult.Failed

    /** API 29+: asynchronous, and cancelled through the signal if the timeout fires first. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun resolveAsync(host: String): SystemLookupResult = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { runCatching { signal.cancel() } }
        val callback = object : DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                val result = if (answer.isEmpty()) SystemLookupResult.Failed else SystemLookupResult.Resolved(answer)
                if (cont.isActive) cont.resume(result)
            }

            override fun onError(error: DnsResolver.DnsException) {
                if (cont.isActive) cont.resume(SystemLookupResult.Failed)
            }
        }
        try {
            // network = null: Fauxx's own default network, VPN included. No cache: a fresh answer.
            DnsResolver.getInstance().query(
                null, host, DnsResolver.FLAG_NO_CACHE_LOOKUP, DIRECT, signal, callback,
            )
        } catch (e: RuntimeException) {
            if (cont.isActive) cont.resume(SystemLookupResult.Unavailable)
        }
    }

    /**
     * API 26-28: `getAllByName` cannot be cancelled, so it runs on at most [MAX_THREADS] daemon
     * threads that exit when idle. When all are busy (lookups still hanging from an earlier probe)
     * the lookup is [SystemLookupResult.Unavailable] at once instead of queueing more: that says
     * nothing about trackers, so it voids the verdict rather than counting as a failure.
     */
    private suspend fun resolveBlocking(host: String): SystemLookupResult = suspendCancellableCoroutine { cont ->
        val task = try {
            fallbackPool.submit {
                val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
                val result = if (addresses.isNullOrEmpty()) SystemLookupResult.Failed else SystemLookupResult.Resolved(addresses)
                if (cont.isActive) cont.resume(result)
            }
        } catch (e: RejectedExecutionException) {
            cont.resume(SystemLookupResult.Unavailable)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { task.cancel(true) }
    }

    private companion object {
        const val MAX_THREADS = 6
        val DIRECT = Executor { it.run() }

        /** Bounded: core 0, at most [MAX_THREADS], no queue, idle threads exit after 30 s. */
        val FALLBACK_POOL: ExecutorService = ThreadPoolExecutor(
            0, MAX_THREADS, 30, TimeUnit.SECONDS, SynchronousQueue(),
        ) { r -> Thread(r, "fauxx-tracker-dns").apply { isDaemon = true } }
    }
}
