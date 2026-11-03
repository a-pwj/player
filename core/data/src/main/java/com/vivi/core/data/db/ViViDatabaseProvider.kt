package com.vivi.core.data.db

import android.content.Context
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库单例。app 层通过 AppContainer 拿它，而不是到处 builder。
 */
object ViViDatabaseProvider {

    @Volatile
    private var instance: ViViDatabase? = null

    /** 播放列表是两张新表，建表就够；用破坏式迁移会把观看进度和收藏一起清掉。 */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlists` (" +
                    "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playlist_items` (" +
                    "`playlist_id` TEXT NOT NULL, `media_key` TEXT NOT NULL, " +
                    "`position` INTEGER NOT NULL, `added_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`playlist_id`, `media_key`))",
            )
        }
    }

    fun get(context: Context): ViViDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            ViViDatabase::class.java,
            "viviv.db",
        ).addMigrations(MIGRATION_1_2).fallbackToDestructiveMigration().build().also { instance = it }
    }

    fun close() {
        instance?.close()
        instance = null
    }
}
