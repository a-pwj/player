package com.vivi.core.data.repository

import com.vivi.core.data.db.FavoriteEntity
import com.vivi.core.data.db.MetadataDao
import com.vivi.core.data.db.PlaybackDao
import com.vivi.core.data.db.PlaybackStateEntity
import com.vivi.core.data.db.VideoMetadataEntity
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.PlaybackProgress
import com.vivi.core.data.model.TrackPreferences
import com.vivi.core.data.model.VideoMedia
import com.vivi.core.data.model.VideoMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class ResumableItem(val media: VideoMedia, val progress: PlaybackProgress)

/**
 * 进度、收藏、探测缓存。
 *
 * 写入要节流：播放器每秒都在推进位置，直接落库会把主线程 IO 打满，
 * 所以调用方（PlayerViewModel）负责每若干秒聚合一次，这里只保证「最后一次写入是新的」。
 * 完成判定用比例而不是剩余秒数，短视频和 10 小时长的电影要同一套规则。
 */
class PlaybackRepository(
    private val playbackDao: PlaybackDao,
    private val favoriteDao: com.vivi.core.data.db.FavoriteDao,
    private val metadataDao: MetadataDao,
    private val videoRepository: VideoRepository,
) {

    suspend fun saveProgress(
        key: MediaKey,
        positionMicros: Long,
        durationMicros: Long,
        preferences: TrackPreferences = TrackPreferences(null, null, 1f),
    ) {
        val completed = durationMicros > 0 &&
            positionMicros >= durationMicros - COMPLETE_MARGIN_MICROS &&
            positionMicros.toFloat() / durationMicros >= COMPLETE_FRACTION
        playbackDao.upsert(
            PlaybackStateEntity(
                mediaKey = key.value,
                positionMicros = if (completed) 0 else positionMicros,
                durationMicros = durationMicros,
                updatedAtMillis = System.currentTimeMillis(),
                completed = completed,
                audioStreamIndex = preferences.audioStreamIndex,
                subtitleStreamIndex = preferences.subtitleStreamIndex,
                speed = preferences.speed,
            )
        )
    }

    fun observeProgress(key: MediaKey): Flow<PlaybackProgress?> =
        playbackDao.observe(key.value).map { it?.toModel() }

    /** 全量进度，媒体库用它给每张卡片画进度条。 */
    fun observeAllProgress(): Flow<Map<MediaKey, PlaybackProgress>> =
        playbackDao.observeAll().map { rows -> rows.associate { MediaKey(it.mediaKey) to it.toModel() } }

    suspend fun progressOf(key: MediaKey): PlaybackProgress? =
        playbackDao.observeOneOnce(key.value)?.toModel()

    /** 恢复位置：太靠头的直接从头放，太靠尾的当作已看完。 */
    suspend fun resumePositionFor(key: MediaKey, skipUnderSec: Int): Long? {
        val state = playbackDao.observeOneOnce(key.value) ?: return null
        if (state.completed) return null
        val thresholdMicros = skipUnderSec * 1_000_000L
        if (state.positionMicros in 1 until thresholdMicros) return null
        val duration = state.durationMicros
        if (duration > 0 && duration - state.positionMicros < thresholdMicros) return null
        return state.positionMicros
    }

    /**
     * 「继续观看」要和媒体库条目流一起 combine。
     *
     * 只监听进度表的话，冷启动时文件扫描（MediaStore 之外的目录）还没跑完，
     * resolveKeys 解析不到任何条目，这一段就永久是空的 —— 之后进度不变化，
     * Room 不会再推一次，用户看到的就是「明明看过，却没有继续观看」。
     */
    fun observeContinueWatching(limit: Int = 20): Flow<List<ResumableItem>> =
        combine(playbackDao.observeResumable(limit * 3), videoRepository.observeItems()) { rows, items ->
            rows.mapNotNull { row ->
                val media = items.resolveMediaKey(MediaKey(row.mediaKey)) ?: return@mapNotNull null
                ResumableItem(media, row.toModel())
            }.take(limit)
        }

    fun observeFavoriteKeys(): Flow<Set<MediaKey>> =
        favoriteDao.observeKeys().map { keys -> keys.map(::MediaKey).toSet() }

    suspend fun toggleFavorite(key: MediaKey): Boolean {
        val existing = favoriteDao.getKeys().contains(key.value)
        return if (existing) {
            favoriteDao.remove(key.value)
            false
        } else {
            favoriteDao.add(FavoriteEntity(key.value, System.currentTimeMillis()))
            true
        }
    }

    suspend fun saveMetadata(key: MediaKey, metadata: VideoMetadata) {
        metadataDao.upsert(
            VideoMetadataEntity(
                mediaKey = key.value,
                container = metadata.container,
                videoCodec = metadata.videoCodec,
                audioCodec = metadata.audioCodec,
                bitRate = metadata.bitRate,
                frameRate = metadata.frameRate,
                bitDepth = metadata.bitDepth,
                hasEmbeddedSubtitles = metadata.hasEmbeddedSubtitles,
                audioTrackCount = metadata.audioTrackCount,
                subtitleTrackCount = metadata.subtitleTrackCount,
                probedAtMillis = metadata.probedAtMillis,
            )
        )
    }

    fun observeMetadata(key: MediaKey): Flow<VideoMetadata?> =
        metadataDao.observe(key.value).map { it?.toModel() }

    suspend fun metadataOf(key: MediaKey): VideoMetadata? = metadataDao.get(key.value)?.toModel()

    suspend fun clearHistory() {
        playbackDao.getAll().forEach { playbackDao.delete(it.mediaKey) }
    }

    suspend fun deleteFor(keys: List<MediaKey>) {
        playbackDao.deleteAll(keys.map { it.value })
    }

    private fun PlaybackStateEntity.toModel() = PlaybackProgress(
        key = MediaKey(mediaKey),
        positionMicros = positionMicros,
        durationMicros = durationMicros,
        updatedAtMillis = updatedAtMillis,
        completed = completed,
    )

    private fun VideoMetadataEntity.toModel() = VideoMetadata(
        key = MediaKey(mediaKey),
        container = container,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        bitRate = bitRate,
        frameRate = frameRate,
        bitDepth = bitDepth,
        hasEmbeddedSubtitles = hasEmbeddedSubtitles,
        audioTrackCount = audioTrackCount,
        subtitleTrackCount = subtitleTrackCount,
        probedAtMillis = probedAtMillis,
    )

    private companion object {
        /** 距离结尾 15 秒内视为看完。 */
        const val COMPLETE_MARGIN_MICROS = 15_000_000L
        const val COMPLETE_FRACTION = 0.99f
    }
}
