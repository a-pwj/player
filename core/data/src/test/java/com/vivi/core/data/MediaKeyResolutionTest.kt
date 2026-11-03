package com.vivi.core.data

import android.net.TestUri
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.MediaSource
import com.vivi.core.data.model.VideoMedia
import com.vivi.core.data.repository.resolveMediaKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 同一个文件在媒体库里可能有两种 key：
 * 被 MediaStore 索引之前是 `fs:<路径>`，索引之后是 `ms:<id>`。
 * 进度/收藏/播放列表存的是当时那个 key，解析必须能按路径回退，
 * 否则媒体库一完成索引，「继续观看」和收藏就整条失联（真机上就是这么丢的）。
 */
class MediaKeyResolutionTest {

    private val path = "/storage/emulated/0/Movies/ViViTest/long_180.mp4"

    private fun item(key: MediaKey, itemPath: String?) = VideoMedia(
        key = key,
        uri = TestUri(itemPath ?: path),
        displayName = "long_180.mp4",
        path = itemPath,
        folderPath = itemPath?.substringBeforeLast('/'),
        durationMicros = 180_000_000,
        sizeBytes = 1L,
        width = 640,
        height = 360,
        dateAddedMillis = 1L,
        lastModifiedMillis = 1L,
        source = MediaSource.MEDIA_STORE,
    )

    private val fsKey = MediaKey.ofPath(path)
    private val msKey = MediaKey.ofMediaStore(42)

    @Test
    fun exactKeyStillWins() {
        val pool = listOf(item(fsKey, path), item(msKey, path))
        assertEquals(fsKey, pool.resolveMediaKey(fsKey)?.key)
        assertEquals(msKey, pool.resolveMediaKey(msKey)?.key)
    }

    @Test
    fun storedFsKeyResolvesToMediaStoreItem() {
        // 进度存的是 fs:，现在列表里只有 ms: 条目
        val pool = listOf(item(msKey, path))
        assertEquals(msKey, pool.resolveMediaKey(fsKey)?.key)
    }

    @Test
    fun mediaStoreRowsAreKeyedByPathSoTwoSourcesUnify() {
        // 根因修复：MediaStore 行有真实路径时用路径当 key，
        // 于是「文件扫描」和「媒体库索引」给出的是同一个 key，进度不会再两头漂移
        val fromScan = item(fsKey, path)
        val fromStore = item(MediaKey.ofPath(path), path)
        assertEquals(fromScan.key, fromStore.key)
        assertEquals(1, listOf(fromScan, fromStore).distinctBy { it.key }.size)
    }

    @Test
    fun idKeyedRowWithoutPathCannotBeRecovered() {
        // 边界：没有路径的 ms:<id> 键（scoped storage 下 DATA 为空的行）只能靠精确键命中，
        // 媒体库不再列出这条时，进度就无从回推 —— 这是已知限制，不是解析器缺陷
        val pool = listOf(item(fsKey, path))
        assertNull(pool.resolveMediaKey(msKey))
        assertEquals(msKey, (listOf(item(msKey, null).copy(path = null)) + pool).resolveMediaKey(msKey)?.key)
    }

    @Test
    fun pathMatchIsCaseInsensitive() {
        val mixedCaseItem = item(msKey, "/storage/emulated/0/Movies/ViViTest/LONG_180.MP4")
        val stored = MediaKey("fs:/storage/emulated/0/movies/vivitest/long_180.mp4")
        assertEquals(msKey, listOf(mixedCaseItem).resolveMediaKey(stored)?.key)
    }

    @Test
    fun uriKeyDoesNotFallBackToPath() {
        val pool = listOf(item(MediaKey.ofUri(TestUri("content://media/external/video/media/42")), path))
        assertNull(pool.resolveMediaKey(MediaKey.ofUri(TestUri("content://nope"))))
    }

    @Test
    fun missingFileResolvesToNull() {
        val pool = listOf(item(msKey, "/storage/emulated/0/Movies/other.mp4"))
        assertNull(pool.resolveMediaKey(fsKey))
    }

    @Test
    fun emptyPoolResolvesToNull() {
        assertNull(emptyList<VideoMedia>().resolveMediaKey(fsKey))
    }

    @Test
    fun itemWithoutPathOnlyMatchesExactKey() {
        val noPath = item(msKey, null).copy(path = null, folderPath = null)
        val pool = listOf(noPath)
        assertEquals(msKey, pool.resolveMediaKey(msKey)?.key)
        assertNull(pool.resolveMediaKey(fsKey))
    }
}
