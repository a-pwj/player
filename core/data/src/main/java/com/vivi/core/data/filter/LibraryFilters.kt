package com.vivi.core.data.filter

import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.PlaybackProgress
import com.vivi.core.data.model.SortOrder
import com.vivi.core.data.model.VideoMedia
import java.io.File
import java.util.Locale

enum class MediaCategory(val label: String) {
    ALL("全部"),
    MOVIE("电影"),
    TV_SERIES("剧集"),
    ANIME("动画"),
    DOCUMENTARY("纪录片"),
    SHORT_CLIP("短视频"),
    ADULT_EDU("教程"),
    OTHER("其他"),
}

enum class ResolutionBand(val label: String, val minShortEdge: Int, val maxShortEdge: Int) {
    // 区间要和 VideoMedia.resolutionLabel 一致，否则同一条视频在卡片和筛选面板里会被归到不同档
    SD("标清", 0, 719),
    HD("高清 720p", 720, 1079),
    FULL_HD("全高清 1080p", 1080, 1439),
    TWO_K("2K", 1440, 2159),
    FOUR_K("4K", 2160, Int.MAX_VALUE),
    UNKNOWN("未知", -1, -1),
}

enum class DurationBand(val label: String, val minMicros: Long, val maxMicros: Long) {
    CLIP("片段 < 10 分钟", 0, 10 * 60_000_000L),
    SHORT("10–40 分钟", 10 * 60_000_000L, 40 * 60_000_000L),
    EPISODE("40–90 分钟", 40 * 60_000_000L, 90 * 60_000_000L),
    FEATURE("90 分钟 – 3 小时", 90 * 60_000_000L, 180 * 60_000_000L),
    LONG("长片 > 3 小时", 180 * 60_000_000L, Long.MAX_VALUE),
}

enum class SizeBand(val label: String, val minBytes: Long, val maxBytes: Long) {
    SMALL("小于 200MB", 0, 200L * 1024 * 1024),
    MEDIUM("200MB – 1GB", 200L * 1024 * 1024, 1024L * 1024 * 1024),
    LARGE("1GB – 4GB", 1024L * 1024 * 1024, 4L * 1024 * 1024 * 1024),
    HUGE("大于 4GB", 4L * 1024 * 1024 * 1024, Long.MAX_VALUE),
}

enum class WatchState { ALL, UNFINISHED, FINISHED, NEVER_PLAYED }

/** 多维筛选条件。空集合表示「不限」，UI 直接绑定，不需要额外的 boolean 开关。 */
data class LibraryFilter(
    val search: String = "",
    val sort: SortOrder = SortOrder.DATE_ADDED_DESC,
    val category: MediaCategory = MediaCategory.ALL,
    val folderPath: String? = null,
    val volume: String? = null,
    val resolutions: Set<ResolutionBand> = emptySet(),
    val durations: Set<DurationBand> = emptySet(),
    val sizes: Set<SizeBand> = emptySet(),
    val containers: Set<String> = emptySet(),
    val watchState: WatchState = WatchState.ALL,
    val favoritesOnly: Boolean = false,
    val minDurationMicros: Long = VideoMedia.MIN_CLIP_MICROS,
) {
    val isActive: Boolean
        get() = search.isNotBlank() || category != MediaCategory.ALL || folderPath != null ||
            volume != null || resolutions.isNotEmpty() || durations.isNotEmpty() ||
            sizes.isNotEmpty() || containers.isNotEmpty() || watchState != WatchState.ALL ||
            favoritesOnly
}

/**
 * 视频归类。
 *
 * 本地播放器没有元数据服务可问，只能靠启发式：目录名关键词 + 文件名里的 SxxEyy + 时长。
 * 规则宁可粗也不要错得离谱，所以每种判断都要求「证据明确」，否则落到 OTHER。
 */
object VideoClassifier {

    private val EPISODE_PATTERN = Regex("""(?i)[\[\s(]?s\d{1,2}[\s._-]?e\d{1,3}""")
    private val SEASON_EPISODE = Regex("""(?i)(season|第\s*\d+\s*季|第\s*\d+\s*集)""")
    private val MOVIE_HINTS = listOf("movie", "电影", "影片")
    private val TV_HINTS = listOf("tv", "series", "剧集", "电视剧", "连续剧", "季")
    private val ANIME_HINTS = listOf("anime", "动画", "动漫", "番剧", "oaav", "ova")
    private val DOC_HINTS = listOf("doc", "纪录", "纪录片", "地理", "发现", "national geographic")
    private val EDU_HINTS = listOf("course", "tutorial", "教程", "课程", "培训", "讲座", "网课", "精品课")

    fun classify(media: VideoMedia): MediaCategory {
        val haystack = ((media.folderPath ?: "") + " " + media.displayName).lowercase(Locale.ROOT)
        val minutes = media.durationMicros / 60_000_000.0

        when {
            ANIME_HINTS.any { haystack.contains(it) } -> return MediaCategory.ANIME
            DOC_HINTS.any { haystack.contains(it) } -> return MediaCategory.DOCUMENTARY
            EDU_HINTS.any { haystack.contains(it) } -> return MediaCategory.ADULT_EDU
            MOVIE_HINTS.any { haystack.contains(it) } && minutes >= 60 -> return MediaCategory.MOVIE
            TV_HINTS.any { haystack.contains(it) } -> return MediaCategory.TV_SERIES
        }
        if (EPISODE_PATTERN.containsMatchIn(media.displayName) || SEASON_EPISODE.containsMatchIn(haystack)) {
            return MediaCategory.TV_SERIES
        }
        if (media.durationMicros in 1 until DurationBand.CLIP.maxMicros) return MediaCategory.SHORT_CLIP
        if (minutes >= 75) return MediaCategory.MOVIE
        return MediaCategory.OTHER
    }

    fun resolutionBand(media: VideoMedia): ResolutionBand {
        val shortEdge = if (media.width <= 0 || media.height <= 0) return ResolutionBand.UNKNOWN
        else minOf(media.width, media.height)
        return ResolutionBand.entries.firstOrNull { shortEdge in it.minShortEdge..it.maxShortEdge }
            ?: ResolutionBand.UNKNOWN
    }

    fun durationBand(media: VideoMedia): DurationBand {
        if (media.durationMicros <= 0) return DurationBand.CLIP
        return DurationBand.entries.firstOrNull { media.durationMicros in it.minMicros..<it.maxMicros }
            ?: DurationBand.LONG
    }

    fun sizeBand(media: VideoMedia): SizeBand =
        SizeBand.entries.firstOrNull { media.sizeBytes in it.minBytes..<it.maxBytes } ?: SizeBand.SMALL

    fun volumeOf(media: VideoMedia): String? = media.path?.substringBeforeLast('/')?.substringBefore("/storage/")
        ?.let { if (it.contains("/storage/")) "/storage/${it.substringAfter("/storage/")}" else "/storage" }
}

/** 把筛选条件应用到列表；进度与收藏是外部状态，这里只做组合。 */
object LibraryFilterEngine {

    fun apply(
        items: List<VideoMedia>,
        filter: LibraryFilter,
        favorites: Set<MediaKey>,
        progress: Map<MediaKey, PlaybackProgress>,
        categories: Map<MediaKey, MediaCategory> = emptyMap(),
    ): List<VideoMedia> = items.asSequence()
        .filter { it.durationMicros == 0L || it.durationMicros >= filter.minDurationMicros }
        .filter { filter.folderPath == null || it.folderPath == filter.folderPath }
        .filter { filter.volume == null || it.path?.startsWith(filter.volume) == true }
        .filter { filter.search.isBlank() || it.displayName.contains(filter.search, ignoreCase = true) }
        .filter { filter.category == MediaCategory.ALL ||
            (categories[it.key] ?: VideoClassifier.classify(it)) == filter.category }
        .filter { filter.resolutions.isEmpty() ||
            VideoClassifier.resolutionBand(it) in filter.resolutions }
        .filter { filter.durations.isEmpty() || VideoClassifier.durationBand(it) in filter.durations }
        .filter { filter.sizes.isEmpty() || VideoClassifier.sizeBand(it) in filter.sizes }
        .filter { filter.containers.isEmpty() || it.extension in filter.containers }
        .filter { !filter.favoritesOnly || it.key in favorites }
        .filter { media ->
            val state = progress[media.key]
            when (filter.watchState) {
                WatchState.ALL -> true
                WatchState.NEVER_PLAYED -> state == null
                WatchState.FINISHED -> state?.completed == true
                WatchState.UNFINISHED -> state != null && !state.completed
            }
        }
        .sortedWith(comparator(filter.sort))
        .toList()

    /** 每个维度当前可选项与命中数量，用于筛选面板显示计数。 */
    fun facets(items: List<VideoMedia>, filter: LibraryFilter, categories: Map<MediaKey, MediaCategory>) =
        Facets(
            categories = MediaCategory.entries.associateWith { category ->
                // 「全部」是总数而不是某个分类，否则筛选条上永远显示 0
                if (category == MediaCategory.ALL) items.size
                else items.count { (categories[it.key] ?: VideoClassifier.classify(it)) == category }
            },
            resolutions = ResolutionBand.entries.associateWith { band ->
                items.count { VideoClassifier.resolutionBand(it) == band }
            },
            durations = DurationBand.entries.associateWith { band ->
                items.count { VideoClassifier.durationBand(it) == band }
            },
            containers = items.groupingBy { it.extension }.eachCount(),
            volumes = items.mapNotNull { it.volumeRoot() }.distinct().sorted(),
            folders = items.mapNotNull { it.folderPath }.distinct().sortedBy { File(it).name.lowercase() },
        )

    private fun comparator(sort: SortOrder): Comparator<VideoMedia> = when (sort) {
        SortOrder.NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        SortOrder.NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.displayName }
        SortOrder.DATE_ADDED_DESC -> compareByDescending { it.dateAddedMillis }
        SortOrder.DATE_ADDED_ASC -> compareBy { it.dateAddedMillis }
        SortOrder.DURATION_DESC -> compareByDescending { it.durationMicros }
        SortOrder.SIZE_DESC -> compareByDescending { it.sizeBytes }
    }

    private fun VideoMedia.volumeRoot(): String? =
        path?.let { root -> Regex("""^/storage/[^/]+""").find(root)?.value }
}

data class Facets(
    val categories: Map<MediaCategory, Int>,
    val resolutions: Map<ResolutionBand, Int>,
    val durations: Map<DurationBand, Int>,
    val containers: Map<String, Int>,
    val volumes: List<String>,
    val folders: List<String>,
)
