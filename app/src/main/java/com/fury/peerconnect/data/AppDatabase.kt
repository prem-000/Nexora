package com.fury.peerconnect.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PeerEntity::class, MessageEntity::class, AlertEntity::class], version = 9, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao
    abstract fun alertDao(): AlertDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alerts ADD COLUMN alertId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE alerts ADD COLUMN attachmentPath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN alertId TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN nextHop TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE peers ADD COLUMN hopDistance INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE peers ADD COLUMN isReachable INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN deliveryStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE alerts ADD COLUMN originPeerId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE alerts ADD COLUMN isCustodyActive INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE alerts ADD COLUMN expiresAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN messageType TEXT NOT NULL DEFAULT 'TEXT'")
                db.execSQL("ALTER TABLE messages ADD COLUMN fileName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN localPath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN fileSize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN transferStatus TEXT NOT NULL DEFAULT 'SUCCESS'")
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alerts ADD COLUMN message TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN senderMessageId TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE alerts ADD COLUMN latitude REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE alerts ADD COLUMN longitude REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE alerts ADD COLUMN accuracy REAL DEFAULT NULL")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "peer_connect_db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

