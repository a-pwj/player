package com.vivi.core.ffmpeg

import com.vivi.core.ffmpeg.ThumbnailService.SpriteSheet
import com.vivi.core.ffmpeg.ThumbnailService.TileRef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 拖动预览的雪碧图取格数学：位置 -> 第几张图、第几列、第几行。 */
class SpriteSheetTest {

    private val seconds = 1_000_000L

    private fun sheet(
        sheets: Int = 2,
        interval: Long = 10 * seconds,
        columns: Int = 10,
        rows: Int = 10,
        tileWidth: Int = 160,
        tileHeight: Int = 90,
        duration: Long = 300 * seconds,
    ) = SpriteSheet(
        directory = File("unused"),
        sheetFiles = (0 until sheets).map { File("unused", "sheet$it.jpg") },
        intervalMicros = interval,
        columns = columns,
        rows = rows,
        tileWidth = tileWidth,
        tileHeight = tileHeight,
        framesPerSheet = columns * rows,
        durationMicros = duration,
    )

    @Test
    fun firstFrameIsTopLeftOfFirstSheet() {
        val tile = sheet().tileAt(0)!!
        assertEquals(TileRef(0, 0, 0), tile)
    }

    @Test
    fun frameIndexFollowsTheInterval() {
        // 每格 10 秒：第 35 秒落在第 3 格
        assertEquals(TileRef(0, 3, 0), sheet().tileAt(35 * seconds))
    }

    @Test
    fun columnWrapsIntoNextRow() {
        // 第 12 格 = 第 1 行第 2 列
        assertEquals(TileRef(0, 2, 1), sheet().tileAt(120 * seconds))
    }

    @Test
    fun framesBeyondOneSheetRollToTheNextSheet() {
        // 每张 100 格，第 100 格开始是第二张的第 0 列
        assertEquals(TileRef(1, 0, 0), sheet().tileAt(1000 * seconds))
        assertEquals(TileRef(1, 5, 0), sheet().tileAt(1055 * seconds))
    }

    @Test
    fun tailOfTheVideoStillHasATile() {
        val s = sheet(sheets = 1, columns = 3, rows = 2, duration = 65 * seconds, interval = 10 * seconds)
        // 容量 6 格（0..5 格 = 0..60 秒），拖到 65 秒应该给最后一格而不是空
        assertEquals(TileRef(0, 2, 1), s.tileAt(65 * seconds))
        assertEquals(TileRef(0, 2, 1), s.tileAt(60 * seconds))
    }

    @Test
    fun timesFarPastTheEndReturnNull() {
        val s = sheet(sheets = 1, columns = 3, rows = 2, duration = 65 * seconds, interval = 10 * seconds)
        assertNull(s.tileAt(10_000 * seconds))
    }

    @Test
    fun negativeTimeReturnsNull() {
        assertNull(sheet().tileAt(-5 * seconds))
    }

    @Test
    fun degenerateSheetsReturnNull() {
        assertNull(sheet(sheets = 0).tileAt(0))
        assertNull(sheet(interval = 0).tileAt(0))
        assertNull(sheet(columns = 0).tileAt(0))
    }

    @Test
    fun offsetIsColumnByWidthAndRowByHeight() {
        val s = sheet()
        assertEquals(2 * 160 to 1 * 90, s.offsetOf(TileRef(0, 2, 1)))
        assertEquals(0 to 0, s.offsetOf(TileRef(0, 0, 0)))
    }

    @Test
    fun sheetForMapsIndexToTheRightFile() {
        val s = sheet()
        assertEquals("sheet0.jpg", s.sheetFor(TileRef(0, 0, 0))?.name)
        assertEquals("sheet1.jpg", s.sheetFor(TileRef(1, 0, 0))?.name)
        assertNull(s.sheetFor(TileRef(9, 0, 0)))
    }
}
