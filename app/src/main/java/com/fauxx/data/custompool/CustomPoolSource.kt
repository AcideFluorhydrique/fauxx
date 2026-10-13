package com.fauxx.data.custompool

import kotlin.random.Random

/**
 * Synchronous, read-only view of the user's own searches and sites (#328) for the engine. An
 * interface so [com.fauxx.data.crawllist.CrawlListManager] and the search module do not depend on
 * Room, and tests can hand them a fixed list.
 */
interface CustomPoolSource {

    /** The user's search terms, already validated. Empty when none were added. */
    fun searches(): List<String>

    /** The user's website URLs, already validated and normalised. Empty when none were added. */
    fun sites(): List<String>

    companion object {
        /** No custom entries. The default for tests and for any caller built without the pool. */
        val EMPTY: CustomPoolSource = object : CustomPoolSource {
            override fun searches(): List<String> = emptyList()
            override fun sites(): List<String> = emptyList()
        }

        /** A fixed pool, for tests. */
        fun of(searches: List<String> = emptyList(), sites: List<String> = emptyList()): CustomPoolSource =
            object : CustomPoolSource {
                override fun searches(): List<String> = searches
                override fun sites(): List<String> = sites
            }
    }
}

/**
 * How often a search or crawl action draws from the user's own pool instead of the built-in one
 * (#328).
 *
 * The share grows with the number of entries, [SHARE_PER_ENTRY] each, up to [MAX_SHARE]:
 *
 * - 30% at most, so the built-in, reviewed, per-locale corpus always carries the majority of the
 *   noise. The user's list is short and personal (often the sites they really read), so letting it
 *   dominate would shrink the noise toward one small cluster of domains and topics, which is both
 *   easy to separate from the rest of the traffic and closer to the user's real profile than decoy
 *   noise should be. 30% is still enough for local sites and searches to give the profile a
 *   visible regional and language signal, which is the point of the feature.
 * - Scaled per entry, so one or two entries are not hammered. With a single site at a flat 30%,
 *   Fauxx would visit that one site on roughly a third of its crawl actions, tens of times an hour
 *   at the higher intensities: a pattern no reader produces, and unfriendly to a small local
 *   site. At 5% per entry, one site gets 5% of crawl actions and the full 30% is reached from six
 *   entries, where the per-site share is again about 5%.
 *
 * Custom entries are not categorised: they are drawn independently of the persona's interests
 * and of the dispatcher's category, see [CustomPoolRepository].
 */
object CustomPoolMixer {

    /** Share each entry adds. */
    const val SHARE_PER_ENTRY = 0.05f

    /** Upper bound on the custom share, reached at [MAX_SHARE] / [SHARE_PER_ENTRY] = 6 entries. */
    const val MAX_SHARE = 0.30f

    /** Fraction of actions that should draw from a pool of [entryCount] custom entries. */
    fun share(entryCount: Int): Float =
        if (entryCount <= 0) 0f else minOf(MAX_SHARE, SHARE_PER_ENTRY * entryCount)

    /**
     * Whether this action draws from the custom pool. Consumes no randomness when the pool is
     * empty, so installs without custom entries behave exactly as before (and seeded tests keep
     * their draws).
     */
    fun drawCustom(entryCount: Int, random: Random): Boolean {
        val share = share(entryCount)
        if (share <= 0f) return false
        return random.nextFloat() < share
    }
}
