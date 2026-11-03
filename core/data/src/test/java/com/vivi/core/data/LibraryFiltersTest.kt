package com.vivi.core.data

import com.vivi.core.data.filter.DurationBand
import com.vivi.core.data.filter.Facets
import com.vivi.core.data.filter.LibraryFilter
import com.vivi.core.data.filter.LibraryFilterEngine
import com.vivi.core.data.filter.MediaCategory
import com.vivi.core.data.filter.ResolutionBand
import com.vivi.core.data.filter.SizeBand
import com.vivi.core.data.filter.VideoClassifier
import com.vivi.core.data.filter.WatchState
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.PlaybackProgress
import com.vivi.core.data.model.SortOrder
import com.vivi.core.data.model.VideoMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFiltersTest {

    private val minute = 60_000_000L

    private fun names(items: List<VideoMedia>) = items.map { it.displayName }

    private fun apply(
        items: List<VideoMedia>,
        filter: LibraryFilter = LibraryFilter(),
        favorites: Set<MediaKey> = emptySet(),
        progress: Map<MediaKey, PlaybackProgress> = emptyMap(),
        categories: Map<MediaKey, MediaCategory> = emptyMap(),
    ) = LibraryFilterEngine.apply(items, filter, favorites, progress, categories)

    @Test
    fun unknownDurationIsNotDroppedByClipFloor() {
        val items = listOf(
            video("unknown.mkv", durationMicros = 0),
            video("clip.mp4", durationMicros = 10 * minute),
            video("tiny.mp4", durationMicros = 10_000_000),  // 10 秒，低于 20 秒门槛
            video("feature.mp4", durationMicros = 100 * minute),
        )
        // 门槛只砍「已知且过短」的片段，0 时长（未探测）必须保留
        assertEquals(listOf("unknown.mkv", "clip.mp4", "feature.mp4"), names(apply(items)))
    }

    @Test
    fun minDurationComesFromSettingsAndIsHonoured() {
        val items = listOf(
            video("short.mp4", durationMicros = 30_000_000),  // 30 秒
            video("long.mp4", durationMicros = 5 * minute),  // 5 分钟
        )
        // 设置里把门槛调到 2 分钟：30 秒的被砍，5 分钟的留下
        assertEquals(
            listOf("long.mp4"),
            names(apply(items, LibraryFilter(minDurationMicros = 2 * 60 * 1_000_000L))),
        )
        // 门槛为 0 时全部放行
        assertEquals(2, names(apply(items, LibraryFilter(minDurationMicros = 0))).size)
    }

    @Test
    fun searchIsCaseInsensitiveSubstring() {
        val items = listOf(video("Big.Buck.Bunny.mp4"), video("other.mkv"))
        assertEquals(listOf("Big.Buck.Bunny.mp4"), names(apply(items, LibraryFilter(search = "buck"))))
    }

    @Test
    fun explicitCategoryMapOverridesClassifier() {
        val item = video("sample.mp4", durationMicros = 100 * minute)
        val forced = mapOf(item.key to MediaCategory.ANIME)
        assertEquals(
            listOf("sample.mp4"),
            names(apply(listOf(item), LibraryFilter(category = MediaCategory.ANIME), categories = forced)),
        )
        assertTrue(names(apply(listOf(item), LibraryFilter(category = MediaCategory.MOVIE), categories = forced)).isEmpty())
    }

    @Test
    fun classifierCoversEachRule() {
        assertEquals(MediaCategory.ANIME, VideoClassifier.classify(video("[FZ]some anime S01.mkv", durationMicros = 24 * minute)))
        assertEquals(MediaCategory.DOCUMENTARY, VideoClassifier.classify(video("earth.documentary.mkv")))
        assertEquals(MediaCategory.ADULT_EDU, VideoClassifier.classify(video("Kotlin tutorial.mp4")))
        assertEquals(MediaCategory.MOVIE, VideoClassifier.classify(video("movie.big.mp4", durationMicros = 90 * minute)))
        assertEquals(MediaCategory.TV_SERIES, VideoClassifier.classify(video("Show.S02E12.mp4", durationMicros = 45 * minute)))
        assertEquals(MediaCategory.SHORT_CLIP, VideoClassifier.classify(video("random.mp4", durationMicros = 5 * minute)))
        assertEquals(MediaCategory.MOVIE, VideoClassifier.classify(video("random.mp4", durationMicros = 80 * minute)))
        assertEquals(MediaCategory.OTHER, VideoClassifier.classify(video("random.mp4", durationMicros = 50 * minute)))
    }

    @Test
    fun resolutionBandsMatchCardLabel() {
        val cases = listOf(
            Triple(640, 360, ResolutionBand.SD),
            Triple(1280, 720, ResolutionBand.HD),
            Triple(1920, 1080, ResolutionBand.FULL_HD),
            Triple(2560, 1440, ResolutionBand.TWO_K),
            Triple(3840, 2160, ResolutionBand.FOUR_K),
            Triple(0, 0, ResolutionBand.UNKNOWN),
        )
        cases.forEach { (w, h, band) ->
            val media = video("x.mp4", width = w, height = h)
            assertEquals("$w x $h", band, VideoClassifier.resolutionBand(media))
        }
    }

    @Test
    fun durationAndSizeBandsHitBoundaries() {
        assertEquals(DurationBand.CLIP, VideoClassifier.durationBand(video("a.mp4", durationMicros = 9 * minute)))
        assertEquals(DurationBand.SHORT, VideoClassifier.durationBand(video("a.mp4", durationMicros = 10 * minute)))
        assertEquals(DurationBand.LONG, VideoClassifier.durationBand(video("a.mp4", durationMicros = 200 * minute)))
        assertEquals(DurationBand.CLIP, VideoClassifier.durationBand(video("a.mp4", durationMicros = 0)))
        assertEquals(SizeBand.SMALL, VideoClassifier.sizeBand(video("a.mp4", sizeBytes = 100L * 1024 * 1024)))
        assertEquals(SizeBand.MEDIUM, VideoClassifier.sizeBand(video("a.mp4", sizeBytes = 300L * 1024 * 1024)))
        assertEquals(SizeBand.HUGE, VideoClassifier.sizeBand(video("a.mp4", sizeBytes = 5L * 1024 * 1024 * 1024)))
    }

    @Test
    fun multipleDimensionsCompose() {
        val items = listOf(
            video("a.mp4", durationMicros = 100 * minute, width = 3840, height = 2160, sizeBytes = 4L * 1024 * 1024 * 1024),
            video("b.mkv", durationMicros = 100 * minute, width = 1280, height = 720, sizeBytes = 300L * 1024 * 1024),
            video("c.mkv", durationMicros = 0),
        )
        val filtered = apply(items, LibraryFilter(resolutions = setOf(ResolutionBand.HD), containers = setOf("mkv")))
        assertEquals(listOf("b.mkv"), names(filtered))
    }

    @Test
    fun favoritesAndWatchStateFilters() {
        val a = video("a.mp4")
        val b = video("b.mp4")
        val c = video("c.mp4")
        val progress = mapOf(
            b.key to PlaybackProgress(b.key, 1, 1, 1, completed = true),
            c.key to PlaybackProgress(c.key, 1, 1, 1, completed = false),
        )
        assertEquals(listOf("a.mp4"), names(apply(listOf(a, b, c), LibraryFilter(favoritesOnly = true), favorites = setOf(a.key))))
        assertEquals(
            listOf("a.mp4"),
            names(apply(listOf(a, b, c), LibraryFilter(watchState = WatchState.NEVER_PLAYED), progress = progress)),
        )
        assertEquals(
            listOf("b.mp4"),
            names(apply(listOf(a, b, c), LibraryFilter(watchState = WatchState.FINISHED), progress = progress)),
        )
        assertEquals(
            listOf("c.mp4"),
            names(apply(listOf(a, b, c), LibraryFilter(watchState = WatchState.UNFINISHED), progress = progress)),
        )
    }

    @Test
    fun sortingCoversNameDurationSizeDate() {
        val items = listOf(
            video("b.mp4", durationMicros = 30 * minute, sizeBytes = 10L, dateAddedMillis = 5L),
            video("a.mp4", durationMicros = 90 * minute, sizeBytes = 40L, dateAddedMillis = 9L),
            video("c.mp4", durationMicros = 60 * minute, sizeBytes = 20L, dateAddedMillis = 1L),
        )
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4"), names(apply(items, LibraryFilter(sort = SortOrder.NAME_ASC))))
        assertEquals(listOf("c.mp4", "b.mp4", "a.mp4"), names(apply(items, LibraryFilter(sort = SortOrder.NAME_DESC))))
        assertEquals(listOf("a.mp4", "c.mp4", "b.mp4"), names(apply(items, LibraryFilter(sort = SortOrder.DURATION_DESC))))
        assertEquals(listOf("a.mp4", "c.mp4", "b.mp4"), names(apply(items, LibraryFilter(sort = SortOrder.SIZE_DESC))))
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4"), names(apply(items, LibraryFilter(sort = SortOrder.DATE_ADDED_DESC))))
    }

    @Test
    fun folderFilterKeepsOnlyThatFolder() {
        val items = listOf(
            video("a.mp4", path = "/storage/emulated/0/Movies/a.mp4"),
            video("b.mp4", path = "/storage/emulated/0/DCIM/b.mp4"),
        )
        assertEquals(listOf("a.mp4"), names(apply(items, LibraryFilter(folderPath = "/storage/emulated/0/Movies"))))
    }

    @Test
    fun facetsCountAllAsTotal() {
        val items = listOf(
            video("a.mp4", durationMicros = 100 * minute),
            video("b.mkv", durationMicros = 5 * minute),
        )
        val facets: Facets = LibraryFilterEngine.facets(items, LibraryFilter(), emptyMap())
        assertEquals(2, facets.categories.getValue(MediaCategory.ALL))
        assertEquals(1, facets.categories.getValue(MediaCategory.SHORT_CLIP))
        assertEquals(mapOf("mp4" to 1, "mkv" to 1), facets.containers)
        assertEquals(1, facets.folders.size)  // 两条都在同一个 Movies 目录
        assertFalse(facets.volumes.isEmpty())
        assertFalse(LibraryFilter().isActive)
        assertTrue(LibraryFilter(search = "x").isActive)
    }
}
