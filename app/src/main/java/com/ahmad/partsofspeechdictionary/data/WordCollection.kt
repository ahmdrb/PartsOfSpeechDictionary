package com.ahmad.partsofspeechdictionary.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "word_collections",
    indices = [Index(value = ["name"], unique = true)]
)
data class WordCollection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)
