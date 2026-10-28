package com.ahmad.partsofspeechdictionary.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryDao {

    @Query("SELECT * FROM dictionary_entries")
    suspend fun getAllEntriesForBackup(): List<DictionaryEntry>

    @Query("SELECT * FROM word_collections")
    suspend fun getAllCollectionsForBackup(): List<WordCollection>

    @Query("SELECT * FROM entry_collection_cross_ref")
    suspend fun getAllMembershipsForBackup(): List<EntryCollectionCrossRef>

    @Query("DELETE FROM entry_collection_cross_ref")
    suspend fun clearAllMembershipsForBackup()

    @Query("DELETE FROM dictionary_entries")
    suspend fun clearAllEntriesForBackup()

    @Query("DELETE FROM word_collections")
    suspend fun clearAllCollectionsForBackup()

    @Query("SELECT * FROM dictionary_entries ORDER BY word COLLATE NOCASE")
    fun observeAll(): Flow<List<DictionaryEntry>>

    @Query("""
        SELECT * FROM dictionary_entries
        WHERE word LIKE '%' || :query || '%'
           OR definitions LIKE '%' || :query || '%'
        ORDER BY word COLLATE NOCASE
    """)
    fun search(query: String): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DictionaryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entry: DictionaryEntry): Long

    @Query("DELETE FROM dictionary_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM word_collections ORDER BY name COLLATE NOCASE")
    fun observeCollections(): Flow<List<WordCollection>>

    @Query("""
        SELECT wc.id, wc.name, COUNT(DISTINCT x.entryId) AS wordCount
        FROM word_collections wc
        LEFT JOIN entry_collection_cross_ref x
            ON wc.id = x.collectionId
        GROUP BY wc.id, wc.name
        ORDER BY wc.name COLLATE NOCASE
    """)
    fun observeCollectionsWithCounts(): Flow<List<CollectionWithCount>>

    @Query("SELECT COUNT(*) FROM dictionary_entries")
    fun observeEntryCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollection(collection: WordCollection): Long

    @Update
    suspend fun updateCollection(collection: WordCollection)

    @Query("DELETE FROM word_collections WHERE id = :id")
    suspend fun deleteCollection(id: Long)

    @Query("""
        SELECT wc.* FROM word_collections wc
        INNER JOIN entry_collection_cross_ref x
        ON wc.id = x.collectionId
        WHERE x.entryId = :entryId
        ORDER BY wc.name COLLATE NOCASE
    """)
    fun observeCollectionsForEntry(entryId: Long): Flow<List<WordCollection>>

    @Query("""
        SELECT * FROM entry_collection_cross_ref
        WHERE entryId = :entryId
    """)
    suspend fun getMemberships(entryId: Long): List<EntryCollectionCrossRef>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addEntryToCollection(ref: EntryCollectionCrossRef)

    @Query("""
        DELETE FROM entry_collection_cross_ref
        WHERE entryId = :entryId AND collectionId = :collectionId
    """)
    suspend fun removeEntryFromCollection(entryId: Long, collectionId: Long)

    @Query("""
        SELECT de.* FROM dictionary_entries de
        INNER JOIN entry_collection_cross_ref x ON de.id = x.entryId
        WHERE x.collectionId = :collectionId
        ORDER BY de.word COLLATE NOCASE
    """)
    fun observeEntriesInCollection(collectionId: Long): Flow<List<DictionaryEntry>>
}
