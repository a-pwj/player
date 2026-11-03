package com.vivi.core.data.repository

import com.vivi.core.data.db.PlaylistDao
import com.vivi.core.data.db.PlaylistEntity
import com.vivi.core.data.db.PlaylistItemDao
import com.vivi.core.data.db.PlaylistItemEntity
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.VideoMedia
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine

/**
 * 本地播放列表：只存 media key + 顺序，不复制文件。
 *
 * 视频被移走或删除后，条目自然从列表里消失（resolveKeys 解析不到就跳过），
 * 所以不需要额外的「失效标记」维护。
 */
class PlaylistRepository(
    private val playlists: PlaylistDao,
    private val playlistItems: PlaylistItemDao,
    /** key -> 条目；文件被移走时解析不到，列表里自然跳过。 */
    private val resolveKeys: suspend (List<MediaKey>) -> List<VideoMedia>,
) {

    data class Playlist(
        val id: String,
        val name: String,
        val itemCount: Int,
        val createdAtMillis: Long,
    )

    val observePlaylists: Flow<List<Playlist>> =
        combine(playlists.observeAll(), playlistItems.observeAll()) { entities, rows ->
            val counts = rows.groupingBy { it.playlistId }.eachCount()
            entities.map { Playlist(it.id, it.name, counts[it.id] ?: 0, it.createdAtMillis) }
        }

    fun observeEntries(playlistId: String): Flow<List<VideoMedia>> =
        playlistItems.observeFor(playlistId).map { rows ->
            resolveKeys(rows.map { MediaKey(it.mediaKey) })
        }

    suspend fun entriesOnce(playlistId: String): List<VideoMedia> =
        resolveKeys(playlistIdsOf(playlistId))

    private suspend fun playlistIdsOf(playlistId: String): List<MediaKey> =
        playlistItems.observeFor(playlistId).first().map { MediaKey(it.mediaKey) }

    suspend fun create(name: String): String {
        val id = UUID.randomUUID().toString()
        playlists.put(
            PlaylistEntity(
                id = id,
                name = name.ifBlank { "未命名列表" },
                createdAtMillis = System.currentTimeMillis(),
            ),
        )
        return id
    }

    suspend fun rename(id: String, name: String) {
        if (name.isNotBlank()) playlists.rename(id, name.trim())
    }

    /** 删列表只删关联关系，视频文件本身不动。 */
    suspend fun delete(id: String) {
        playlistItems.clear(id)
        playlists.delete(id)
    }

    suspend fun add(playlistId: String, key: MediaKey) {
        val next = (playlistItems.maxPosition(playlistId) ?: -1) + 1
        playlistItems.add(
            PlaylistItemEntity(
                playlistId = playlistId,
                mediaKey = key.value,
                position = next,
                addedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun remove(playlistId: String, key: MediaKey) =
        playlistItems.remove(playlistId, key.value)

    suspend fun reorder(playlistId: String, keysInOrder: List<MediaKey>) {
        keysInOrder.forEachIndexed { index, key ->
            playlistItems.setPosition(playlistId, key.value, index)
        }
    }
}
