package com.vivi.core.ffmpeg

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * 内封字幕落地成外挂字幕。
 *
 * Media3 的文本字幕解析只覆盖 SubRip/WebVTT/TTML/DVB，MKV 里最常见的 ASS 会被直接丢掉，
 * 这正是 ffmpeg 的补位点：抽出来转成 SRT（或直接 copy 原始 ASS），再作为外挂字幕喂回播放器。
 * PGS / DVD 这类位图字幕 ffmpeg 也做不了 OCR，只能提示用户。
 */
class SubtitleService(
    private val context: Context,
    private val runner: FFmpegRunner = FFmpegRunner(context),
    private val resolver: MediaInputResolver = MediaInputResolver(context),
) {

    enum class ExtractResult {
        SUCCESS, UNSUPPORTED_FORMAT, FAILED
    }

    data class Outcome(val status: ExtractResult, val file: File?, val message: String? = null)

    /** 文本字幕：转 SRT。 */
    private val textCodecs = setOf(
        "subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text", "eia_608",
        "subviewer", "subviewer1", "microdvd", "sami", "vt", "tt", "realtext", "dvb_teletext",
    )

    /** 已经是 ASS 时优先 copy，保留特效，交给 app 内的 ASS 解析器。 */
    private val keepStylingCodecs = setOf("ass", "ssa")

    suspend fun extract(
        source: Uri,
        identity: String,
        stream: StreamInfo,
        allSubtitleStreams: List<StreamInfo>,
    ): Outcome {
        val codec = stream.codecName?.lowercase()
        if (codec == null) return Outcome(ExtractResult.FAILED, null, "unknown codec")
        if (codec in CompatibilityPolicy.BITMAP_SUBTITLE_CODECS) {
            return Outcome(ExtractResult.UNSUPPORTED_FORMAT, null, "$codec 是位图字幕，无法转为文本")
        }
        if (codec !in textCodecs) {
            return Outcome(ExtractResult.UNSUPPORTED_FORMAT, null, "$codec 暂不支持导出为文本字幕")
        }

        // -map 0:s:N 的下标是「同类型流里的序号」，不是容器流号
        val position = allSubtitleStreams.indexOfFirst { it.index == stream.index }.coerceAtLeast(0)
        val keepAss = codec in keepStylingCodecs
        val extension = if (keepAss) "ass" else "srt"
        val target = File(
            FFmpegEnvironment.subtitleDir(context),
            "${hash(identity)}-s${stream.index}.$extension",
        )
        if (target.isFile && target.length() > 0) {
            return Outcome(ExtractResult.SUCCESS, target)
        }

        return resolver.resolve(source).use { input ->
            val codecArg = if (keepAss) listOf("-c:s", "copy") else listOf("-c:s", "srt")
            val args = listOf(
                "-y", "-hide_banner", "-loglevel", "warning",
                "-i", CommandBuilder.input(input),
                "-map", "0:s:$position",
                "-vn", "-an", "-dn",
            ) + codecArg + listOf(CommandBuilder.path(target.absolutePath))
            val outcome = runner.execute(CommandBuilder.join(args))
            when {
                outcome.success && target.isFile && target.length() > 0 ->
                    Outcome(ExtractResult.SUCCESS, target)
                outcome.success ->
                    Outcome(ExtractResult.FAILED, null, "字幕流为空")
                else ->
                    Outcome(ExtractResult.FAILED, null, outcome.errorSummary)
            }
        }
    }

    /** 外挂字幕文件复制到缓存目录，统一走文件路径，避免 SAF 权限过期。 */
    suspend fun importExternal(uri: Uri, identity: String): File? {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "subtitle"
        val extension = name.substringAfterLast('.', "srt").lowercase()
        val target = File(FFmpegEnvironment.subtitleDir(context), "${hash(identity)}-ext.$extension")
        if (target.isFile && target.length() > 0) return target
        return runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() > 0) target else null
        }.getOrNull()
    }

    fun clearExtracted() {
        FFmpegEnvironment.subtitleDir(context).listFiles()?.forEach { it.delete() }
    }

    private fun hash(identity: String): String =
        MessageDigest.getInstance("SHA-1").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}
