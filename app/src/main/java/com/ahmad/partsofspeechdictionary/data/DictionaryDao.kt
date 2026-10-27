package com.ahmad.partsofspeechdictionary.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM dictionary_entries ORDER BY word COLLATE NOCASE")
    fun observeAll(): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary_entries WHERE word LIKE '%' || :query || '%' OR definitions LIKE '%' || :query || '%' ORDER BY word COLLATE NOCASE")
    fun search(query: String): Flow<List<DictionaryEntry>>

    @Query("SELECT * FROM dictionary_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DictionaryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entry: DictionaryEntry): Long

    @Query("DELETE FROM dictionary_entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}
