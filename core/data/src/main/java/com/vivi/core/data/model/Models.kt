package com.vivi.core.data.model

import android.net.Uri

/** 视频来源：MediaStore 索引、直接文件遍历（需要所有文件访问权限）、SAF 授权目录、隐私空间。 */
enum class MediaSource { MEDIA_STORE, FILE_SYSTEM, SAF, VAULT }

/** 隐私空间的两种强度，用户自己选。 */
enum class VaultMode {
    /** 只在本应用内隐藏，文件仍在原路径（系统相册/其它文件管理器仍可见）。 */
    HIDDEN_ONLY,

    /** 移入应用私有目录并改名，其它应用读不到，代价是占用内部存储。 */
    RELOCATED,
}

data class VaultItem(
    val key: MediaKey,
    val mode: VaultMode,
    val currentPath: String,
    val originalPath: String?,
    /** 移入时记住的原名：磁盘上是哈希名，列表仍要显示用户认识的名字。 */
    val displayName: String,
    val addedAtMillis: Long,
)

/**
 * 稳定的媒体标识。
 *
 * MediaStore 的 `_id` 在重装/重新扫描后会变，路径在用户改名后也会变，所以 key 由来源 + 主键组成，
 * 进度、收藏、元数据都挂在 key 上；文件移动后按 [VideoMedia.path] 再迁移一次。
 */
@JvmInline
value class MediaKey(val value: String) {
    companion object {
        fun ofMediaStore(id: Long) = MediaKey("ms:$id")
        fun ofPath(path: String) = MediaKey("fs:${path.lowercase()}")
        fun ofUri(uri: Uri) = MediaKey("sf:${uri.toString().lowercase()}")
    }
}

data class VideoMedia(
    val key: MediaKey,
    val uri: Uri,
    val displayName: String,
    val path: String?,
    val folderPath: String?,
    val durationMicros: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateAddedMillis: Long,
    val lastModifiedMillis: Long,
    val source: MediaSource,
) {
    val extension: String get() = displayName.substringAfterLast('.', "").lowercase()

    val isLongForm: Boolean get() = durationMicros >= MIN_CLIP_MICROS

    val resolutionLabel: String
        get() = if (width <= 0 || height <= 0) "" else {
            val short = minOf(width, height)
            when {
                short >= 2160 -> "4K"
                short >= 1440 -> "2K"
                short >= 1080 -> "1080p"
                short >= 720 -> "720p"
                short >= 480 -> "480p"
                else -> "${short}p"
            }
        }

    companion object {
        /** 低于 20 秒的短视频默认不进媒体库，避免把微信/相机小片段混进来。 */
        const val MIN_CLIP_MICROS = 20_000_000L
    }
}

data class FolderSummary(
    val path: String,
    val name: String,
    val videoCount: Int,
    val totalBytes: Long,
    val coverKey: MediaKey?,
    val lastPlayedMillis: Long,
    val isHidden: Boolean,
)

data class PlaybackProgress(
    val key: MediaKey,
    val positionMicros: Long,
    val durationMicros: Long,
    val updatedAtMillis: Long,
    val completed: Boolean,
) {
    val fraction: Float
        get() = if (durationMicros <= 0) 0f else (positionMicros.toFloat() / durationMicros).coerceIn(0f, 1f)
}

/** 上次在这条视频上用的音轨/字幕/倍速，下次打开直接恢复。 */
data class TrackPreferences(
    val audioStreamIndex: Int?,
    val subtitleStreamIndex: Int?,
    val speed: Float,
)

data class VideoMetadata(
    val key: MediaKey,
    val container: String,
    val videoCodec: String?,
    val audioCodec: String?,
    val bitRate: Long,
    val frameRate: Double?,
    val bitDepth: Int?,
    val hasEmbeddedSubtitles: Boolean,
    val audioTrackCount: Int,
    val subtitleTrackCount: Int,
    val probedAtMillis: Long,
)

enum class SortOrder(val label: String) {
    NAME_ASC("名称 A→Z"),
    NAME_DESC("名称 Z→A"),
    DATE_ADDED_DESC("最近添加"),
    DATE_ADDED_ASC("最早添加"),
    DURATION_DESC("时长 长→短"),
    SIZE_DESC("大小 大→小"),
}

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
