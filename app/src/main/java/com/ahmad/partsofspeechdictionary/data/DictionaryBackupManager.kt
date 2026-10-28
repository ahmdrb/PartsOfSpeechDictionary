package com.ahmad.partsofspeechdictionary.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

class DictionaryBackupManager(private val db: AppDatabase) {
    private val dao = db.dictionaryDao()

    companion object {
        private const val FORMAT = "wordwise-backup"
        private const val VERSION = 1
    }

    enum class ImportMode { MERGE, REPLACE }

    suspend fun exportJson(): String {
        val entries = dao.getAllEntriesForBackup()
        val collections = dao.getAllCollectionsForBackup()
        val memberships = dao.getAllMembershipsForBackup()

        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("entries", JSONArray().apply {
                entries.forEach { e ->
                    put(JSONObject()
                        .put("backupId", e.id)
                        .put("word", e.word)
                        .put("definitions", e.definitions)
                        .put("examples", e.examples)
                        .put("pronunciation", e.pronunciation)
                        .put("categories", e.categories)
                        .put("source", e.source)
                        .put("notes", e.notes)
                        .put("synonyms", e.synonyms)
                        .put("antonyms", e.antonyms)
                        .put("collocations", e.collocations)
                        .put("origin", e.origin)
                        .put("translation", e.translation)
                        .put("updatedAt", e.updatedAt))
                }
            })
            .put("collections", JSONArray().apply {
                collections.forEach { c ->
                    put(JSONObject()
                        .put("backupId", c.id)
                        .put("name", c.name))
                }
            })
            .put("memberships", JSONArray().apply {
                memberships.forEach { m ->
                    put(JSONObject()
                        .put("entryId", m.entryId)
                        .put("collectionId", m.collectionId))
                }
            })

        return root.toString(2)
    }

    suspend fun importJson(json: String, mode: ImportMode) {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) {
            "This file is not a Wordwise backup."
        }
        require(root.optInt("version", -1) == VERSION) {
            "Unsupported backup version."
        }

        val entryArray = root.getJSONArray("entries")
        val collectionArray = root.getJSONArray("collections")
        val membershipArray = root.getJSONArray("memberships")

        data class EntryData(
            val word: String,
            val definitions: String,
            val examples: String,
            val pronunciation: String,
            val categories: String,
            val source: String,
            val notes: String,
            val synonyms: String,
            val antonyms: String,
            val collocations: String,
            val origin: String,
            val translation: String,
            val updatedAt: Long
        )

        data class CollectionData(val backupId: Long, val name: String)
        data class MembershipData(val entryId: Long, val collectionId: Long)

        val incomingEntries = (0 until entryArray.length()).map { i ->
            val o = entryArray.getJSONObject(i)
            val word = o.getString("word").trim()
            require(word.isNotBlank()) { "Backup contains a blank word." }
            EntryData(
                word = word,
                definitions = o.getString("definitions"),
                examples = o.getString("examples"),
                pronunciation = o.getString("pronunciation"),
                categories = o.getString("categories"),
                source = o.getString("source"),
                notes = o.getString("notes"),
                synonyms = o.getString("synonyms"),
                antonyms = o.getString("antonyms"),
                collocations = o.getString("collocations"),
                origin = o.getString("origin"),
                translation = o.getString("translation"),
                updatedAt = o.getLong("updatedAt")
            )
        }

        val incomingCollections = (0 until collectionArray.length()).map { i ->
            val o = collectionArray.getJSONObject(i)
            val name = o.getString("name").trim()
            val backupId = o.getLong("backupId")
            require(name.isNotBlank()) { "Backup contains a blank collection name." }
            require(backupId > 0) { "Backup contains an invalid collection ID." }
            CollectionData(backupId, name)
        }

        require(incomingEntries.map { it.word.lowercase() }.distinct().size == incomingEntries.size) {
            "Backup contains duplicate words ignoring case."
        }
        require(incomingCollections.map { it.name.lowercase() }.distinct().size == incomingCollections.size) {
            "Backup contains duplicate collection names ignoring case."
        }

        require(incomingCollections.map { it.backupId }.distinct().size == incomingCollections.size) {
            "Backup contains duplicate collection IDs."
        }

        val incomingMemberships = (0 until membershipArray.length()).map { i ->
            val o = membershipArray.getJSONObject(i)
            MembershipData(o.getLong("entryId"), o.getLong("collectionId"))
        }

        val validEntryIds = incomingMemberships.map { it.entryId }.toSet()
        val collectionIds = incomingCollections.map { it.backupId }.toSet()
        require(incomingMemberships.all { it.entryId > 0 && it.collectionId in collectionIds }) {
            "Backup contains a membership pointing to an unknown collection."
        }
        require(incomingMemberships.map { it.entryId to it.collectionId }.distinct().size == incomingMemberships.size) {
            "Backup contains duplicate memberships."
        }

        // Every entry ID referenced by a membership must exist in this backup.
        val entryIdArray = root.getJSONArray("entries")
        val hasEntryIds = (0 until entryIdArray.length()).all { i ->
            entryIdArray.getJSONObject(i).has("backupId")
        }
        val anyEntryHasId = (0 until entryIdArray.length()).any { i ->
            entryIdArray.getJSONObject(i).has("backupId")
        }
        require(!anyEntryHasId || hasEntryIds) {
            "Backup contains incomplete entry IDs."
        }

        val backupEntryIdList = if (hasEntryIds) {
            (0 until entryIdArray.length()).map { i ->
                entryIdArray.getJSONObject(i).getLong("backupId")
            }
        } else {
            emptyList()
        }
        require(backupEntryIdList.all { it > 0L }) {
            "Backup contains an invalid entry ID."
        }
        require(backupEntryIdList.distinct().size == backupEntryIdList.size) {
            "Backup contains duplicate entry IDs."
        }
        val backupEntryIds = backupEntryIdList.toSet()
        if (incomingMemberships.isNotEmpty()) {
            require(hasEntryIds) {
                "Backup is missing entry IDs needed to restore collection memberships."
            }
            require(incomingMemberships.all { it.entryId in backupEntryIds }) {
                "Backup contains a membership pointing to an unknown word."
            }
        }

        db.withTransaction {
            if (mode == ImportMode.REPLACE) {
                dao.clearAllMembershipsForBackup()
                dao.clearAllEntriesForBackup()
                dao.clearAllCollectionsForBackup()
            }

            val existingEntries = dao.getAllEntriesForBackup().associateBy {
                it.word.trim().lowercase()
            }
            val entryIdMap = mutableMapOf<Long, Long>()

            incomingEntries.forEachIndexed { index, data ->
                val backupId = if (hasEntryIds) entryIdArray.getJSONObject(index).getLong("backupId") else -1L
                val existing = if (mode == ImportMode.MERGE) existingEntries[data.word.lowercase()] else null
                val localId = if (existing != null) {
                    // Merge preserves the existing word and all its fields.
                    existing.id
                } else {
                    dao.save(
                        DictionaryEntry(
                            word = data.word,
                            definitions = data.definitions,
                            examples = data.examples,
                            pronunciation = data.pronunciation,
                            categories = data.categories,
                            source = data.source,
                            notes = data.notes,
                            synonyms = data.synonyms,
                            antonyms = data.antonyms,
                            collocations = data.collocations,
                            origin = data.origin,
                            translation = data.translation,
                            updatedAt = data.updatedAt
                        )
                    )
                }
                if (backupId > 0) entryIdMap[backupId] = localId
            }

            val existingCollections = dao.getAllCollectionsForBackup().associateBy {
                it.name.trim().lowercase()
            }
            val collectionIdMap = mutableMapOf<Long, Long>()

            incomingCollections.forEach { data ->
                val existing = if (mode == ImportMode.MERGE) {
                    existingCollections[data.name.lowercase()]
                } else null
                val newId = if (existing != null) {
                    existing.id
                } else {
                    dao.insertCollection(WordCollection(name = data.name))
                }
                require(newId > 0) {
                    "Could not import collection '${data.name}'. A collection with that name may already exist."
                }
                collectionIdMap[data.backupId] = newId
            }

            incomingMemberships.forEach { membership ->
                val entryId = entryIdMap[membership.entryId]
                    ?: throw IllegalArgumentException("Could not map a backup word to a local word.")
                val collectionId = collectionIdMap[membership.collectionId]
                    ?: throw IllegalArgumentException("Could not map a backup collection.")
                dao.addEntryToCollection(
                    EntryCollectionCrossRef(entryId = entryId, collectionId = collectionId)
                )
            }
        }
    }
}
