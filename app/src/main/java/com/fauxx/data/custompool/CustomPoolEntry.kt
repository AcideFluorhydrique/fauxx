package com.fauxx.data.custompool

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Which half of the user's own noise pool an entry belongs to (#328). */
enum class CustomPoolKind {
    /** A search term, dispatched through the search module like a built-in query. */
    SEARCH,

    /** A website URL, visited by the crawl modules like a built-in crawl-list entry. */
    SITE,
}

/**
 * One entry the user added to their own noise pool (#328): a search term or a website, typically
 * local news sites and searches in their own language, which the built-in per-locale corpora
 * cannot cover for every country.
 *
 * These are user data (they say where the user lives and what they read), so they live in the
 * SQLCipher-encrypted database rather than the plain DataStore, are never written to the debug
 * log, and are deleted by "Clear all data". [value] is stored already validated and normalised by
 * [CustomPoolValidator]; the engine re-checks the safety gates at dispatch time anyway.
 *
 * The unique index on (kind, value) makes a bulk paste with repeats, or a re-paste of the same
 * list, converge instead of piling up duplicates.
 */
@Entity(
    tableName = "custom_pool_entries",
    indices = [Index(value = ["kind", "value"], unique = true)]
)
data class CustomPoolEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: CustomPoolKind,
    val value: String,
    val addedAt: Long,
)

/** DAO for the user's own searches and sites (#328). */
@Dao
interface CustomPoolDao {

    /** Inserts [entries], skipping any (kind, value) already stored. Returns -1 for each skipped row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<CustomPoolEntry>): List<Long>

    @Query("SELECT * FROM custom_pool_entries ORDER BY addedAt DESC, id DESC")
    fun observeAll(): Flow<List<CustomPoolEntry>>

    @Query("SELECT * FROM custom_pool_entries WHERE kind = :kind ORDER BY addedAt DESC, id DESC")
    suspend fun getByKind(kind: CustomPoolKind): List<CustomPoolEntry>

    @Query("DELETE FROM custom_pool_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM custom_pool_entries WHERE kind = :kind")
    suspend fun deleteKind(kind: CustomPoolKind)

    @Query("DELETE FROM custom_pool_entries")
    suspend fun deleteAll()
}
