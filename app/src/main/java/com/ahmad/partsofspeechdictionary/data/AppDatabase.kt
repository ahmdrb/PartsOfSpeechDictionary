package com.ahmad.partsofspeechdictionary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DictionaryEntry::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao

    companion object {
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dictionary_entries ADD COLUMN synonyms TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE dictionary_entries ADD COLUMN antonyms TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE dictionary_entries ADD COLUMN collocations TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE dictionary_entries ADD COLUMN origin TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "parts_of_speech_dictionary.db"
            ).addMigrations(MIGRATION_1_2)
                .build().also { INSTANCE = it }
        }
    }
}
