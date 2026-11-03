package com.vivi.core.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「系统能不能直接播」的决策表。
 *
 * 这条链路决定要不要动 FFmpeg：容器不认→混流（秒级）、音频不认→只重编音频、
 * 视频不认才转码（慢，且要先征求用户同意）。判错方向要么白白耗时，要么直接播不出来。
 */
class CompatibilityPolicyTest {

    private val behaviour = CompatibilityPolicy.RiskyBehaviour(
        transcodeRiskyVideo = false,
        transcodeRiskyAudio = false,
    )

    private fun stream(
        type: CodecType,
        codec: String?,
        bitRate: Long = 1000,
        width: Int? = null,
        height: Int? = null,
        attached: Boolean = false,
    ) = StreamInfo(
        index = 0,
        type = type,
        codecName = codec,
        profile = null,
        width = width,
        height = height,
        pixelFormat = null,
        frameRate = null,
        sampleRate = null,
        channels = null,
        channelLayout = null,
        bitDepth = null,
        bitRate = bitRate,
        language = null,
        title = null,
        isDefault = false,
        isForced = false,
        isAttachedPicture = attached,
    )

    private fun info(video: String?, audio: String?) = MediaInfo(
        format = FormatInfo("matroska", 60_000_000, 1000, 1000, emptyMap()),
        streams = listOfNotNull(
            video?.let { stream(CodecType.VIDEO, it, width = 1920, height = 1080) },
            audio?.let { stream(CodecType.AUDIO, it) },
        ),
        chapters = emptyList(),
    )

    @Test
    fun nativeContainerWithNativeCodecsGoesStraightThrough() {
        assertEquals(
            CompatibilityPolicy.Decision.Native,
            CompatibilityPolicy.decide(info("h264", "aac"), "mp4", behaviour),
        )
        assertEquals("", CompatibilityPolicy.Decision.Native.reason)
    }

    @Test
    fun unknownContainerIsRemuxed() {
        val decision = CompatibilityPolicy.decide(info("h264", "aac"), "avi", behaviour)
        assertTrue(decision is CompatibilityPolicy.Decision.Remux)
        assertTrue(decision.reason.contains("avi"))
    }

    @Test
    fun containerDotPrefixIsNormalized() {
        assertEquals(
            CompatibilityPolicy.decide(info("h264", "aac"), "avi", behaviour),
            CompatibilityPolicy.decide(info("h264", "aac"), ".AVI", behaviour),
        )
    }

    @Test
    fun unsupportedVideoAlwaysTranscodes() {
        for (codec in listOf("flv1", "wmv2", "rv40", "prores")) {
            val decision = CompatibilityPolicy.decide(info(codec, "aac"), "mkv", behaviour)
            assertTrue("$codec 应该走转码", decision is CompatibilityPolicy.Decision.TranscodeVideo)
        }
    }

    @Test
    fun riskyVideoOnlyTranscodesWhenUserOptedIn() {
        val off = CompatibilityPolicy.decide(info("mpeg4", "aac"), "mkv", behaviour)
        assertTrue(off is CompatibilityPolicy.Decision.Native)
        val on = CompatibilityPolicy.decide(
            info("mpeg4", "aac"),
            "mkv",
            CompatibilityPolicy.RiskyBehaviour(transcodeRiskyVideo = true, transcodeRiskyAudio = false),
        )
        assertTrue(on is CompatibilityPolicy.Decision.TranscodeVideo)
    }

    @Test
    fun unsupportedAudioTranscodesAudioOnly() {
        val decision = CompatibilityPolicy.decide(info("h264", "cook"), "mkv", behaviour)
        assertTrue(decision is CompatibilityPolicy.Decision.TranscodeAudio)
        assertTrue(decision.reason.contains("cook"))
    }

    @Test
    fun riskyAudioRespectsTheAutoRemuxSwitch() {
        val off = CompatibilityPolicy.decide(info("h264", "ac3"), "mkv", behaviour)
        assertTrue(off is CompatibilityPolicy.Decision.Native)
        val on = CompatibilityPolicy.decide(
            info("h264", "ac3"),
            "mkv",
            CompatibilityPolicy.RiskyBehaviour(transcodeRiskyVideo = false, transcodeRiskyAudio = true),
        )
        assertTrue(on is CompatibilityPolicy.Decision.TranscodeAudio)
    }

    @Test
    fun videoProblemWinsOverAudioProblem() {
        // 视频解不了必须转码，此时音频顺带一起重编，不该只报音频问题
        val decision = CompatibilityPolicy.decide(info("flv1", "cook"), "flv", behaviour)
        assertTrue(decision is CompatibilityPolicy.Decision.TranscodeVideo)
    }

    @Test
    fun audioOnlyFileIsNotJudgedAsVideoProblem() {
        val decision = CompatibilityPolicy.decide(info(null, "mp3"), "mp3", behaviour)
        assertTrue(decision is CompatibilityPolicy.Decision.Native)
    }

    @Test
    fun missingProbeFallsBackToNative() {
        // 探测失败时不能拦着用户，交给 Media3 自己试
        assertEquals(CompatibilityPolicy.Decision.Native, CompatibilityPolicy.decide(null, "mkv", behaviour))
        assertEquals(CompatibilityPolicy.Decision.Native, CompatibilityPolicy.decide(info(null, null), null, behaviour))
    }

    @Test
    fun highestBitrateAudioIsTheOneJudged() {
        val two = MediaInfo(
            format = FormatInfo("matroska", 1, 1, 1, emptyMap()),
            streams = listOf(
                stream(CodecType.VIDEO, "h264", width = 1920, height = 1080),
                stream(CodecType.AUDIO, "aac", bitRate = 128_000),
                stream(CodecType.AUDIO, "cook", bitRate = 64_000),
            ),
            chapters = emptyList(),
        )
        assertTrue(CompatibilityPolicy.decide(two, "mkv", behaviour) is CompatibilityPolicy.Decision.Native)
    }

    @Test
    fun coverArtStreamIsNotTreatedAsVideo() {
        val withCover = MediaInfo(
            format = FormatInfo("mp3", 1, 1, 1, emptyMap()),
            streams = listOf(
                stream(CodecType.VIDEO, "mjpeg", attached = true, width = 500, height = 500),
                stream(CodecType.AUDIO, "mp3"),
            ),
            chapters = emptyList(),
        )
        // 封面图会被 primaryVideo 排除，不该因此触发转码
        assertTrue(CompatibilityPolicy.decide(withCover, "mp3", behaviour) is CompatibilityPolicy.Decision.Native)
    }

    @Test
    fun nativeContainerLookupHandlesCaseAndMissingExtension() {
        assertTrue(CompatibilityPolicy.isNativeContainer("MKV"))
        assertTrue(CompatibilityPolicy.isNativeContainer(".mp4"))
        assertFalse(CompatibilityPolicy.isNativeContainer("avi"))
        assertFalse(CompatibilityPolicy.isNativeContainer(null))
    }

    @Test
    fun bitmapSubtitleCodecsAreListed() {
        assertTrue(CompatibilityPolicy.BITMAP_SUBTITLE_CODECS.contains("hdmv_pgs_subtitle"))
        assertTrue(CompatibilityPolicy.BITMAP_SUBTITLE_CODECS.contains("dvd_subtitle"))
    }
}
