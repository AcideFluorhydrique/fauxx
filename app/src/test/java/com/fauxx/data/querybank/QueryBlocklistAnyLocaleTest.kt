package com.fauxx.data.querybank

import android.content.Context
import android.content.res.AssetManager
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * [QueryBlocklist.isBlockedInAnyLocale], the gate for user-added search terms (#328): a term typed
 * in a language other than the noise language must still meet that language's reviewed list.
 * Runs against the real shipped `harmful_queries/<tag>.json` files.
 */
class QueryBlocklistAnyLocaleTest {

    private val assetsRoot = File("src/main/assets")

    private fun blocklist(active: SupportedLocale, missing: Set<String> = emptySet()): QueryBlocklist {
        val assets: AssetManager = mockk()
        every { assets.open(any()) } answers {
            val path = firstArg<String>()
            if (path in missing) throw IOException("missing $path")
            val file = File(assetsRoot, path)
            if (!file.exists()) throw IOException("missing $path")
            FileInputStream(file)
        }
        val context: Context = mockk { every { this@mockk.assets } returns assets }
        val localeManager: LocaleManager = mockk(relaxed = true) { every { currentLocale } returns active }
        return QueryBlocklist(context, localeManager)
    }

    @Test
    fun `a Russian crisis phrase slips past the English list but not the every-language gate`() {
        val bl = blocklist(SupportedLocale.EN)
        val phrase = "телефон доверия при суицидальных мыслях"
        assertFalse("precondition: the EN list alone does not know this phrase", bl.isBlocked(phrase))
        assertTrue(bl.isBlockedInAnyLocale(phrase))
    }

    @Test
    fun `a phrase from every shipped list is caught whatever the active locale`() {
        val samples = mapOf(
            SupportedLocale.EN to "suicide hotline",
            SupportedLocale.RU to "телефон доверия при суицидальных мыслях",
        )
        for (active in SupportedLocale.entries) {
            val bl = blocklist(active)
            samples.values.forEach { assertTrue("$it with active=$active", bl.isBlockedInAnyLocale(it)) }
        }
    }

    @Test
    fun `an ordinary local search passes`() {
        val bl = blocklist(SupportedLocale.FR)
        assertFalse(bl.isBlockedInAnyLocale("Wetter Leipzig morgen"))
        assertFalse(bl.isBlockedInAnyLocale("horaires piscine municipale"))
    }

    @Test
    fun `a locale whose list fails to load fails the gate closed`() {
        val bl = blocklist(SupportedLocale.EN, missing = setOf("harmful_queries/es.json"))
        assertFalse("the active list still works", bl.isBlocked("horaires piscine municipale"))
        assertTrue(bl.isBlockedInAnyLocale("horaires piscine municipale"))
    }
}
