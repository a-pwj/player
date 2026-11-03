package com.vivi.core.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令拼接的转义规则：ffmpeg-kit 按 shell 语义切分参数串，
 * 文件名里的空格和引号如果处理错，会把一条命令变成两条、或者改变输出路径。
 */
class CommandBuilderTest {

    @Test
    fun `plain path stays unquoted`() {
        assertEquals("/sdcard/DCIM/a.mp4", CommandBuilder.path("/sdcard/DCIM/a.mp4"))
    }

    @Test
    fun `spaces are quoted`() {
        assertEquals("\"/sdcard/My Videos/a b.mp4\"", CommandBuilder.path("/sdcard/My Videos/a b.mp4"))
    }

    @Test
    fun `inner quotes are escaped`() {
        val escaped = CommandBuilder.path("/sdcard/a\"b.mp4")
        assertTrue(escaped.contains("\\\""))
    }

    @Test
    fun `chinese names need no escaping`() {
        assertEquals("/sdcard/电影/第一集.mp4", CommandBuilder.path("/sdcard/电影/第一集.mp4"))
    }

    @Test
    fun `filter graph keeps commas literal`() {
        val filter = "fps=1/5,scale=160:-2,tile=10x10"
        assertEquals(filter, CommandBuilder.arg(filter))
    }

    @Test
    fun `saf parameter is passed through raw`() {
        val input = MediaInput(path = "saf:content://media/document", rawCommandParameter = true)
        assertEquals("saf:content://media/document", CommandBuilder.input(input))
        val file = MediaInput(path = "/sdcard/a b.mp4")
        assertEquals("\"/sdcard/a b.mp4\"", CommandBuilder.input(file))
    }
}
