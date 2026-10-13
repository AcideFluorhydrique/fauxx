package com.fauxx.data.custompool

import com.fauxx.data.crawllist.DomainBlocklist
import com.fauxx.data.querybank.QueryBlocklist
import com.fauxx.support.FakeClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Add/remove and the add-time safety gates of [CustomPoolRepository] (#328), over an in-memory DAO. */
class CustomPoolRepositoryTest {

    private class FakeDao : CustomPoolDao {
        val rows = MutableStateFlow<List<CustomPoolEntry>>(emptyList())
        private var nextId = 1L

        override suspend fun insertAll(entries: List<CustomPoolEntry>): List<Long> = entries.map { e ->
            if (rows.value.any { it.kind == e.kind && it.value == e.value }) {
                -1L
            } else {
                val id = nextId++
                rows.value = rows.value + e.copy(id = id)
                id
            }
        }

        override fun observeAll(): Flow<List<CustomPoolEntry>> = rows
        override suspend fun getByKind(kind: CustomPoolKind) = rows.value.filter { it.kind == kind }
        override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
        override suspend fun deleteKind(kind: CustomPoolKind) { rows.value = rows.value.filterNot { it.kind == kind } }
        override suspend fun deleteAll() { rows.value = emptyList() }
    }

    private val dao = FakeDao()
    private val queryBlocklist: QueryBlocklist = mockk {
        every { isBlockedInAnyLocale(any()) } answers { firstArg<String>().contains("crisis") }
    }
    private val domainBlocklist: DomainBlocklist = mockk {
        every { isUrlBlocked(any()) } answers { firstArg<String>().contains("blocked.example.com") }
    }
    private val repo = CustomPoolRepository(dao, queryBlocklist, domainBlocklist, FakeClock(42L))

    @Test
    fun `a bulk paste of sites stores the valid ones, normalised, with the add time`() = runBlocking {
        val plan = repo.add(CustomPoolKind.SITE, "lokal.example.de\nhttps://blocked.example.com/x\nftp://x.example.com\n\nlokal.example.de")
        assertEquals(listOf("https://lokal.example.de/"), plan.accepted)
        assertEquals(1, plan.rejections[CustomPoolRejection.BLOCKED])
        assertEquals(1, plan.rejections[CustomPoolRejection.NOT_A_WEB_ADDRESS])
        assertEquals(1, plan.rejections[CustomPoolRejection.DUPLICATE])
        val stored = dao.rows.value.single()
        assertEquals(CustomPoolKind.SITE, stored.kind)
        assertEquals("https://lokal.example.de/", stored.value)
        assertEquals(42L, stored.addedAt)
    }

    @Test
    fun `searches go through the every-language query blocklist`() = runBlocking {
        val plan = repo.add(CustomPoolKind.SEARCH, "Wetter morgen\ncrisis line number")
        assertEquals(listOf("Wetter morgen"), plan.accepted)
        assertEquals(1, plan.rejections[CustomPoolRejection.BLOCKED])
        verify { queryBlocklist.isBlockedInAnyLocale("crisis line number") }
    }

    @Test
    fun `re-adding an existing entry is a duplicate, and kinds are independent`() = runBlocking {
        repo.add(CustomPoolKind.SEARCH, "example.com")
        val again = repo.add(CustomPoolKind.SEARCH, "EXAMPLE.COM")
        assertTrue(again.accepted.isEmpty())
        assertEquals(1, again.rejections[CustomPoolRejection.DUPLICATE])
        // The same text as a site is a different list.
        assertEquals(listOf("https://example.com/"), repo.add(CustomPoolKind.SITE, "example.com").accepted)
    }

    @Test
    fun `the per-kind cap holds across adds`() = runBlocking {
        val full = (1..CustomPoolValidator.MAX_ENTRIES_PER_KIND).joinToString("\n") { "term $it" }
        assertEquals(CustomPoolValidator.MAX_ENTRIES_PER_KIND, repo.add(CustomPoolKind.SEARCH, full).accepted.size)
        val over = repo.add(CustomPoolKind.SEARCH, "one more")
        assertTrue(over.accepted.isEmpty())
        assertEquals(1, over.rejections[CustomPoolRejection.LIMIT_REACHED])
    }

    @Test
    fun `remove, clear and clearAll delete what they say`() = runBlocking {
        repo.add(CustomPoolKind.SEARCH, "a\nb")
        repo.add(CustomPoolKind.SITE, "a.example.com\nb.example.com")
        repo.remove(dao.rows.value.first { it.value == "a" }.id)
        assertEquals(listOf("b"), dao.rows.value.filter { it.kind == CustomPoolKind.SEARCH }.map { it.value })
        repo.clear(CustomPoolKind.SITE)
        assertTrue(dao.rows.value.none { it.kind == CustomPoolKind.SITE })
        repo.clearAll()
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `the engine's synchronous snapshot follows the database`() = runBlocking {
        repo.add(CustomPoolKind.SEARCH, "Wetter morgen")
        repo.add(CustomPoolKind.SITE, "lokal.example.de")
        withTimeout(5_000) {
            while (repo.searches() != listOf("Wetter morgen") || repo.sites() != listOf("https://lokal.example.de/")) {
                delay(10)
            }
        }
    }
}
