package com.vivi.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** 字幕解析是纯文本逻辑，最容易在时间格式和样式字段顺序上出错，这里锁住关键行为。 */
class SubtitleParserTest {

    @Test
    fun `detects format from header`() {
        assertEquals(SubtitleFormat.ASS, SubtitleParser.detect("zh.ass", emptyList()))
        assertEquals(
            SubtitleFormat.SRT,
            SubtitleParser.detect("zh.srt", listOf("1", "00:00:01,000 --> 00:00:02,000")),
        )
        assertEquals(SubtitleFormat.VTT, SubtitleParser.detect("sub", listOf("WEBVTT", "", "00:00.000 --> 00:02.000")))
    }

    @Test
    fun `parses srt timing and strips tags`() {
        val srt = """
            1
            00:01:02,500 --> 00:01:05,250
            <i>你好</i> world

            2
            00:02:00,000 --> 00:02:03,000
            第二句
        """.trimIndent()
        val cues = SubtitleParser.parseText(SubtitleFormat.SRT, srt)
        assertEquals(2, cues.size)
        assertEquals("你好 world", cues[0].text)
        assertEquals(62_500_000L, cues[0].startMicros)
        assertEquals(65_250_000L, cues[0].endMicros)
        assertEquals("第二句", cues[1].text)
    }

    @Test
    fun `parses ass colour alignment and text overrides`() {
        val ass = """
            [Script Info]
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,48,&H0000FFFF,&H00000000,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,2,0,2,20,20,40,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.20,0:00:03.50,Default,,0,0,0,,第一行{\r}第二行
            Dialogue: 0,0:05:00.00,0:05:02.00,Default,,0,0,0,,\N带换行
        """.trimIndent()
        val cues = SubtitleParser.parseText(SubtitleFormat.ASS, ass)
        assertEquals(2, cues.size)
        // &H0000FFFF = ASS 不透明 + 红绿全满 -> 黄色
        assertEquals(0x00FFFF00, cues[0].primaryColorRgb?.and(0xFFFFFF))
        assertEquals(0xFF, (cues[0].primaryColorRgb?.shr(24))?.and(0xFF))
        assertEquals(AssAlignment.BOTTOM_CENTER, cues[0].alignment)
        assertEquals("第一行第二行", cues[0].text)
        assertEquals(1_200_000L, cues[0].startMicros)
        assertEquals("带换行", cues[1].text)
        assertEquals(300_000_000L, cues[1].startMicros)
    }

    @Test
    fun `vtt handles hundredths and cue settings`() {
        val vtt = """
            WEBVTT

            00:01.500 --> 00:03.250 align:middle
            字幕文本
        """.trimIndent()
        val cues = SubtitleParser.parseText(SubtitleFormat.VTT, vtt)
        assertEquals(1, cues.size)
        assertEquals(1_500_000L, cues[0].startMicros)
        assertEquals("字幕文本", cues[0].text)
    }

    @Test
    fun `garbage input yields no cues instead of throwing`() {
        assertEquals(0, SubtitleParser.parseText(SubtitleFormat.SRT, "not a subtitle").size)
        assertEquals(0, SubtitleParser.parseText(SubtitleFormat.ASS, "Dialogue: broken").size)
    }

    @Test
    fun `language codes render as chinese display names`() {
        assertEquals("中文", SubtitleParser.displayLanguage("zh"))
        assertEquals("英文", SubtitleParser.displayLanguage("eng"))
        assertEquals(null, SubtitleParser.displayLanguage("und"))
    }
}
