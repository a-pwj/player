package com.vivi.core.data

import com.vivi.core.data.scan.StorageVolumes
import com.vivi.core.data.scan.VideoExtensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRulesTest {

    @Test
    fun extensionRecognitionCoversContainersAndIgnoresCase() {
        listOf("a.mkv", "b.WMV", "c.ts", "d.mp3", "e.f4v", "f.rmvb").forEach { assertTrue(it, VideoExtensions.isVideo(it)) }
        listOf("a.ass", "b.srt", "c.jpg", "d.txt", "e").forEach { assertFalse(it, VideoExtensions.isVideo(it)) }
        assertEquals("mkv", VideoExtensions.of("X.Y.MKV"))
        assertEquals("", VideoExtensions.of("noext"))
    }

    @Test
    fun pureVideoContainersUseTheLowerSizeFloor() {
        // 时长未知时靠体积兜底：纯视频容器门槛低，否则几百 KB 的短片会整批漏掉
        assertEquals(VideoExtensions.VIDEO_MIN_BYTES, VideoExtensions.minBytesFor("clip.mp4"))
        assertEquals(VideoExtensions.VIDEO_MIN_BYTES, VideoExtensions.minBytesFor("clip.rmvb"))
        assertEquals(VideoExtensions.VIDEO_MIN_BYTES, VideoExtensions.minBytesFor("clip.avi"))
        assertEquals(VideoExtensions.MIN_BYTES, VideoExtensions.minBytesFor("track.mp3"))
        assertEquals(VideoExtensions.MIN_BYTES, VideoExtensions.minBytesFor("cover.jpg"))
        assertTrue(VideoExtensions.MIN_BYTES > VideoExtensions.VIDEO_MIN_BYTES)
    }

    @Test
    fun systemDirsAlwaysSkippedAndAppDirsFollowTheSwitch() {
        val own = "/storage/emulated/0/Android/data/com.x/files/a.mp4"
        assertTrue(StorageVolumes.skipAppDirectories(own, include = false))
        assertFalse(StorageVolumes.skipAppDirectories(own, include = true))
        assertTrue(StorageVolumes.skipAppDirectories("/system/media/a.mp4", include = true))
        assertTrue(StorageVolumes.skipAppDirectories("/storage/emulated/0/Android/obb/a.mp4", include = false))
        assertTrue(StorageVolumes.skipAppDirectories("/storage/emulated/0/Android/media/a.mp4", include = false))
        assertFalse(StorageVolumes.skipAppDirectories("/storage/emulated/0/Movies/a.mp4", include = false))
        assertFalse(StorageVolumes.isExcluded("/storage/emulated/0/Movies/a.mp4"))
        assertTrue(StorageVolumes.isExcluded("/data/system/a.mp4"))
    }

    @Test
    fun scanDepthIsBounded() {
        assertEquals(8, StorageVolumes.MAX_SCAN_DEPTH)
    }
}
