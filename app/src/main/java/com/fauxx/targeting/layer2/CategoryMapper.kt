package com.fauxx.targeting.layer2

import android.content.Context
import timber.log.Timber
import com.fauxx.data.querybank.CategoryPool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps raw ad platform category strings (e.g., "Video Games", "Software Development") to
 * [CategoryPool] enum values. Uses a JSON lookup table first, then falls back to fuzzy keyword
 * matching. Unknown strings are logged and skipped without crashing.
 */
@Singleton
class CategoryMapper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Raw platform string → CategoryPool name, loaded from assets. */
    private val exactMap: Map<String, String> by lazy { loadExactMap() }

    /**
     * Map a raw platform category string to a [CategoryPool] value.
     * Returns null and logs a warning if no match is found.
     */
    fun map(platformString: String): CategoryPool? {
        val normalized = platformString.trim()

        // 1. Exact match from JSON map
        val exactMatch = exactMap[normalized]
            ?: exactMap.entries.firstOrNull { it.key.equals(normalized, ignoreCase = true) }?.value
        if (exactMatch != null) {
            return runCatching { CategoryPool.valueOf(exactMatch) }.getOrNull()
        }

        // 2. Fuzzy matching against CategoryPool names, on whole words: "Carpets" is not PETS,
        // and a short fragment like "me" is not HOME_IMPROVEMENT.
        val lowerInput = normalized.lowercase()
        for (category in CategoryPool.values()) {
            val categoryWords = category.name.lowercase().replace("_", " ")
            if (wholeWords(categoryWords, plural = true).containsMatchIn(lowerInput) ||
                (lowerInput.length >= 4 && wholeWords(lowerInput, plural = false).containsMatchIn(categoryWords))
            ) {
                return category
            }
        }

        // 3. Keyword-based heuristics for common platform strings
        val heuristic = applyHeuristics(lowerInput)
        if (heuristic != null) return heuristic

        Timber.d("No CategoryPool match for platform string: '$platformString'")
        return null
    }

    /**
     * Map a collection of platform strings to a set of [CategoryPool] values.
     * Unmatched strings are skipped silently.
     */
    fun mapAll(platformStrings: Collection<String>): Set<CategoryPool> =
        platformStrings.mapNotNull { map(it) }.toSet()

    private fun applyHeuristics(lower: String): CategoryPool? {
        val words = lower.split(NON_LETTERS).filter { it.isNotEmpty() }
        fun has(vararg keywords: String) = keywords.any { matchesKeyword(lower, words, it) }
        return when {
            has("game", "gaming") -> CategoryPool.GAMING
            has("health", "medical", "doctor") -> CategoryPool.MEDICAL
            has("sport", "fitness", "exercise") -> CategoryPool.SPORTS
            has("travel", "vacation", "hotel") -> CategoryPool.TRAVEL
            has("food", "recipe", "restaurant") -> CategoryPool.FOOD
            has("tech", "software", "computer") -> CategoryPool.TECHNOLOGY
            has("fashion", "clothing", "apparel") -> CategoryPool.FASHION
            has("finance", "invest", "stock") -> CategoryPool.FINANCE
            has("parent", "baby", "child") -> CategoryPool.PARENTING
            has("auto", "car", "vehicle") -> CategoryPool.AUTOMOTIVE
            has("home", "garden", "house") -> CategoryPool.HOME_IMPROVEMENT
            has("music", "concert", "album") -> CategoryPool.MUSIC
            has("beauty", "cosmetic", "makeup") -> CategoryPool.BEAUTY
            has("cook", "baking", "kitchen") -> CategoryPool.COOKING
            has("real estate", "property", "mortgage") -> CategoryPool.REAL_ESTATE
            has("retire", "pension", "senior") -> CategoryPool.RETIREMENT
            has("legal", "lawyer", "attorney") -> CategoryPool.LEGAL
            has("farm", "agriculture", "crop") -> CategoryPool.AGRICULTURE
            has("educat", "academic", "college") -> CategoryPool.ACADEMIC
            has("pet", "dog", "cat") -> CategoryPool.PETS
            has("entertain", "movie", "film") -> CategoryPool.ENTERTAINMENT
            has("science", "research", "lab") -> CategoryPool.SCIENCE
            has("business", "entrepreneur", "startup") -> CategoryPool.BUSINESS
            has("hiking", "camping", "fishing", "outdoor", "hunting") -> CategoryPool.OUTDOOR_RECREATION
            has("craft", "knitting", "pottery", "quilt", "scrapbook") -> CategoryPool.CRAFTS
            has("history", "museum", "genealogy", "antique", "historical") -> CategoryPool.HISTORY
            has("climate", "sustainability", "conservation", "renewable", "environment") -> CategoryPool.ENVIRONMENT
            has("military", "veteran", "defense", "army", "navy") -> CategoryPool.MILITARY_DEFENSE
            has("meditation", "astrology", "crystal", "holistic", "essential oil") -> CategoryPool.WELLNESS_ALTERNATIVE
            has("dating", "relationship", "wedding", "marriage", "breakup") -> CategoryPool.RELATIONSHIPS_DATING
            else -> null
        }
    }

    /**
     * A keyword matches a whole word, its plural, or, for keywords of four letters or more, the
     * start of a word: "educat" finds "education" and "tech" finds "technology", but "car" does
     * not find "Skin Care" or "Careers", and "pet" does not find "Competitions". A keyword with a
     * space matches as a phrase.
     */
    private fun matchesKeyword(lower: String, words: List<String>, keyword: String): Boolean {
        if (' ' in keyword) return lower.contains(keyword)
        return words.any { word ->
            word == keyword || word == keyword + "s" || (keyword.length >= 4 && word.startsWith(keyword))
        }
    }

    /** [phrase] as whole words, optionally with a trailing "s" ("sport" finds "sports"). */
    private fun wholeWords(phrase: String, plural: Boolean): Regex =
        Regex("(?<![\\p{L}])" + Regex.escape(phrase) + (if (plural) "s?" else "") + "(?![\\p{L}])")

    private fun loadExactMap(): Map<String, String> {
        return try {
            val json = context.assets.open("platform_category_map.json")
                .bufferedReader().readText()
            val type = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load platform_category_map.json")
            emptyMap()
        }
    }

    private companion object {
        val NON_LETTERS = Regex("[^\\p{L}]+")
    }
}
