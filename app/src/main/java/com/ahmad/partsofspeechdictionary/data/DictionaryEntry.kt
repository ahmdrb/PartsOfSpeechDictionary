package com.ahmad.partsofspeechdictionary.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dictionary_entries")
data class DictionaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val definitions: String = "",
    val examples: String = "",
    val pronunciation: String = "",
    val categories: String = "",
    val source: String = "User entry",
    val notes: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
