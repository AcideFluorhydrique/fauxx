package com.fauxx.data.custompool

import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.querybank.QueryBlocklist
import com.fauxx.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own searches and sites (#328): add, remove and list for the Settings screen, and a
 * synchronous [CustomPoolSource] snapshot for the engine.
 *
 * **Relation to categories and personas.** Built-in entries are filed under a
 * [com.fauxx.data.querybank.CategoryPool]
 * and the dispatcher weights categories by layer (persona interests, self-report, imported ad
 * profile). User entries are not categorised: asking the user to file each local news site under
 * one of thirty categories is friction for no real gain, and guessing a category from a URL or a
 * search in an arbitrary language would be wrong often enough to skew the weights. They are an
 * independent source instead: a fixed, size-scaled share of search and crawl actions draws from
 * them ([CustomPoolMixer]) whatever the persona or the dispatched category is, and the rest of the
 * actions follow the normal category-weighted path. The action keeps the dispatcher's category in
 * the log, and the search detail is tagged `[CUSTOM]` so the user can tell which ones came from
 * their list.
 *
 * **Safety.** Searches pass [QueryBlocklist.isBlockedInAnyLocale] (every reviewed language's
 * crisis and harmful-query list, not just the active one) and sites pass [DomainBlocklist] plus the
 * scheme, credential and local-address rules in [CustomPoolValidator], at add time here and again
 * at dispatch time in the engine.
 *
 * **Privacy.** Entries live in the SQLCipher database, never reach the debug log (only counts are
 * logged), are wiped by "Clear all data", and are deliberately NOT carried by LAN sync: sync
 * moves decoy personas between the user's devices, and a list of the user's own local sites is
 * real personal data that also need not suit the other device's language.
 */
@Singleton
class CustomPoolRepository @Inject constructor(
    private val dao: CustomPoolDao,
    private val queryBlocklist: QueryBlocklist,
    private val domainBlocklist: DomainBlocklist,
    private val clock: Clock,
) : CustomPoolSource {

    private data class Snapshot(val searches: List<String>, val sites: List<String>)

    private val snapshot = AtomicReference(Snapshot(emptyList(), emptyList()))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises add() so two quick pastes cannot both pass the capacity check. */
    private val writeLock = Mutex()

    init {
        // Same pattern as PoisonProfileRepository: keep a cached copy so the engine's synchronous
        // selection path never touches the database. Empty until the first read completes.
        scope.launch {
            try {
                dao.observeAll().collect { all ->
                    snapshot.set(
                        Snapshot(
                            searches = all.filter { it.kind == CustomPoolKind.SEARCH }.map { it.value },
                            sites = all.filter { it.kind == CustomPoolKind.SITE }.map { it.value },
                        )
                    )
                }
            } catch (e: Exception) {
                // Counts only: entries are user data and stay out of the log.
                Timber.w("Custom pool unavailable: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Every entry, newest first, for the Settings list. */
    val entries: Flow<List<CustomPoolEntry>> get() = dao.observeAll()

    override fun searches(): List<String> = snapshot.get().searches

    override fun sites(): List<String> = snapshot.get().sites

    /**
     * Validates [text] (one entry, or a bulk paste with one entry per line) and stores what passes.
     * Returns what was added and why the rest was not.
     */
    suspend fun add(kind: CustomPoolKind, text: String): CustomPoolAddPlan = withContext(Dispatchers.IO) {
        // Off the caller's thread: the first check against another locale's blocklist reads an asset.
        writeLock.withLock { addLocked(kind, text) }
    }

    private suspend fun addLocked(kind: CustomPoolKind, text: String): CustomPoolAddPlan {
        val existing = dao.getByKind(kind).map { it.value }
        val capacity = (CustomPoolValidator.MAX_ENTRIES_PER_KIND - existing.size).coerceAtLeast(0)
        val plan = CustomPoolValidator.planAdd(
            lines = CustomPoolValidator.splitLines(text),
            existing = existing,
            capacity = capacity,
            validate = validatorFor(kind),
        )
        if (plan.accepted.isEmpty()) return plan

        val now = clock.currentTimeMillis()
        val ids = dao.insertAll(plan.accepted.map { CustomPoolEntry(kind = kind, value = it, addedAt = now) })
        // The unique (kind, value) index can still skip a row (a case-only variant of an existing
        // value is caught above; this covers anything else). Report those as duplicates.
        val skipped = ids.count { it == -1L }
        val result = if (skipped == 0) {
            plan
        } else {
            val rejections = LinkedHashMap(plan.rejections)
            rejections[CustomPoolRejection.DUPLICATE] = (rejections[CustomPoolRejection.DUPLICATE] ?: 0) + skipped
            CustomPoolAddPlan(plan.accepted.filterIndexed { i, _ -> ids[i] != -1L }, rejections)
        }
        Timber.i("Custom pool: added ${result.accepted.size} ${kind.name.lowercase()} entries, rejected ${result.rejectedCount}")
        return result
    }

    suspend fun remove(id: Long) = dao.delete(id)

    suspend fun clear(kind: CustomPoolKind) = dao.deleteKind(kind)

    /** Part of "Clear all data". */
    suspend fun clearAll() = dao.deleteAll()

    private fun validatorFor(kind: CustomPoolKind): (String) -> CustomPoolValidation = when (kind) {
        CustomPoolKind.SEARCH -> { line -> CustomPoolValidator.validateQuery(line, queryBlocklist::isBlockedInAnyLocale) }
        CustomPoolKind.SITE -> { line -> CustomPoolValidator.validateUrl(line, domainBlocklist::isUrlBlocked) }
    }
}
