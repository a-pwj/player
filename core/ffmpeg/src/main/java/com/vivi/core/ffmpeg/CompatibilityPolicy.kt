package com.vivi.core.ffmpeg

/**
 * 「系统播放器能直接吃下什么」的判定表。
 *
 * Media3/ExoPlayer 自己解析容器，解码交给 MediaCodec；容器解析器只有 mp4/mkv/webm/ts/ps/ogg/wav/…
 * 解码器则由设备决定。ffmpeg 的用处就在这里：容器不认就混流（copy，秒级），
 * 音频不认就重编音频（比视频快一个数量级），视频不认才转码（慢，需用户确认）。
 */
object CompatibilityPolicy {

    /** Media3 有对应 Extractor 的容器/扩展名。 */
    private val NATIVE_CONTAINERS = setOf(
        "mp4", "m4v", "m4a", "mov", "3gp", "3g2", "mkv", "mk3d", "webm",
        "ts", "m2ts", "mts", "ps", "mpg", "mpeg", "vob", "tp",
        "ogg", "ogv", "oga", "opus", "spx",
        "mp3", "mka", "flac", "wav", "wave", "aif", "aiff", "aac", "m4b", "amr", "ac3", "eac3",
    )

    /** 几乎所有带硬解的设备都支持。 */
    private val SAFE_VIDEO_CODECS = setOf(
        "h264", "hevc", "av1", "vp8", "vp9", "mpeg2video", "mpeg1video", "h263",
    )

    /** 依赖设备：多数手机有，TV盒子/老设备可能没有。 */
    private val RISKY_VIDEO_CODECS = setOf("mpeg4", "vc1", "wmv3", "theora", "mjpegb")

    private val UNSUPPORTED_VIDEO_CODECS = setOf(
        "flv1", "svq1", "svq3", "rv10", "rv20", "rv30", "rv40", "prores", "dnxhd", "dnxav",
        "jpeg", "mjpeg", "gif", "png", "bmp", "webp", "avrn", "indeo3", "indeo5", "fraps",
        "qtrle", "cinepak", "vcr1", "msmpeg4v1", "msmpeg4v2", "msmpeg4v3", "wmv1", "wmv2",
        "h261", "asv1", "asv2", "ffv1", "huffyuv",
    )

    private val SAFE_AUDIO_CODECS = setOf(
        "aac", "mp3", "mp3adu", "mp3float", "flac", "vorbis", "opus", "pcm_s16le", "pcm_s24le",
        "pcm_s32le", "pcm_f32le", "pcm_u8", "pcm_s16be", "alac", "amrnb", "amrwb",
    )

    /** 常见但非强制：多数 Android 设备有 AC-3 解码，个别低端机没有。 */
    private val RISKY_AUDIO_CODECS = setOf("ac3", "eac3", "truehd", "mlp", "dts", "wmav1", "wmav2")

    private val UNSUPPORTED_AUDIO_CODECS = setOf(
        "cook", "atrc", "sipr", "ra_144", "ra_288", "ape", "tta", "shorten", "wmalossless",
        "interplay_acm", "acelp", "gsm_ms", "nellymoser", "pcm_s8",
    )

    /** 图片型字幕，ffmpeg 也无法转成文本字幕。 */
    val BITMAP_SUBTITLE_CODECS = setOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub")

    enum class VideoRisk { SAFE, RISKY, UNSUPPORTED }
    enum class AudioRisk { SAFE, RISKY, UNSUPPORTED }

    sealed interface Decision {
        /** 给用户看的处理原因，Native 为空串。 */
        val reason: String

        /** 直接交给 Media3。 */
        data object Native : Decision {
            override val reason: String = ""
        }

        /** 容器不认，或轨道需要挑掉：copy 重混流。 */
        data class Remux(override val reason: String) : Decision

        /** 音频解不了：视频 copy + 音频重编。 */
        data class TranscodeAudio(override val reason: String) : Decision

        /** 视频解不了：需要转码，耗时，应先征求用户同意。 */
        data class TranscodeVideo(override val reason: String) : Decision
    }

    fun decide(info: MediaInfo?, extension: String?, riskyBehaviour: RiskyBehaviour): Decision {
        val normalizedExt = extension?.lowercase()?.trimStart('.')
        val video = info?.primaryVideo
        val audio = info?.audioStreams?.maxByOrNull { it.bitRate }

        val videoRisk = video?.codecName?.let { videoRisk(it) }
        val audioRisk = audio?.codecName?.let { audioRisk(it) }

        if (videoRisk == VideoRisk.UNSUPPORTED) {
            return Decision.TranscodeVideo("视频编码 ${video?.codecName} 系统无法解码")
        }
        if (videoRisk == VideoRisk.RISKY && riskyBehaviour.transcodeRiskyVideo) {
            return Decision.TranscodeVideo("视频编码 ${video?.codecName} 在当前设备上可能无硬解")
        }
        if (audioRisk == AudioRisk.UNSUPPORTED) {
            return Decision.TranscodeAudio("音频编码 ${audio?.codecName} 系统无法解码")
        }
        if (audioRisk == AudioRisk.RISKY && riskyBehaviour.transcodeRiskyAudio) {
            return Decision.TranscodeAudio("音频编码 ${audio?.codecName} 在当前设备上可能不支持")
        }

        val needsRemux = normalizedExt != null && normalizedExt !in NATIVE_CONTAINERS
        if (needsRemux) return Decision.Remux("容器 .$normalizedExt 不被系统解析器支持")

        // MKV 里带内封图片字幕时 Media3 会忽略，但如果容器本身完好就没必要动它。
        return Decision.Native
    }

    fun isNativeContainer(extension: String?): Boolean {
        val normalized = extension?.lowercase()?.trimStart('.') ?: return false
        return normalized in NATIVE_CONTAINERS
    }

    private fun videoRisk(codec: String): VideoRisk = when {
        codec in SAFE_VIDEO_CODECS -> VideoRisk.SAFE
        codec in RISKY_VIDEO_CODECS -> VideoRisk.RISKY
        codec in UNSUPPORTED_VIDEO_CODECS -> VideoRisk.UNSUPPORTED
        else -> VideoRisk.UNSUPPORTED
    }

    private fun audioRisk(codec: String): AudioRisk = when {
        codec in SAFE_AUDIO_CODECS -> AudioRisk.SAFE
        codec in RISKY_AUDIO_CODECS -> AudioRisk.RISKY
        codec in UNSUPPORTED_AUDIO_CODECS -> AudioRisk.UNSUPPORTED
        else -> AudioRisk.UNSUPPORTED
    }

    data class RiskyBehaviour(
        /** 用户设备是否被告知过 "mpeg4/vc1 可能无硬解"。默认交给设置页开关。 */
        val transcodeRiskyVideo: Boolean = false,
        val transcodeRiskyAudio: Boolean = true,
    )
}
