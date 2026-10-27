package com.ahmad.partsofspeechdictionary.data

import kotlinx.coroutines.flow.Flow

class DictionaryRepository(private val dao: DictionaryDao) {
    fun entries(query: String): Flow<List<DictionaryEntry>> =
        if (query.isBlank()) dao.observeAll() else dao.search(query)

    suspend fun save(entry: DictionaryEntry) = dao.save(
        entry.copy(word = entry.word.trim(), updatedAt = System.currentTimeMillis())
    )

    suspend fun delete(id: Long) = dao.deleteById(id)
}
