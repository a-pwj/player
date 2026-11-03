package com.vivi.core.ffmpeg

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.collect

/**
 * 把「系统播不了」变成「系统能播」。
 *
 * 三档代价递增：copy 混流（只改容器，速度等于 IO）、只重编音频（比视频快一个数量级）、
 * 重编视频（分钟级，必须用户确认）。产物落在 cache/ffmpeg/play，用「临时文件写完再改名」
 * 保证中断残留不会被当成完整文件复用。
 */
class PlaybackPreparer(
    private val context: Context,
    private val runner: FFmpegRunner = FFmpegRunner(context),
    private val resolver: MediaInputResolver = MediaInputResolver(context),
) {

    data class Options(
        val allowVideoTranscode: Boolean = false,
        val maxTranscodeWidth: Int = 1280,
        val videoCrf: Int = 23,
        val audioBitrateKbps: Int = 256,
        val transcodeVideoBitrateK: Int = 2000,
    )

    sealed interface Output {
        data class Direct(val uri: Uri) : Output
        data class Prepared(val file: File, val decision: CompatibilityPolicy.Decision) : Output
        data class Failed(val uri: Uri, val message: String) : Output
    }

    suspend fun prepare(
        source: Uri,
        info: MediaInfo?,
        extension: String?,
        options: Options = Options(),
        risky: CompatibilityPolicy.RiskyBehaviour = CompatibilityPolicy.RiskyBehaviour(),
        onProgress: (Float) -> Unit = {},
    ): Output {
        val decision = when (val raw = CompatibilityPolicy.decide(info, extension, risky)) {
            is CompatibilityPolicy.Decision.TranscodeVideo ->
                // 视频转码是分钟级操作，没得到许可就退回原始 Uri，让 Media3 自己试一次
                if (options.allowVideoTranscode) raw else return Output.Direct(source)
            else -> raw
        }
        if (decision is CompatibilityPolicy.Decision.Native) return Output.Direct(source)

        val directory = FFmpegEnvironment.playbackDir(context)
        val key = cacheKey(source, info, decision, options)
        val extension = outputContainerFor(decision).extension
        val finalFile = File(directory, "$key.$extension")
        if (finalFile.isFile && finalFile.length() > 0) {
            onProgress(1f)
            return Output.Prepared(finalFile, decision)
        }
        val tempFile = File(directory, "$key.tmp.$extension")

        resolver.resolve(source).use { input ->
            if (decision is CompatibilityPolicy.Decision.TranscodeVideo) resolveVideoEncoder()
            val args = buildArgs(decision, input, tempFile, info, options)
            val durationMicros = info?.durationMicros ?: 0L
            var failure: String? = null
            var cancelled = false
            try {
                runner.run(CommandBuilder.join(args)).collect { event ->
                    when (event) {
                        is FFmpegEvent.Processed -> if (durationMicros > 0) {
                            onProgress((event.micros.toFloat() / durationMicros).coerceIn(0f, 1f))
                        }
                        is FFmpegEvent.Done -> if (!event.outcome.success) {
                            failure = event.outcome.errorSummary
                        }
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw cancel
            } finally {
                if (cancelled || failure != null) tempFile.delete()
            }

            return if (failure == null && tempFile.isFile && tempFile.length() > 0) {
                finalFile.delete()
                // File.renameTo 在部分 ROM 上会失败（返回 false 而不是抛异常），
                // 用 NIO move 才能可靠落位，否则每次进这条视频都要重混一遍。
                val renamed = runCatching {
                    java.nio.file.Files.move(
                        tempFile.toPath(),
                        finalFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                    true
                }.getOrDefault(false)
                onProgress(1f)
                Output.Prepared(if (renamed) finalFile else tempFile, decision)
            } else {
                Output.Failed(source, failure ?: "ffmpeg 未生成输出文件")
            }
        }
    }

    private fun buildArgs(
        decision: CompatibilityPolicy.Decision,
        input: MediaInput,
        output: File,
        info: MediaInfo?,
        options: Options,
    ): List<String> {
        val head = listOf(
            "-y",
            "-hide_banner",
            "-loglevel", "warning",
            // AVI / RMVB 的索引经常残缺，重新生成 PTS
            "-fflags", "+genpts",
            "-i", CommandBuilder.input(input),
        )
        val tail = listOf(
            "-avoid_negative_ts", "make_zero",
            "-f", outputContainerFor(decision).muxer,
            CommandBuilder.path(output.absolutePath),
        )
        return when (decision) {
            is CompatibilityPolicy.Decision.TranscodeAudio ->
                head + listOf("-map", "0:v:?", "-map", "0:a:?", "-c:v", "copy",
                    "-c:a", "aac", "-b:a", "${options.audioBitrateKbps}k") + tail

            is CompatibilityPolicy.Decision.TranscodeVideo ->
                head + scaleArgs(info, options) + listOf(
                    "-map", "0:v:0",
                    "-c:v", transcodeVideoCodec,
                    "-b:v", "${options.transcodeVideoBitrateK}k",
                ) + transcodeCodecOptions(transcodeVideoCodec) + listOf(
                    "-map", "0:a:?",
                    "-c:a", "aac",
                    "-b:a", "${options.audioBitrateKbps}k",
                ) + tail

            else ->
                head + listOf("-map", "0:v:?", "-map", "0:a:?", "-c", "copy") + tail
        }
    }

    /** 只在超宽片源时缩放，避免 ffmpeg 表达式里的逗号被命令解析器切开。 */
    private fun scaleArgs(info: MediaInfo?, options: Options): List<String> {
        val width = info?.primaryVideo?.width ?: 0
        val height = info?.primaryVideo?.height ?: 0
        if (width <= options.maxTranscodeWidth || width <= 0 || height <= 0) return emptyList()
        val targetHeight = (options.maxTranscodeWidth.toLong() * height / width)
            .coerceAtLeast(2L)
            .let { if (it % 2 == 0L) it else it - 1 }
        return listOf("-vf", "scale=${options.maxTranscodeWidth}:$targetHeight")
    }

    private fun cacheKey(
        source: Uri,
        info: MediaInfo?,
        decision: CompatibilityPolicy.Decision,
        options: Options,
    ): String {
        val fingerprint = runCatching {
            val file = File(source.path.orEmpty())
            if (file.isFile) "${file.length()}-${file.lastModified()}" else "${info?.durationMicros ?: 0}"
        }.getOrDefault("0")
        val mode = when (decision) {
            is CompatibilityPolicy.Decision.TranscodeVideo ->
                "tv-${options.maxTranscodeWidth}-${options.videoCrf}"
            is CompatibilityPolicy.Decision.TranscodeAudio -> "ta-${options.audioBitrateKbps}"
            else -> "rm"
        }
        val raw = "$fingerprint|$mode|$source"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private enum class OutContainer(val muxer: String, val extension: String) {
        MP4("mp4", "mp4"),
        MATROSKA("matroska", "mkv"),
    }

    /**
     * 转码编码器。
     *
     * ffmpeg-kit 的 Android 预编译包**不含 libx264**（授权原因），真机上直接报
     * `Unknown encoder 'libx264'`，所以这里运行时探测可用编码器再决定：
     * 优先 VP8（Android 普遍能解），退化到 mpeg4（内置，永远可用但设备解码有差异）。
     */
    @Volatile
    private var cachedVideoEncoder: String? = null

    private val encoderCandidates = listOf("libvpx", "mpeg4")

    private suspend fun resolveVideoEncoder(): String {
        cachedVideoEncoder?.let { return it }
        val listing = runCatching { runner.execute("-hide_banner -encoders").logs }.getOrDefault("")
        val chosen = encoderCandidates.firstOrNull { name ->
            Regex("""(?m)^\s*V[.\-A-Z]+\s+$name\s""").containsMatchIn(listing)
        } ?: "mpeg4"
        cachedVideoEncoder = chosen
        return chosen
    }

    /** VP8 需要实时档才不会慢到不可用；mpeg4 用 qscale 控制质量。 */
    private fun transcodeCodecOptions(codec: String): List<String> = when (codec) {
        "libvpx" -> listOf(
            "-deadline", "realtime",
            "-cpu-used", "5",
            "-auto-alt-ref", "0",
            "-pix_fmt", "yuv420p",
        )

        else -> listOf(
            "-q:v", "5",
            "-pix_fmt", "yuv420p",
        )
    }

    private val transcodeVideoCodec: String
        get() = cachedVideoEncoder ?: "libvpx"

    /**
     * copy 路径输出 MKV：Media3 原生解析 Matroska，而 MKV 几乎装得下任何音视频编码。
     * 统一输出 MP4 会被 vorbis / pcm / wmav2 这类流直接拒绝，整条混流失败。
     * 转码路径同样用 MKV，因为可用的视频编码器是 VP8/mpeg4，MP4 对 VP8 的解析支持不好。
     */
    private fun outputContainerFor(decision: CompatibilityPolicy.Decision): OutContainer = OutContainer.MATROSKA

    fun clearPreparedCache() {
        FFmpegEnvironment.playbackDir(context).listFiles()?.forEach { it.delete() }
    }
}
