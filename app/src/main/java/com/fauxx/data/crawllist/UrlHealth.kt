package com.fauxx.data.crawllist

import android.content.Context
import com.fauxx.util.Clock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A crawl failure that counts against a page or its site (#353). Failures caused on the device
 * (offline, Fauxx's own DNS proxy, a DNS blocker) never get this far.
 */
enum class HealthFailure {
    /** HTTP 404/410 and the like: this URL is gone. Held back on its own, not the whole site. */
    DEAD_URL,

    /** The domain does not exist (NXDOMAIN, confirmed by a second lookup). Held back by domain. */
    DEAD_HOST,

    /** A bot check or a refusal (403/429/challenge). Held back by domain, with escalation. */
    BOT_WALL,

    /** A timeout, a reset, a 5xx. Held back by domain, with escalation. */
    TRANSIENT,

    /**
     * A search engine answered with a bot check (Google's /sorry page, a 429). Held back by engine,
     * from the FIRST hit: a CAPTCHA from Google covers the whole IP address, so the user's own
     * searches on the same network can meet it too, and every further search makes it worse.
     */
    SEARCH_BOT_WALL,
}

/** What is remembered about one URL or domain. */
data class HealthRecord(
    val kind: HealthFailure,
    /** Failures in a row, each within [UrlHealthPolicy.STRIKE_WINDOW_MS] of the one before. */
    val strikes: Int,
    val lastFailureAt: Long,
    /** Wall-clock ms until which the crawler skips it; 0 or past means not held back. */
    val suppressedUntil: Long,
)

/**
 * The backoff rules (#353), as pure functions so they can be tested without a clock or a disk.
 *
 * Why back off at all: hitting the same challenge page again and again is exactly what a bot does,
 * and it is visible to the site. A dead link is not a bot signal, but every visit to it is an
 * action spent on nothing. So:
 * - Dead URL (404/410): skipped for [DEAD_SUPPRESS_MS], then tried again, so a page that comes
 *   back is picked up again.
 * - Dead domain (NXDOMAIN): the same, but only from the second failure, since one lost lookup is
 *   not proof.
 * - Bot wall and transient errors: nothing on the first failure, then an escalating hold on the
 *   whole domain ([botWallHoldMs], [transientHoldMs]).
 * A success on the URL or the domain forgets the record. Strikes further apart than
 * [STRIKE_WINDOW_MS] start over.
 */
object UrlHealthPolicy {
    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DAY_MS = 24 * HOUR_MS

    const val DEAD_SUPPRESS_MS = 30 * DAY_MS
    const val STRIKE_WINDOW_MS = 7 * DAY_MS

    /** Upper bound on remembered records; the stalest are dropped first. */
    const val MAX_RECORDS = 512

    fun urlKey(url: String): String = "u:$url"

    fun domainKey(domain: String): String = "d:${domain.lowercase().removePrefix("www.")}"

    /** Search engines are remembered by their stable id ("google"), apart from crawl domains. */
    fun engineKey(engineId: String): String = "s:${engineId.lowercase()}"

    /** The key a [failure] on [url] at [domain] is remembered under. */
    fun keyFor(failure: HealthFailure, url: String, domain: String): String =
        if (failure == HealthFailure.DEAD_URL) urlKey(url) else domainKey(domain)

    fun onFailure(previous: HealthRecord?, failure: HealthFailure, now: Long): HealthRecord {
        val continues = previous != null && now - previous.lastFailureAt <= STRIKE_WINDOW_MS
        val strikes = if (continues) previous!!.strikes + 1 else 1
        val hold = holdMs(failure, strikes)
        val until = if (hold > 0) now + hold else 0L
        // Never shorten a hold already running (a fallback pick can visit a held-back domain).
        val keep = previous?.suppressedUntil?.takeIf { it > now } ?: 0L
        return HealthRecord(failure, strikes, now, maxOf(until, keep))
    }

    fun holdMs(failure: HealthFailure, strikes: Int): Long = when (failure) {
        HealthFailure.DEAD_URL -> DEAD_SUPPRESS_MS
        HealthFailure.DEAD_HOST -> if (strikes >= 2) DEAD_SUPPRESS_MS else 0L
        HealthFailure.BOT_WALL -> botWallHoldMs(strikes)
        HealthFailure.TRANSIENT -> transientHoldMs(strikes)
        HealthFailure.SEARCH_BOT_WALL -> searchBotWallHoldMs(strikes)
    }

    private fun searchBotWallHoldMs(strikes: Int): Long = when {
        strikes <= 1 -> HOUR_MS
        strikes == 2 -> 6 * HOUR_MS
        strikes == 3 -> DAY_MS
        else -> 3 * DAY_MS
    }

    private fun botWallHoldMs(strikes: Int): Long = when {
        strikes <= 1 -> 0L
        strikes == 2 -> 6 * HOUR_MS
        strikes == 3 -> DAY_MS
        else -> 3 * DAY_MS
    }

    private fun transientHoldMs(strikes: Int): Long = when {
        strikes <= 1 -> 0L
        strikes == 2 -> HOUR_MS
        strikes == 3 -> 6 * HOUR_MS
        strikes == 4 -> DAY_MS
        else -> 3 * DAY_MS
    }

    fun isSuppressed(record: HealthRecord?, now: Long): Boolean =
        record != null && record.suppressedUntil > now

    /**
     * Drop records that no longer matter (not held back, and too old to add to a strike), then
     * cap what is left at [MAX_RECORDS], keeping the ones held back longest.
     */
    fun prune(records: Map<String, HealthRecord>, now: Long): Map<String, HealthRecord> {
        val live = records.filterValues { it.suppressedUntil > now || now - it.lastFailureAt <= STRIKE_WINDOW_MS }
        if (live.size <= MAX_RECORDS) return live
        return live.entries
            .sortedWith(compareByDescending<Map.Entry<String, HealthRecord>> { it.value.suppressedUntil }
                .thenByDescending { it.value.lastFailureAt })
            .take(MAX_RECORDS)
            .associate { it.key to it.value }
    }
}

/** Where [UrlHealthTracker] keeps its records between runs. */
interface UrlHealthStore {
    fun load(): Map<String, HealthRecord>
    fun save(records: Map<String, HealthRecord>)
    fun clear()

    companion object {
        /** Nothing persisted; for tests. */
        fun inMemory(): UrlHealthStore = object : UrlHealthStore {
            private var saved: Map<String, HealthRecord> = emptyMap()
            override fun load() = saved
            override fun save(records: Map<String, HealthRecord>) { saved = records.toMap() }
            override fun clear() { saved = emptyMap() }
        }
    }
}

/**
 * A small tab-separated file in the app's private storage: one record per line,
 * `key<TAB>kind<TAB>strikes<TAB>lastFailureAt<TAB>suppressedUntil`. No Room table (the schema
 * stays where it is) and no reflection (nothing for R8 to strip). Written whole through a temp
 * file and a rename, so a crash mid-write leaves the previous version. Unreadable lines are
 * skipped; an unreadable file is an empty memory, which only costs a few extra visits.
 */
class FileUrlHealthStore(private val file: File) : UrlHealthStore {

    override fun load(): Map<String, HealthRecord> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            file.readLines().mapNotNull(::parse).toMap()
        }.onFailure { Timber.w("Crawl health file unreadable; starting empty") }.getOrDefault(emptyMap())
    }

    override fun save(records: Map<String, HealthRecord>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(records.entries.joinToString("") { (key, r) ->
                "$key\t${r.kind.name}\t${r.strikes}\t${r.lastFailureAt}\t${r.suppressedUntil}\n"
            })
            if (!tmp.renameTo(file)) {
                file.delete()
                check(tmp.renameTo(file)) { "rename failed" }
            }
        }.onFailure { Timber.w("Could not save the crawl health file: ${it.javaClass.simpleName}") }
    }

    override fun clear() {
        runCatching {
            file.delete()
            File(file.path + ".tmp").delete()
        }
    }

    private fun parse(line: String): Pair<String, HealthRecord>? {
        val parts = line.split('\t')
        if (parts.size != 5 || parts[0].isBlank() || parts[0].any { it == '\n' }) return null
        val kind = runCatching { HealthFailure.valueOf(parts[1]) }.getOrNull() ?: return null
        val strikes = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val last = parts[3].toLongOrNull() ?: return null
        val until = parts[4].toLongOrNull() ?: return null
        return parts[0] to HealthRecord(kind, strikes, last, until)
    }

    companion object {
        const val FILE_NAME = "crawl_health.tsv"
    }
}

/** What [CrawlListManager] asks before picking a URL (#353). */
interface UrlHealthGate {
    fun isSuppressed(url: String, domain: String): Boolean

    companion object {
        val NONE: UrlHealthGate = object : UrlHealthGate {
            override fun isSuppressed(url: String, domain: String) = false
        }
    }
}

/**
 * Runtime backoff for search engines that answer with a bot check (#353). Never touches the
 * user's engine settings: a held-back engine is skipped for a while, not disabled.
 */
interface SearchEngineHealth {
    fun isEngineHeldBack(engineId: String): Boolean
    fun recordEngineBotCheck(engineId: String)
    fun recordEngineSuccess(engineId: String)

    companion object {
        val NONE: SearchEngineHealth = object : SearchEngineHealth {
            override fun isEngineHeldBack(engineId: String) = false
            override fun recordEngineBotCheck(engineId: String) = Unit
            override fun recordEngineSuccess(engineId: String) = Unit
        }
    }
}

/**
 * On-device memory of crawl URLs that keep failing (#353), so the crawler stops revisiting them.
 * Rules in [UrlHealthPolicy]. Only built-in crawl-list visits are recorded today; nothing here is
 * specific to the built-in list, so custom entries can use it as well.
 *
 * Thread-safe. Loaded lazily on first use (from the engine's IO dispatcher), saved after every
 * change. The URLs it holds are the bundled corpus's own and never logged from here. Cleared by
 * Settings, "Clear all data".
 */
@Singleton
class UrlHealthTracker @Inject constructor(
    private val store: UrlHealthStore,
    private val clock: Clock,
) : UrlHealthGate, SearchEngineHealth {
    private val lock = Any()
    private var records: MutableMap<String, HealthRecord>? = null

    private fun loaded(): MutableMap<String, HealthRecord> =
        records ?: UrlHealthPolicy.prune(store.load(), clock.currentTimeMillis()).toMutableMap().also { records = it }

    override fun isSuppressed(url: String, domain: String): Boolean = synchronized(lock) {
        val now = clock.currentTimeMillis()
        val map = loaded()
        UrlHealthPolicy.isSuppressed(map[UrlHealthPolicy.urlKey(url)], now) ||
            UrlHealthPolicy.isSuppressed(map[UrlHealthPolicy.domainKey(domain)], now)
    }

    fun recordFailure(url: String, domain: String, failure: HealthFailure) {
        val (record, now) = strike(UrlHealthPolicy.keyFor(failure, url, domain), failure)
        // Kind and hold only; never the URL or the domain.
        if (record.suppressedUntil > now) {
            Timber.i("Crawl page held back (%s, strike %d, %d min)", failure, record.strikes, (record.suppressedUntil - now) / 60_000)
        }
    }

    fun recordSuccess(url: String, domain: String) =
        forget(UrlHealthPolicy.urlKey(url), UrlHealthPolicy.domainKey(domain))

    override fun isEngineHeldBack(engineId: String): Boolean = synchronized(lock) {
        UrlHealthPolicy.isSuppressed(loaded()[UrlHealthPolicy.engineKey(engineId)], clock.currentTimeMillis())
    }

    override fun recordEngineBotCheck(engineId: String) {
        val (record, now) = strike(UrlHealthPolicy.engineKey(engineId), HealthFailure.SEARCH_BOT_WALL)
        // The engine id is a fixed brand name; no query text ever reaches here.
        Timber.i(
            "Search engine held back (%s, strike %d, %d min)",
            engineId, record.strikes, (record.suppressedUntil - now) / 60_000,
        )
    }

    override fun recordEngineSuccess(engineId: String) = forget(UrlHealthPolicy.engineKey(engineId))

    private fun strike(key: String, failure: HealthFailure): Pair<HealthRecord, Long> = synchronized(lock) {
        val now = clock.currentTimeMillis()
        val map = loaded()
        val record = UrlHealthPolicy.onFailure(map[key], failure, now)
        map[key] = record
        val pruned = UrlHealthPolicy.prune(map, now)
        records = pruned.toMutableMap()
        store.save(pruned)
        record to now
    }

    private fun forget(vararg keys: String) = synchronized(lock) {
        val map = loaded()
        var removed = false
        for (key in keys) removed = (map.remove(key) != null) || removed
        if (removed) store.save(map.toMap())
    }

    /** Settings, "Clear all data". */
    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            records = mutableMapOf()
            store.clear()
        }
    }

    /** For tests and diagnostics. */
    internal fun snapshot(): Map<String, HealthRecord> = synchronized(lock) { loaded().toMap() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class UrlHealthModule {
    @Binds
    abstract fun bindUrlHealthGate(tracker: UrlHealthTracker): UrlHealthGate

    @Binds
    abstract fun bindSearchEngineHealth(tracker: UrlHealthTracker): SearchEngineHealth

    companion object {
        @Provides
        @Singleton
        fun provideUrlHealthStore(@ApplicationContext context: Context): UrlHealthStore =
            FileUrlHealthStore(File(context.filesDir, FileUrlHealthStore.FILE_NAME))
    }
}
