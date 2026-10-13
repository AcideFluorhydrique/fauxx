package com.fauxx.engine.webview

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.fauxx.data.crawllist.CrawlEntry
import com.fauxx.data.crawllist.HealthFailure
import com.fauxx.data.crawllist.UrlHealthTracker
import com.fauxx.engine.dns.CustomDns
import com.fauxx.engine.webview.TrackerDnsProbe.Companion.isReal
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Where a crawl visit's outcome goes once the visit is over (#353). */
fun interface CrawlOutcomeSink {
    /** [failure] is null when the page loaded. Never throws. */
    suspend fun report(entry: CrawlEntry, failure: PageLoadFailure?)

    companion object {
        val NONE = CrawlOutcomeSink { _, _ -> }
    }
}

/** Whether the device has a working internet connection right now (#353). */
fun interface NetworkOnline {
    fun isOnline(): Boolean

    companion object {
        /** The default network has internet and Android has validated it (not a captive portal). */
        fun system(context: Context): NetworkOnline = NetworkOnline {
            runCatching {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }.getOrDefault(true)
        }
    }
}

/**
 * Decides whether a failed crawl visit counts against the page, and records it in
 * [UrlHealthTracker] if it does (#353).
 *
 * A failure the device caused is never the page's fault, so it is dropped:
 * - [LoadFailureKind.LOCAL]: Fauxx's own DNS proxy, no connection, a sinkholed address, a refused
 *   app-link redirect;
 * - any failure while the device has no validated connection;
 * - a refused connection whose name resolves only to a sinkhole (0.0.0.0, loopback, a private
 *   address): what Pi-hole and most DNS blockers answer;
 * - a name that did not resolve, unless a fresh system lookup confirms the name is missing while
 *   a control name resolves, and no DNS-level blocker has been detected (#341): a blocker that
 *   answers NXDOMAIN looks exactly like a dead domain, and an outage of the resolver looks like
 *   every domain being dead.
 * The checks are DNS lookups only, through the device's resolver, like [TrackerDnsProbe]'s, and
 * never in strict custom-DNS mode, where the failures that need them go uncounted.
 */
@Singleton
class CrawlHealthReporter @Inject constructor(
    private val health: UrlHealthTracker,
    private val online: NetworkOnline,
    private val lookup: SystemDnsLookup,
    private val blockSource: TrackerBlockSource,
    private val customDns: CustomDns,
) : CrawlOutcomeSink {

    override suspend fun report(entry: CrawlEntry, failure: PageLoadFailure?) {
        try {
            if (failure == null) {
                health.recordSuccess(entry.url, entry.domain)
                return
            }
            val counted = healthFailureFor(entry, failure) ?: return
            health.recordFailure(entry.url, entry.domain, counted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("Crawl health update failed: ${e.javaClass.simpleName}")
        }
    }

    /** What [failure] counts as against the page, or null if it does not count. */
    internal suspend fun healthFailureFor(entry: CrawlEntry, failure: PageLoadFailure): HealthFailure? {
        if (failure.kind == LoadFailureKind.LOCAL) return null
        if (!online.isOnline()) return null
        // Strict custom DNS (#333): nothing Fauxx does may touch the device's resolver, so the
        // failures that need a lookup to tell the site from the device go uncounted.
        val canLookUp = customDns.systemLookupsAllowed()
        return when {
            failure.nameNotResolved -> if (canLookUp) confirmMissingName(entry.domain) else null
            failure.connectionRefused ->
                if (!canLookUp || resolvesToSinkhole(entry.domain)) null else HealthFailure.TRANSIENT
            failure.kind == LoadFailureKind.DEAD -> HealthFailure.DEAD_URL
            failure.kind == LoadFailureKind.BOT_WALL -> HealthFailure.BOT_WALL
            else -> HealthFailure.TRANSIENT
        }
    }

    private suspend fun confirmMissingName(host: String): HealthFailure? {
        when (val answer = lookup.lookup(host, LOOKUP_TIMEOUT_MS)) {
            // It resolves now: the failure was the resolver's moment, not a missing domain.
            is SystemLookupResult.Resolved -> return if (answer.isReal()) HealthFailure.TRANSIENT else null
            SystemLookupResult.Unavailable -> return null
            SystemLookupResult.Failed -> Unit
        }
        if (!lookup.lookup(TrackerDnsProbe.CONTROL_HOST, LOOKUP_TIMEOUT_MS).isReal()) return null
        if (dnsBlockerDetected()) return null
        return HealthFailure.DEAD_HOST
    }

    private suspend fun resolvesToSinkhole(host: String): Boolean {
        val answer = lookup.lookup(host, LOOKUP_TIMEOUT_MS)
        return answer is SystemLookupResult.Resolved && !answer.isReal()
    }

    private suspend fun dnsBlockerDetected(): Boolean {
        val verdict = withTimeoutOrNull(VERDICT_TIMEOUT_MS) { blockSource.verdict.first() } ?: return true
        return verdict.blocked && verdict.kind == TrackerBlockKind.DNS
    }

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 4_000L
        const val VERDICT_TIMEOUT_MS = 1_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CrawlHealthModule {
    @Binds
    abstract fun bindCrawlOutcomeSink(reporter: CrawlHealthReporter): CrawlOutcomeSink

    companion object {
        @Provides
        @Singleton
        fun provideNetworkOnline(@ApplicationContext context: Context): NetworkOnline = NetworkOnline.system(context)
    }
}
