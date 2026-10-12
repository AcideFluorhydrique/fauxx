package com.fauxx.engine.webview

import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** How a tracker request was stopped before it reached the tracker (#341). */
enum class TrackerBlockKind {
    /** The tracker's name did not resolve, or resolved to an address that cannot be reached. */
    DNS,

    /** The connection was refused, reset, timed out or cut off. */
    CONNECTION,
}

/**
 * Decides whether a failed WebView request means a tracker request was blocked on the way out
 * (#341), and how.
 *
 * WebView puts the exact Chromium error name in [android.webkit.WebResourceError.getDescription]
 * ("net::ERR_NAME_NOT_RESOLVED"), which is far more precise than the error code: the codes lump
 * together causes that are blocks and causes that are not (`ERROR_HOST_LOOKUP` also means "no
 * internet", `ERROR_UNKNOWN` is a catch-all for dozens of names). So the name decides, against a
 * whitelist:
 *
 * - DNS: `ERR_NAME_NOT_RESOLVED`, `ERR_NAME_RESOLUTION_FAILED`, `ERR_ADDRESS_INVALID` and
 *   `ERR_ADDRESS_UNREACHABLE` (the last two are how a resolver's 0.0.0.0 or `::` sinkhole answer
 *   usually ends).
 * - Connection: `ERR_CONNECTION_REFUSED`, `ERR_CONNECTION_RESET`, `ERR_CONNECTION_ABORTED`,
 *   `ERR_CONNECTION_FAILED`, `ERR_CONNECTION_CLOSED`, `ERR_TIMED_OUT`, `ERR_CONNECTION_TIMED_OUT`
 *   and `ERR_TUNNEL_CONNECTION_FAILED`.
 * - Every other name is not counted. That includes `ERR_INTERNET_DISCONNECTED` and
 *   `ERR_NETWORK_CHANGED` (the phone's network, not a blocker), `ERR_BLOCKED_BY_CLIENT`,
 *   `ERR_BLOCKED_BY_RESPONSE` and `ERR_BLOCKED_BY_ORB` (WebView's own policy), protocol errors,
 *   `ERR_EMPTY_RESPONSE` (the tracker was reached) and anything added to Chromium later, so a new
 *   name can only make the detection quieter.
 *
 * With custom DNS on (#227), the WebView reaches every host through Fauxx's loopback proxy, and
 * every refusal of the proxy's CONNECT reaches here as `ERR_TUNNEL_CONNECTION_FAILED`, which IS
 * counted: a 502 means the chosen resolver answered NXDOMAIN or a sinkhole address (it filters
 * trackers), a 504 that the proxy could not connect upstream (refused or dropped, often by a VPN
 * blocker, since the proxy runs in Fauxx's process). Only the proxy's 503 is not a block, and it
 * is effectively unreachable: Chromium opens at most 32 sockets per proxy, under the proxy's
 * tunnel cap of 48.
 *
 * When the description is missing or carries no `net::` name, the code decides, conservatively:
 * [WebViewClient.ERROR_HOST_LOOKUP] is DNS; [WebViewClient.ERROR_CONNECT],
 * [WebViewClient.ERROR_TIMEOUT], [WebViewClient.ERROR_IO] and
 * [WebViewClient.ERROR_FAILED_SSL_HANDSHAKE] are connection; everything else, the
 * [WebViewClient.ERROR_UNKNOWN] catch-all included, is not counted.
 *
 * HTTP error statuses never arrive here: a tracker that answers 4xx or 5xx was reached.
 */
object TrackerErrorClassifier {

    private val NET_ERROR = Regex("""net::(ERR_[A-Z0-9_]+)""")

    private val DNS_ERRORS = setOf(
        "ERR_NAME_NOT_RESOLVED",
        "ERR_NAME_RESOLUTION_FAILED",
        "ERR_ADDRESS_INVALID",
        "ERR_ADDRESS_UNREACHABLE",
    )

    private val CONNECTION_ERRORS = setOf(
        "ERR_CONNECTION_REFUSED",
        "ERR_CONNECTION_RESET",
        "ERR_CONNECTION_ABORTED",
        "ERR_CONNECTION_FAILED",
        "ERR_CONNECTION_CLOSED",
        "ERR_TIMED_OUT",
        "ERR_CONNECTION_TIMED_OUT",
        "ERR_TUNNEL_CONNECTION_FAILED",
    )

    fun classify(errorCode: Int, description: CharSequence?): TrackerBlockKind? {
        val name = description?.let { NET_ERROR.find(it)?.groupValues?.get(1) }
            ?: return classifyCode(errorCode)
        return when (name) {
            in DNS_ERRORS -> TrackerBlockKind.DNS
            in CONNECTION_ERRORS -> TrackerBlockKind.CONNECTION
            else -> null
        }
    }

    private fun classifyCode(errorCode: Int): TrackerBlockKind? = when (errorCode) {
        WebViewClient.ERROR_HOST_LOOKUP -> TrackerBlockKind.DNS
        WebViewClient.ERROR_CONNECT,
        WebViewClient.ERROR_TIMEOUT,
        WebViewClient.ERROR_IO,
        WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> TrackerBlockKind.CONNECTION
        else -> null
    }
}

/**
 * One visit's tracker counts (#341): requests to tracker hosts that Fauxx let out to the network,
 * and how many of those were blocked at the DNS or connection level. [mainFrameFailed] is true when
 * the page itself failed at the network level, in which case the visit says nothing about tracker
 * blocking (the phone may simply have been offline) and [TrackerReachability] ignores it.
 */
data class TrackerVisitCounts(
    val attempted: Int = 0,
    val dnsBlocked: Int = 0,
    val connectionBlocked: Int = 0,
    val mainFrameFailed: Boolean = false,
) {
    val blocked: Int get() = dnsBlocked + connectionBlocked
}

/**
 * Per-WebView tracker bookkeeping for the current visit (#341), owned by [PhantomWebViewPool] and
 * fed by [PhantomWebViewClient], next to the "resources loaded" counter (#73).
 *
 * [recordAttempt] runs on WebView's background IO thread and [recordError] on the main thread, so
 * everything here is lock-free and cheap: it runs for every allowed subresource.
 *
 * An error only counts when its URL matches an attempt this counter recorded, so a request Fauxx
 * refused itself (the safety blocklist, the MIME gate, images off) can never show up as blocked,
 * whatever error WebView reports for it. Attempts are remembered by a 64-bit hash of the URL, not
 * the URL: bid and sync URLs embed the decoy page's address and tracking ids, which have no reason
 * to sit in memory.
 */
class TrackerVisitCounter(
    private val hosts: TrackerHosts,
    private val maxPending: Int = MAX_PENDING,
) {
    /** URL hash -> attempts still waiting for an outcome. Successes never report, so this only grows per visit. */
    private val pending = ConcurrentHashMap<Long, Int>()
    private val attempted = AtomicInteger(0)
    private val dnsBlocked = AtomicInteger(0)
    private val connectionBlocked = AtomicInteger(0)

    @Volatile
    private var mainFrameFailed = false

    /**
     * Record [url] as a tracker attempt if [host] is a tracker. Returns whether it counted. Past
     * [maxPending] distinct outstanding URLs a visit stops counting, rather than letting a page
     * that fires thousands of beacons grow this without bound.
     */
    fun recordAttempt(url: String, host: String): Boolean {
        if (!hosts.isTracker(host)) return false
        val key = urlHash(url)
        if (pending.size >= maxPending && !pending.containsKey(key)) return false
        pending.merge(key, 1, Int::plus)
        attempted.incrementAndGet()
        return true
    }

    /**
     * A request for [url] failed with [errorCode] and [description]. Counts as blocked only if it
     * was a recorded attempt (see the class KDoc) and [TrackerErrorClassifier] calls it a block.
     * Returns the kind counted, or null.
     */
    fun recordError(url: String, errorCode: Int, description: CharSequence?): TrackerBlockKind? {
        if (pending.isEmpty()) return null
        var wasAttempt = false
        pending.computeIfPresent(urlHash(url)) { _, n ->
            wasAttempt = true
            if (n > 1) n - 1 else null
        }
        if (!wasAttempt) return null
        val kind = TrackerErrorClassifier.classify(errorCode, description) ?: return null
        when (kind) {
            TrackerBlockKind.DNS -> dnsBlocked.incrementAndGet()
            TrackerBlockKind.CONNECTION -> connectionBlocked.incrementAndGet()
        }
        return kind
    }

    /** The page itself failed at the network level; see [TrackerVisitCounts.mainFrameFailed]. */
    fun markMainFrameFailed() {
        mainFrameFailed = true
    }

    /**
     * A new navigation starts on the same acquired WebView. SearchPoisonModule loads a results
     * page and then a chain of refinements; one failed refinement must not discard the whole
     * session's tracker counts.
     */
    fun clearMainFrameFailed() {
        mainFrameFailed = false
    }

    fun snapshot(): TrackerVisitCounts = TrackerVisitCounts(
        attempted = attempted.get(),
        dnsBlocked = dnsBlocked.get(),
        connectionBlocked = connectionBlocked.get(),
        mainFrameFailed = mainFrameFailed,
    )

    /** Start a new visit. Called on acquire and on release, like the resource counter's reset. */
    fun reset() {
        pending.clear()
        attempted.set(0)
        dnsBlocked.set(0)
        connectionBlocked.set(0)
        mainFrameFailed = false
    }

    internal companion object {
        const val MAX_PENDING = 512

        /** 64-bit FNV-1a over the URL's UTF-16 units. Collisions are harmless and vanishingly rare. */
        fun urlHash(url: String): Long {
            var hash = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
            for (c in url) {
                hash = hash xor c.code.toLong()
                hash *= 0x100000001b3L // FNV prime
            }
            return hash
        }
    }
}
