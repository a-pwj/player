package com.vivi.core.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ffprobe JSON 解析。
 *
 * 这里用真机 ffprobe 6.x 对一条 MKV 的实际输出形状做夹具：数字既有 int 也有字符串
 * （bit_rate 是字符串、duration 是浮点、start 是整数），字幕流的 language 藏在 tags 里，
 * 帧率是 "30000/1001" 这种分数——这几处正是手写解析最容易翻车的地方。
 */
class ProbeJsonTest {

    private val fixture = """
{
    "streams": [
        {
            "index": 0,
            "codec_name": "hevc",
            "codec_long_name": "H.265 / HEVC",
            "profile": "Main 10",
            "codec_type": "video",
            "width": 3840,
            "height": 2160,
            "pix_fmt": "yuv420p10le",
            "r_frame_rate": "24000/1001",
            "avg_frame_rate": "24000/1001",
            "bit_rate": "24500000",
            "disposition": {
                "default": 1,
                "forced": 0,
                "attached_pic": 0
            }
        },
        {
            "index": 1,
            "codec_name": "dts",
            "codec_type": "audio",
            "sample_rate": "48000",
            "channels": 6,
            "channel_layout": "5.1",
            "bit_rate": "1509000",
            "tags": {
                "language": "eng",
                "title": "DTS 5.1"
            },
            "disposition": {"default": 0, "forced": 0, "attached_pic": 0}
        },
        {
            "index": 2,
            "codec_name": "aac",
            "codec_type": "audio",
            "sample_rate": "48000",
            "channels": 2,
            "bit_rate": "320000",
            "tags": {"language": "chi"},
            "disposition": {"default": 1, "forced": 0, "attached_pic": 0}
        },
        {
            "index": 3,
            "codec_name": "ass",
            "codec_type": "subtitle",
            "tags": {"language": "chi", "title": "简体中文"},
            "disposition": {"default": 0, "forced": 0, "attached_pic": 0}
        },
        {
            "index": 4,
            "codec_name": "hdmv_pgs_subtitle",
            "codec_type": "subtitle",
            "tags": {"language": "und"},
            "disposition": {"default": 0, "forced": 0, "attached_pic": 0}
        }
    ],
    "format": {
        "filename": "/storage/emulated/0/Movies/sample.mkv",
        "nb_streams": 5,
        "format_name": "matroska,webm",
        "format_long_name": "Matroska / WebM",
        "start_time": "0.000000",
        "duration": "4983.241000",
        "size": "9663213456",
        "bit_rate": "15504000",
        "tags": {"title": "Sample", "encoder": "libmakemkv v1.17"}
    },
    "chapters": [
        {
            "id": 0,
            "time_base": "1/1000",
            "start": 0,
            "start_time": "00:00:00.000000000",
            "end": 120000,
            "end_time": "00:02:00.000000000",
            "tags": {"title": "Opening"}
        },
        {
            "id": 1,
            "time_base": "1/1000",
            "start": 120000,
            "start_time": "00:02:00.000000000",
            "end": 300000,
            "end_time": "00:05:00.000000000",
            "tags": {"title": "Chapter 2"}
        }
    ]
}
"""

    @Test
    fun `reads format level numbers that mix strings and floats`() {
        val info = ProbeJson.parse(fixture)!!
        assertEquals(4_983_241_000L, info.durationMicros)
        assertEquals(9_663_213_456L, info.format.sizeBytes)
        assertEquals(15_504_000L, info.bitrate)
        assertEquals("matroska,webm", info.format.formatName)
        assertEquals("libmakemkv v1.17", info.format.tags["encoder"])
    }

    @Test
    fun `splits streams by type and keeps container index`() {
        val info = ProbeJson.parse(fixture)!!
        assertEquals(1, info.videoStreams.size)
        assertEquals(2, info.audioStreams.size)
        assertEquals(2, info.subtitleStreams.size)
        // -map 0:s:N 需要的是容器流号，不能丢
        assertEquals(3, info.subtitleStreams[0].index)
        assertEquals(4, info.subtitleStreams[1].index)
    }

    @Test
    fun `parses fractional frame rate and ten bit pixel format`() {
        val video = ProbeJson.parse(fixture)!!.primaryVideo!!
        assertEquals("hevc", video.codecName)
        assertEquals(3840, video.width)
        assertEquals(2160, video.height)
        assertEquals(10, video.bitDepth)
        assertEquals(23.976, video.frameRate!!, 0.01)
        assertTrue(video.isDefault)
    }

    @Test
    fun `reads language and title from stream tags`() {
        val info = ProbeJson.parse(fixture)!!
        assertEquals("chi", info.audioStreams.first { it.isDefault }.language)
        assertEquals("DTS 5.1", info.audioStreams.first { it.codecName == "dts" }.title)
        assertEquals("简体中文", info.subtitleStreams.first().title)
    }

    @Test
    fun `chapters come through in microseconds`() {
        val info = ProbeJson.parse(fixture)!!
        assertEquals(2, info.chapters.size)
        assertEquals(0L, info.chapters[0].startMicros)
        assertEquals(120_000_000L, info.chapters[1].startMicros)
        assertEquals("Opening", info.chapters[0].title)
    }

    @Test
    fun `bitmap subtitle codec is recognised as unsupported for text export`() {
        val pgs = ProbeJson.parse(fixture)!!.subtitleStreams[1]
        assertEquals("hdmv_pgs_subtitle", pgs.codecName)
        assertTrue(CompatibilityPolicy.BITMAP_SUBTITLE_CODECS.contains(pgs.codecName))
    }

    @Test
    fun `policy routes this file to native playback not transcode`() {
        val info = ProbeJson.parse(fixture)!!
        // HEVC 硬解普遍可用，容器是 Media3 支持的 mkv；但 DTS 音轨需要转码
        val decision = CompatibilityPolicy.decide(
            info,
            "mkv",
            CompatibilityPolicy.RiskyBehaviour(transcodeRiskyVideo = false, transcodeRiskyAudio = true),
        )
        assertTrue(decision is CompatibilityPolicy.Decision.TranscodeAudio)
        assertFalse(CompatibilityPolicy.isNativeContainer("avi"))
        assertTrue(CompatibilityPolicy.isNativeContainer("mkv"))
    }

    @Test
    fun `non json input returns null instead of throwing`() {
        assertNull(ProbeJson.parse("not json at all"))
        assertNull(ProbeJson.parse(""))
        assertNull(ProbeJson.parse("[1,2,3]"))
    }

    @Test
    fun `copy path targets matroska while video transcode targets mp4`() {
        val info = ProbeJson.parse(fixture)!!
        val remux = CompatibilityPolicy.decide(
            info.copy(streams = info.streams.filterNot { it.codecName == "dts" }),
            "avi",
            CompatibilityPolicy.RiskyBehaviour(),
        )
        assertTrue(remux is CompatibilityPolicy.Decision.Remux)
    }
}
