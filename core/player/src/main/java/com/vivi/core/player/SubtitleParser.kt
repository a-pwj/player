package com.vivi.core.player

import java.io.File
import java.util.Locale

enum class SubtitleFormat { SRT, VTT, ASS, UNKNOWN }

/**
 * SRT / WebVTT / ASS 文本字幕解析。
 *
 * 为什么不用 Media3 自带解析：内封 ASS 是 MKV 里最常见的字幕格式，ExoPlayer 直接丢弃；
 * ffmpeg 能把它导成 .ass（保留样式）或 .srt（丢样式）。这里两条路都吃：
 * 保留样式时按 ASS 的 PrimaryColour / \an / MarginV 做近似渲染，够用且不用实现完整 \pos 引擎。
 */
object SubtitleParser {

    fun detect(fileName: String, lines: List<String>): SubtitleFormat {
        val lowered = fileName.lowercase()
        if (lowered.endsWith(".ass") || lowered.endsWith(".ssa")) return SubtitleFormat.ASS
        if (lowered.endsWith(".srt") || lowered.endsWith(".sub")) return SubtitleFormat.SRT
        if (lowered.endsWith(".vtt")) return SubtitleFormat.VTT
        val head = lines.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(" ")
        return when {
            head.startsWith("WEBVTT") -> SubtitleFormat.VTT
            head.contains("[Script Info]", ignoreCase = true) || head.contains("[V4", ignoreCase = true) ->
                SubtitleFormat.ASS
            TIME_PATTERN.containsMatchIn(head) -> SubtitleFormat.SRT
            else -> SubtitleFormat.UNKNOWN
        }
    }

    fun parse(file: File): List<SubtitleCue> {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        if (lines.isEmpty()) return emptyList()
        return parseText(detect(file.name, lines), lines)
    }

    /** 文本入口，供测试与「从网络读到的字幕串」复用。 */
    fun parseText(format: SubtitleFormat, text: String): List<SubtitleCue> =
        parseText(format, text.lines())

    fun parseText(format: SubtitleFormat, lines: List<String>): List<SubtitleCue> {
        if (lines.isEmpty()) return emptyList()
        return when (format) {
            SubtitleFormat.ASS -> parseAss(lines)
            SubtitleFormat.VTT -> parseVtt(lines)
            SubtitleFormat.SRT -> parseSrt(lines)
            SubtitleFormat.UNKNOWN -> parseSrt(lines).ifEmpty { parseVtt(lines) }
        }.sortedBy { it.startMicros }
    }

    private val TIME_PATTERN = Regex("""\d{1,2}:\d{2}:\d{2}[.,]\d{2,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[.,]\d{2,3}""")

    private fun parseSrt(lines: List<String>): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        var index = 0
        while (index < lines.size) {
            if (!TIME_PATTERN.containsMatchIn(lines[index])) {
                index++
                continue
            }
            val timing = splitTiming(lines[index])
            if (timing == null) {
                index++
                continue
            }
            val text = StringBuilder()
            index++
            while (index < lines.size && lines[index].isNotBlank()) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(stripTags(lines[index]))
                index++
            }
            if (text.isNotBlank()) cues += SubtitleCue(timing.first, timing.second, text.toString().trim())
        }
        return cues
    }

    private fun parseVtt(lines: List<String>): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (!line.contains("-->")) {
                index++
                continue
            }
            val timing = splitTiming(line)
            if (timing == null) {
                index++
                continue
            }
            val text = StringBuilder()
            index++
            while (index < lines.size && lines[index].isNotBlank() && !lines[index].contains("-->")) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(stripTags(lines[index]))
                index++
            }
            if (text.isNotBlank()) cues += SubtitleCue(timing.first, timing.second, text.toString().trim())
        }
        return cues
    }

    /** ASS：先建样式表，再逐条 Dialogue 合成。 */
    private fun parseAss(lines: List<String>): List<SubtitleCue> {
        val styles = HashMap<String, AssStyle>()
        var section = ""
        val cues = ArrayList<SubtitleCue>()

        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                section = line.lowercase()
                continue
            }
            if (line.startsWith("Style:", true)) {
                parseStyleLine(line.substringAfter(':'))?.let { styles[it.name.lowercase()] = it }
                continue
            }
            if (!line.startsWith("Dialogue:", true) || !section.contains("events")) continue
            val dialogue = parseDialogue(line.substringAfter(':')) ?: continue
            val style = styles[dialogue.styleName?.lowercase()] ?: styles["default"] ?: AssStyle(
                name = "Default",
                colorRgb = null,
                alignment = AssAlignment.BOTTOM_CENTER,
                marginVerticalPx = 40,
            )
            cues += SubtitleCue(
                startMicros = dialogue.startMicros,
                endMicros = dialogue.endMicros,
                text = cleanAss(dialogue.text),
                primaryColorRgb = style.colorRgb,
                alignment = dialogue.alignment ?: style.alignment,
                marginBottomPx = dialogue.marginVerticalPx ?: style.marginVerticalPx,
            )
        }
        return cues
    }

    private class AssDialogue(
        val startMicros: Long,
        val endMicros: Long,
        val text: String,
        val styleName: String?,
        val alignment: AssAlignment?,
        val marginVerticalPx: Int?,
    )

    private fun parseDialogue(body: String): AssDialogue? {
        val parts = body.split(',', limit = 10)
        if (parts.size < 10) return null
        val start = parseAssTime(parts[1]) ?: return null
        val end = parseAssTime(parts[2]) ?: return null
        val text = parts[9].trim()
        if (text.isBlank()) return null
        val marginV = parts.getOrNull(7)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        // \an{1..8} 覆盖样式表里的对齐方式
        val alignment = Regex("""\\an(\d)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { AssAlignment.fromAssCode(it) }
        return AssDialogue(
            startMicros = start,
            endMicros = end,
            text = text,
            styleName = parts[3].trim().takeIf { it.isNotEmpty() },
            alignment = alignment,
            marginVerticalPx = marginV,
        )
    }

    private data class AssStyle(
        val name: String,
        val colorRgb: Int?,
        val alignment: AssAlignment,
        val marginVerticalPx: Int,
    )

    private fun parseStyleLine(body: String): AssStyle? {
        val fields = body.split(',').map { it.trim() }
        if (fields.size < 22) return null
        val name = fields[0]
        // V4+ 样式表字段顺序：Name,Fontname,Fontsize,PrimaryColour,...,Alignment(18),MarginL,MarginR,MarginV(21)
        val primaryColor = parseAssColor(fields[3])
        val alignmentCode = fields[18].toIntOrNull() ?: 2
        val marginV = fields[21].toIntOrNull() ?: 40
        return AssStyle(
            name = name,
            colorRgb = primaryColor,
            alignment = AssAlignment.fromAssCode(alignmentCode),
            marginVerticalPx = marginV,
        )
    }

    /** &HAABBGGRR -> 0xAARRGGBB；ASS 的 alpha 是反的（00 不透明，FF 全透明）。 */
    private fun parseAssColor(token: String): Int? {
        val cleaned = token.trim().removePrefix("&").removePrefix("H").removePrefix("h")
        val value = cleaned.toLongOrNull(16) ?: return null
        if (token.length < 8) return (value or 0xFF000000L).toInt()
        val alpha = 255 - ((value shr 24) and 0xFF).toInt()
        val blue = ((value shr 16) and 0xFF).toInt()
        val green = ((value shr 8) and 0xFF).toInt()
        val red = (value and 0xFF).toInt()
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun cleanAss(text: String): String {
        val withoutOverrides = text.replace(Regex("""\{[^}]*\}"""), "")
        return withoutOverrides.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
    }

    private fun parseAssTime(token: String): Long? {
        val parts = token.trim().split('.')
        val clock = parts[0].split(':')
        if (clock.size < 2) return null
        val hours = clock.getOrNull(clock.size - 3)?.toLongOrNull() ?: 0L
        val minutes = clock[clock.size - 2].toLongOrNull() ?: 0L
        val seconds = clock[clock.size - 1].toLongOrNull() ?: 0L
        val millis = parts.getOrNull(1)?.let { (it.padEnd(3, '0').take(3)).toLongOrNull() } ?: 0L
        return ((hours * 3600 + minutes * 60 + seconds) * 1000 + millis) * 1000
    }

    private fun splitTiming(line: String): Pair<Long, Long>? {
        val sides = line.split("-->")
        if (sides.size < 2) return null
        // VTT 的结束时间后面还跟着 align:middle 之类的设置，用正则只取时间部分
        val start = parseClock(sides[0]) ?: return null
        val end = parseClock(sides[1]) ?: return null
        return start to end
    }

    private val CLOCK_TOKEN = Regex("""\d{1,2}:\d{1,2}(?::\d{1,2})?(?:[.,]\d{1,3})?""")

    private fun parseClock(token: String): Long? {
        val text = CLOCK_TOKEN.find(token)?.value ?: return null
        val parts = text.split(':')
        if (parts.size < 2) return null
        val secondsParts = parts.last().replace(',', '.').split('.')
        val seconds = secondsParts[0].toLongOrNull() ?: return null
        val millis = secondsParts.getOrNull(1)?.let { it.padEnd(3, '0').take(3).toLongOrNull() } ?: 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: 0L
        val hours = parts.getOrNull(parts.size - 3)?.toLongOrNull() ?: 0L
        return ((hours * 3600 + minutes * 60 + seconds) * 1000 + millis) * 1000
    }

    private fun stripTags(line: String): String =
        line.replace(Regex("""<[^>]*>"""), "").replace("&amp;", "&").trim()

    /** 语言代码转成中文显示名，列表里显示「中文」而不是「zh」。 */
    fun displayLanguage(code: String?): String? {
        val normalized = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        val locale = Locale.forLanguageTag(normalized.replace('_', '-'))
        return runCatching {
            locale.getDisplayLanguage(Locale.CHINESE).takeIf { it.isNotBlank() } ?: normalized
        }.getOrDefault(normalized)
    }
}
