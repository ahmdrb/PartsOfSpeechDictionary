package com.ahmad.partsofspeechdictionary.data

import kotlinx.coroutines.flow.Flow

class DictionaryRepository(private val dao: DictionaryDao) {
    fun entries(query: String): Flow<List<DictionaryEntry>> =
        if (query.isBlank()) dao.observeAll() else dao.search(query)

    suspend fun save(entry: DictionaryEntry) = dao.save(
        entry.copy(word = entry.word.trim(), updatedAt = System.currentTimeMillis())
    )

    suspend fun findByWord(word: String): DictionaryEntry? =
        dao.getByWord(word.trim())

    suspend fun delete(id: Long) = dao.deleteById(id)

    fun collections(): Flow<List<WordCollection>> = dao.observeCollections()

    fun collectionsWithCounts(): Flow<List<CollectionWithCount>> =
        dao.observeCollectionsWithCounts()

    fun entryCount(): Flow<Int> = dao.observeEntryCount()

    fun collectionsForEntry(entryId: Long): Flow<List<WordCollection>> =
        dao.observeCollectionsForEntry(entryId)

    fun entriesInCollection(collectionId: Long): Flow<List<DictionaryEntry>> =
        dao.observeEntriesInCollection(collectionId)

    suspend fun createCollection(name: String): Long {
        val cleaned = name.trim()
        require(cleaned.isNotBlank()) { "Collection name cannot be empty." }
        return dao.insertCollection(WordCollection(name = cleaned))
    }

    suspend fun renameCollection(id: Long, name: String) {
        val cleaned = name.trim()
        require(cleaned.isNotBlank()) { "Collection name cannot be empty." }
        dao.updateCollection(WordCollection(id = id, name = cleaned))
    }

    suspend fun deleteCollection(id: Long) = dao.deleteCollection(id)

    suspend fun addEntryToCollection(entryId: Long, collectionId: Long) =
        dao.addEntryToCollection(
            EntryCollectionCrossRef(entryId = entryId, collectionId = collectionId)
        )

    suspend fun removeEntryFromCollection(entryId: Long, collectionId: Long) =
        dao.removeEntryFromCollection(entryId, collectionId)

    suspend fun memberships(entryId: Long): List<EntryCollectionCrossRef> =
        dao.getMemberships(entryId)
}
