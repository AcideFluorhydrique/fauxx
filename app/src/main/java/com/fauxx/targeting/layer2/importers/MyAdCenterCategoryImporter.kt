package com.fauxx.targeting.layer2.importers

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer2.CategoryMapper
import com.fauxx.targeting.layer2.PlatformProfileCache
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer2.ProfileSnapshot
import com.fauxx.targeting.layer2.ProfileSnapshotDao
import com.fauxx.targeting.layer2.SnapshotSeries
import com.fauxx.targeting.layer2.SnapshotSource
import com.fauxx.util.Clock
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One line the user pasted, and the Fauxx category it maps to (null when nothing matches). */
data class PastedCategory(val text: String, val category: CategoryPool?)

/**
 * Layer 2 import from text the user copies out of Google's My Ad Center (Manage privacy →
 * "Categories used to show you ads") or types in (#374).
 *
 * Google documents no export of the categories it infers: Takeout and the Data Portability API
 * only carry My Ad Center *activity* (topics the user chose to see more or fewer ads about). The
 * My Ad Center page is the one place the full inferred list is shown, so this importer takes it
 * as text. The result is stored exactly like a Takeout import, under the "google" platform, so it
 * replaces the previous Google import and Layer 2 reads it with no other changes.
 */
@Singleton
class MyAdCenterCategoryImporter @Inject constructor(
    private val categoryMapper: CategoryMapper,
    private val platformProfileDao: PlatformProfileDao,
    private val profileSnapshotDao: ProfileSnapshotDao,
    private val clock: Clock
) {
    val source = ImportSource.GOOGLE_MY_AD_CENTER

    private val gson = Gson()

    /** Split [text] into categories and map each one, for the live preview in the entry dialog. */
    fun preview(text: String): List<PastedCategory> =
        parseLines(text).map { PastedCategory(it, categoryMapper.map(it)) }

    /**
     * Map [text] and store the result. Saves nothing when no line maps to a Fauxx category, since
     * an empty save would wipe the user's previous Google import over what is most likely a
     * paste of the wrong part of the page.
     */
    suspend fun import(text: String, series: SnapshotSeries = SnapshotSeries.POISONED): ImportResult =
        withContext(Dispatchers.IO) {
            val mapped = preview(text).mapNotNull { it.category }.toSet()
            if (mapped.isEmpty()) {
                return@withContext ImportResult.WrongFormat(
                    source,
                    com.fauxx.R.string.targeting_import_wrong_format_my_ad_center
                )
            }
            persistCategories(mapped, series)
            ImportResult.Success(source, mapped.size)
        }

    private suspend fun persistCategories(mapped: Set<CategoryPool>, series: SnapshotSeries) {
        val json = gson.toJson(mapped.map { it.name })
        val now = clock.currentTimeMillis()
        // The control series is measurement-only (#172): never touch the cache Layer 2 reads, so
        // a control import can't influence targeting. Only the poisoned import updates the cache.
        if (series == SnapshotSeries.POISONED) {
            platformProfileDao.upsert(
                PlatformProfileCache(
                    platformName = source.platformId,
                    scrapedCategoriesJson = json,
                    lastScraped = now
                )
            )
        }
        // Append an immutable IMPORT snapshot so Layer 2 can compute drift over time (#170 E1),
        // tagged with its experimental arm (#172 E3).
        profileSnapshotDao.insert(
            ProfileSnapshot(
                platformName = source.platformId,
                source = SnapshotSource.IMPORT,
                scrapedCategoriesJson = json,
                capturedAt = now,
                series = series,
            )
        )
    }

    companion object {
        /** Longer lines are prose from around the list (headings, help text), not categories. */
        internal const val MAX_LINE_LENGTH = 80

        /** Bounds the work a huge accidental paste can cause. */
        internal const val MAX_LINES = 200

        private val SEPARATORS = Regex("[\\n\\r\\t,;•·]+")
        private val LIST_MARKER = Regex("^(?:[-*–]+|\\d{1,3}[.)])\\s+")

        /**
         * One category per line, comma or semicolon, with list markers ("- ", "1. ") stripped,
         * blanks and over-long lines dropped, and duplicates removed ignoring case.
         */
        internal fun parseLines(text: String): List<String> =
            text.split(SEPARATORS)
                .asSequence()
                .map { it.trim().replace(LIST_MARKER, "").trim() }
                .filter { it.isNotEmpty() && it.length <= MAX_LINE_LENGTH }
                .distinctBy { it.lowercase() }
                .take(MAX_LINES)
                .toList()
    }
}
