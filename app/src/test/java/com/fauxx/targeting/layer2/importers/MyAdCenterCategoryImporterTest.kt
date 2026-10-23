package com.fauxx.targeting.layer2.importers

import android.content.Context
import android.content.res.AssetManager
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer2.CategoryMapper
import com.fauxx.targeting.layer2.PlatformProfileCache
import com.fauxx.targeting.layer2.PlatformProfileDao
import com.fauxx.targeting.layer2.ProfileSnapshot
import com.fauxx.targeting.layer2.ProfileSnapshotDao
import com.fauxx.targeting.layer2.SnapshotSeries
import com.fauxx.targeting.layer2.SnapshotSource
import com.fauxx.util.Clock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/** Categories pasted or typed from My Ad Center (#374): parsing, preview and what gets stored. */
class MyAdCenterCategoryImporterTest {

    private lateinit var dao: PlatformProfileDao
    private lateinit var snapshotDao: ProfileSnapshotDao
    private lateinit var importer: MyAdCenterCategoryImporter

    private val nowMs = 1_700_000_000_000L

    @Before
    fun setUp() {
        val assetManager: AssetManager = mockk {
            every { open("platform_category_map.json") } answers {
                ByteArrayInputStream("""{"Video Games": "GAMING", "Travel": "TRAVEL"}""".toByteArray())
            }
        }
        val context: Context = mockk { every { assets } returns assetManager }
        dao = mockk(relaxed = true)
        snapshotDao = mockk(relaxed = true)
        val clock: Clock = mockk { every { currentTimeMillis() } returns nowMs }
        importer = MyAdCenterCategoryImporter(CategoryMapper(context), dao, snapshotDao, clock)
    }

    @Test
    fun `lines split on newlines, commas and semicolons, with list markers stripped`() {
        val text = "- Video Games\r\n• Travel\n1. Cooking, Pets; 3D Printing\n\n  \n"
        assertEquals(
            listOf("Video Games", "Travel", "Cooking", "Pets", "3D Printing"),
            MyAdCenterCategoryImporter.parseLines(text)
        )
    }

    @Test
    fun `duplicates are dropped ignoring case, and long prose lines are skipped`() {
        val prose = "x".repeat(MyAdCenterCategoryImporter.MAX_LINE_LENGTH + 1)
        assertEquals(
            listOf("Travel"),
            MyAdCenterCategoryImporter.parseLines("Travel\ntravel\n$prose")
        )
    }

    @Test
    fun `a huge paste is capped`() {
        val text = (1..1000).joinToString("\n") { "Topic $it" }
        assertEquals(MyAdCenterCategoryImporter.MAX_LINES, MyAdCenterCategoryImporter.parseLines(text).size)
    }

    @Test
    fun `preview shows each line with its category, or none`() {
        val preview = importer.preview("Video Games\nSkin Care")
        assertEquals(PastedCategory("Video Games", CategoryPool.GAMING), preview[0])
        assertEquals("Skin Care", preview[1].text)
        assertNull(preview[1].category)
    }

    @Test
    fun `saving writes the google cache and a poisoned snapshot`() = runBlocking {
        val cacheSlot = slot<PlatformProfileCache>()
        val snapSlot = slot<ProfileSnapshot>()
        coEvery { dao.upsert(capture(cacheSlot)) } returns Unit
        coEvery { snapshotDao.insert(capture(snapSlot)) } returns 1L

        val result = importer.import("Video Games\nTravel\nSkin Care")

        assertEquals(ImportResult.Success(ImportSource.GOOGLE_MY_AD_CENTER, 2), result)
        assertEquals("google", cacheSlot.captured.platformName)
        assertEquals(nowMs, cacheSlot.captured.lastScraped)
        assertTrue(cacheSlot.captured.scrapedCategoriesJson.contains("GAMING"))
        assertTrue(cacheSlot.captured.scrapedCategoriesJson.contains("TRAVEL"))
        assertEquals(SnapshotSeries.POISONED, snapSlot.captured.series)
        assertEquals(SnapshotSource.IMPORT, snapSlot.captured.source)
    }

    @Test
    fun `a control save records a snapshot and never touches the targeting cache`() = runBlocking {
        val result = importer.import("Travel", SnapshotSeries.CONTROL)

        assertTrue(result is ImportResult.Success)
        coVerify(exactly = 0) { dao.upsert(any()) }
        coVerify(exactly = 1) { snapshotDao.insert(any()) }
    }

    @Test
    fun `nothing that matches saves nothing, so the previous import survives`() = runBlocking {
        val result = importer.import("Categories used to show you ads\nSkin Care")

        assertTrue("expected WrongFormat, got $result", result is ImportResult.WrongFormat)
        coVerify(exactly = 0) { dao.upsert(any()) }
        coVerify(exactly = 0) { snapshotDao.insert(any()) }
    }
}
