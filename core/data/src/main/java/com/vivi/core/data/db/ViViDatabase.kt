package com.vivi.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @ColumnInfo(name = "media_key") @PrimaryKey val mediaKey: String,
    @ColumnInfo(name = "position_micros") val positionMicros: Long,
    @ColumnInfo(name = "duration_micros") val durationMicros: Long,
    @ColumnInfo(name = "updated_at") val updatedAtMillis: Long,
    val completed: Boolean,
    @ColumnInfo(name = "audio_stream") val audioStreamIndex: Int?,
    @ColumnInfo(name = "subtitle_stream") val subtitleStreamIndex: Int?,
    val speed: Float,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @ColumnInfo(name = "media_key") @PrimaryKey val mediaKey: String,
    @ColumnInfo(name = "added_at") val addedAtMillis: Long,
)

@Entity(tableName = "video_metadata")
data class VideoMetadataEntity(
    @ColumnInfo(name = "media_key") @PrimaryKey val mediaKey: String,
    val container: String,
    @ColumnInfo(name = "video_codec") val videoCodec: String?,
    @ColumnInfo(name = "audio_codec") val audioCodec: String?,
    @ColumnInfo(name = "bit_rate") val bitRate: Long,
    @ColumnInfo(name = "frame_rate") val frameRate: Double?,
    @ColumnInfo(name = "bit_depth") val bitDepth: Int?,
    @ColumnInfo(name = "has_embedded_subs") val hasEmbeddedSubtitles: Boolean,
    @ColumnInfo(name = "audio_tracks") val audioTrackCount: Int,
    @ColumnInfo(name = "subtitle_tracks") val subtitleTrackCount: Int,
    @ColumnInfo(name = "probed_at") val probedAtMillis: Long,
)

@Entity(tableName = "folder_settings")
data class FolderSettingsEntity(
    @PrimaryKey val path: String,
    val hidden: Boolean = false,
    val locked: Boolean = false,
    @ColumnInfo(name = "pin_hash") val pinHash: String? = null,
)

/** 文件改名或移动后，把旧 key 上的进度搬到新 key，避免「进度丢失」。 */
@Entity(tableName = "media_aliases")
data class MediaAliasEntity(
    @ColumnInfo(name = "old_key") @PrimaryKey val oldKey: String,
    @ColumnInfo(name = "new_key") val newKey: String,
    @ColumnInfo(name = "moved_at") val movedAtMillis: Long,
)

@Dao
interface PlaybackDao {

    @Upsert
    suspend fun upsert(state: PlaybackStateEntity)

    @Query("SELECT * FROM playback_state WHERE media_key = :key")
    suspend fun observeOneOnce(key: String): PlaybackStateEntity?

    @Query("SELECT * FROM playback_state WHERE media_key = :key")
    fun observe(key: String): Flow<PlaybackStateEntity?>

    @Query("SELECT * FROM playback_state WHERE completed = 0 ORDER BY updated_at DESC LIMIT :limit")
    fun observeResumable(limit: Int): Flow<List<PlaybackStateEntity>>

    @Query("SELECT * FROM playback_state ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<PlaybackStateEntity>>

    @Query("SELECT * FROM playback_state")
    suspend fun getAll(): List<PlaybackStateEntity>

    @Query("DELETE FROM playback_state WHERE media_key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM playback_state WHERE media_key IN (:keys)")
    suspend fun deleteAll(keys: List<String>)

    @Query("UPDATE playback_state SET media_key = :newKey WHERE media_key = :oldKey")
    suspend fun rekey(oldKey: String, newKey: String): Int

    @Query("SELECT position_micros FROM playback_state WHERE media_key = :key")
    suspend fun positionOf(key: String): Long?
}

@Dao
interface FavoriteDao {

    @Upsert
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE media_key = :key")
    suspend fun remove(key: String)

    @Query("SELECT media_key FROM favorites")
    fun observeKeys(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE media_key = :key)")
    fun observeIsFavorite(key: String): Flow<Boolean>

    @Query("SELECT media_key FROM favorites")
    suspend fun getKeys(): List<String>
}

@Dao
interface MetadataDao {

    @Upsert
    suspend fun upsert(entity: VideoMetadataEntity)

    @Query("SELECT * FROM video_metadata WHERE media_key = :key")
    fun observe(key: String): Flow<VideoMetadataEntity?>

    @Query("SELECT * FROM video_metadata WHERE media_key = :key")
    suspend fun get(key: String): VideoMetadataEntity?

    @Query("DELETE FROM video_metadata WHERE media_key = :key")
    suspend fun delete(key: String)
}

@Dao
interface FolderSettingsDao {

    @Upsert
    suspend fun upsert(entity: FolderSettingsEntity)

    @Query("SELECT * FROM folder_settings")
    fun observeAll(): Flow<List<FolderSettingsEntity>>

    @Query("SELECT * FROM folder_settings WHERE path = :path")
    suspend fun get(path: String): FolderSettingsEntity?

    @Query("DELETE FROM folder_settings WHERE path = :path")
    suspend fun delete(path: String)
}

@Dao
interface AliasDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(alias: MediaAliasEntity)

    @Query("SELECT new_key FROM media_aliases WHERE old_key = :oldKey")
    suspend fun resolve(oldKey: String): String?
}

@Entity(tableName = "vault_items")
data class VaultItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "media_key") val mediaKey: String,
    /** HIDDEN_ONLY / RELOCATED，决定移出时的恢复方式。 */
    val mode: String,
    @ColumnInfo(name = "current_path") val currentPath: String,
    @ColumnInfo(name = "original_path") val originalPath: String?,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "duration_micros") val durationMicros: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "added_at") val addedAtMillis: Long,
)

@Dao
interface VaultDao {

    @Upsert
    suspend fun put(item: VaultItemEntity)

    @Query("SELECT * FROM vault_items ORDER BY added_at DESC")
    fun observeAll(): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_items ORDER BY added_at DESC")
    suspend fun getAll(): List<VaultItemEntity>

    @Query("SELECT media_key FROM vault_items")
    fun observeKeys(): Flow<List<String>>

    @Query("SELECT * FROM vault_items WHERE media_key = :key LIMIT 1")
    suspend fun getByKey(key: String): VaultItemEntity?

    @Query("DELETE FROM vault_items WHERE media_key = :key")
    suspend fun remove(key: String)
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
)

/** 列表内顺序由 position 决定；同一条视频在同一列表里只会出现一次。 */
@Entity(tableName = "playlist_items", primaryKeys = ["playlist_id", "media_key"])
data class PlaylistItemEntity(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "media_key") val mediaKey: String,
    val position: Int,
    @ColumnInfo(name = "added_at") val addedAtMillis: Long,
)

@Dao
interface PlaylistDao {

    @Upsert
    suspend fun put(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists ORDER BY created_at ASC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: String): PlaylistEntity?

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PlaylistItemDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(item: PlaylistItemEntity): Long

    @Query("SELECT * FROM playlist_items WHERE playlist_id = :playlistId ORDER BY position ASC")
    fun observeFor(playlistId: String): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items")
    fun observeAll(): Flow<List<PlaylistItemEntity>>

    @Query("SELECT MAX(position) FROM playlist_items WHERE playlist_id = :playlistId")
    suspend fun maxPosition(playlistId: String): Int?

    @Query("DELETE FROM playlist_items WHERE playlist_id = :playlistId AND media_key = :key")
    suspend fun remove(playlistId: String, key: String)

    @Query("DELETE FROM playlist_items WHERE playlist_id = :playlistId")
    suspend fun clear(playlistId: String)

    @Query("UPDATE playlist_items SET position = :position WHERE playlist_id = :playlistId AND media_key = :key")
    suspend fun setPosition(playlistId: String, key: String, position: Int)
}

@Database(
    entities = [
        PlaybackStateEntity::class,
        FavoriteEntity::class,
        VideoMetadataEntity::class,
        FolderSettingsEntity::class,
        MediaAliasEntity::class,
        VaultItemEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class ViViDatabase : RoomDatabase() {
    abstract fun playbackDao(): PlaybackDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun metadataDao(): MetadataDao
    abstract fun folderSettingsDao(): FolderSettingsDao
    abstract fun aliasDao(): AliasDao
    abstract fun vaultDao(): VaultDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistItemDao(): PlaylistItemDao
}
