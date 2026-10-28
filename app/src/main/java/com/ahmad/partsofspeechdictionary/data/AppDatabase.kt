package com.ahmad.partsofspeechdictionary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DictionaryEntry::class,
        WordCollection::class,
        EntryCollectionCrossRef::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dictionary_entries ADD COLUMN synonyms TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE dictionary_entries ADD COLUMN antonyms TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE dictionary_entries ADD COLUMN collocations TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE dictionary_entries ADD COLUMN origin TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dictionary_entries ADD COLUMN translation TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS word_collections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_word_collections_name
                    ON word_collections(name)
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS entry_collection_cross_ref (
                        entryId INTEGER NOT NULL,
                        collectionId INTEGER NOT NULL,
                        PRIMARY KEY(entryId, collectionId),
                        FOREIGN KEY(entryId) REFERENCES dictionary_entries(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(collectionId) REFERENCES word_collections(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_entry_collection_cross_ref_collectionId
                    ON entry_collection_cross_ref(collectionId)
                """.trimIndent())
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "parts_of_speech_dictionary.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { INSTANCE = it }
        }
    }
}
