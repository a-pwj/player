package com.vivi.core.ffmpeg

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** ffprobe `-show_format -show_streams -show_chapters` 的结果模型。 */
data class MediaInfo(
    val format: FormatInfo,
    val streams: List<StreamInfo>,
    val chapters: List<ChapterInfo>,
) {
    val durationMicros: Long get() = format.durationMicros
    val videoStreams: List<StreamInfo> = streams.filter { it.type == CodecType.VIDEO }
    val audioStreams: List<StreamInfo> = streams.filter { it.type == CodecType.AUDIO }
    val subtitleStreams: List<StreamInfo> = streams.filter { it.type == CodecType.SUBTITLE }

    /** 主画面：面积最大且非封面图的流。 */
    val primaryVideo: StreamInfo?
        get() = videoStreams.filterNot { it.isAttachedPicture }.maxByOrNull {
            (it.width ?: 0).toLong() * (it.height ?: 0)
        } ?: videoStreams.firstOrNull { !it.isAttachedPicture }

    val bitrate: Long get() = format.bitRate
}

data class FormatInfo(
    val formatName: String,
    val durationMicros: Long,
    val sizeBytes: Long,
    val bitRate: Long,
    val tags: Map<String, String>,
)

data class StreamInfo(
    val index: Int,
    val type: CodecType,
    val codecName: String?,
    val profile: String?,
    val width: Int?,
    val height: Int?,
    val pixelFormat: String?,
    val frameRate: Double?,
    val sampleRate: Int?,
    val channels: Int?,
    val channelLayout: String?,
    val bitDepth: Int?,
    val bitRate: Long,
    val language: String?,
    val title: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isAttachedPicture: Boolean,
)

enum class CodecType { VIDEO, AUDIO, SUBTITLE, DATA, UNKNOWN }

data class ChapterInfo(
    val id: Int,
    val startMicros: Long,
    val endMicros: Long,
    val title: String?,
)

internal object ProbeJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** ffmpeg-kit 把 ffprobe 的 stdout 和日志混在一起，取首尾大括号之间的部分。 */
    fun parse(raw: String): MediaInfo? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }
            .getOrNull() ?: return null

        val format = root.obj("format")
        val streams = root.arr("streams")
        val chapters = root.arr("chapters")

        return MediaInfo(
            format = FormatInfo(
                formatName = format.str("format_name").orEmpty(),
                durationMicros = (format.dbl("duration") ?: 0.0).toMicros(),
                sizeBytes = format.num("size") ?: 0L,
                bitRate = format.num("bit_rate") ?: 0L,
                tags = format.tagsMap(),
            ),
            streams = streams.mapNotNull { entry ->
                val item = entry as? JsonObject ?: return@mapNotNull null
                StreamInfo(
                    index = item.num("index")?.toInt() ?: -1,
                    type = when (item.str("codec_type")) {
                        "video" -> CodecType.VIDEO
                        "audio" -> CodecType.AUDIO
                        "subtitle" -> CodecType.SUBTITLE
                        "data" -> CodecType.DATA
                        else -> CodecType.UNKNOWN
                    },
                    codecName = item.str("codec_name"),
                    profile = item.str("profile"),
                    width = item.num("width")?.toInt(),
                    height = item.num("height")?.toInt(),
                    pixelFormat = item.str("pix_fmt"),
                    frameRate = item.frameRate(),
                    sampleRate = item.num("sample_rate")?.toInt(),
                    channels = item.num("channels")?.toInt(),
                    channelLayout = item.str("channel_layout"),
                    bitDepth = item.str("pix_fmt").pixBitDepth(),
                    bitRate = item.num("bit_rate") ?: 0L,
                    language = item.tagsMap()["language"],
                    title = item.tagsMap()["title"],
                    isDefault = item.disposition("default") == 1L,
                    isForced = item.disposition("forced") == 1L,
                    isAttachedPicture = item.disposition("attached_pic") == 1L,
                )
            },
            chapters = chapters.mapNotNull { entry ->
                val item = entry as? JsonObject ?: return@mapNotNull null
                ChapterInfo(
                    id = item.num("id")?.toInt() ?: 0,
                    startMicros = item.timeValue("start", "start_time"),
                    endMicros = item.timeValue("end", "end_time"),
                    title = item.tagsMap()["title"],
                )
            },
        )
    }

    /**
     * 章节时间有两种写法：`start` 是按 `time_base` 的整数刻度（ffprobe 对章节给的是这个），
     * `start_time` 是 `HH:MM:SS.nnnnnnnnn` 字符串。两者都要支持，否则章节全变成 0。
     */
    private fun JsonObject.timeValue(tickKey: String, clockKey: String): Long {
        val clock = str(clockKey)
        clock?.let { value ->
            CLOCK_TIME.find(value)?.let { match ->
                val groups = match.groupValues
                val seconds = groups[1].toLong() * 3600 + groups[2].toLong() * 60 + groups[3].toLong()
                val fraction = groups[4].take(6).padEnd(6, '0').toLongOrNull() ?: 0L
                return seconds * 1_000_000 + fraction
            }
            value.toDoubleOrNull()?.let { return (it * 1_000_000).toLong() }
        }
        val ticks = num(tickKey) ?: return 0L
        val base = str("time_base") ?: "1/1000000"
        val ratio = base.split('/').let { parts ->
            val numerator = parts.getOrNull(0)?.toDoubleOrNull() ?: 1.0
            val denominator = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
            if (denominator == 0.0) 0.0 else numerator / denominator
        }
        return (ticks * ratio * 1_000_000).toLong()
    }

    private val CLOCK_TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[.,](\d+)""")

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): List<Any?> = (this[key] as? JsonArray)?.toList() ?: emptyList()

    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.text()

    private fun JsonObject?.num(key: String): Long? =
        (this?.get(key) as? JsonPrimitive)?.text()?.toLongOrNull()

    private fun JsonObject?.dbl(key: String): Double? =
        (this?.get(key) as? JsonPrimitive)?.text()?.toDoubleOrNull()

    private fun JsonObject?.tagsMap(): Map<String, String> {
        val node = this?.get("tags") as? JsonObject ?: return emptyMap()
        return node.mapNotNull { (key, value) ->
            val text = (value as? JsonPrimitive)?.text() ?: return@mapNotNull null
            key to text
        }.toMap()
    }

    private fun JsonPrimitive.text(): String? = if (this is JsonNull) null else content

    private fun JsonObject?.disposition(key: String): Long? =
        (this?.get("disposition") as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.text()?.toLongOrNull() }

    /** r_frame_rate 形如 "30000/1001"，也可能是 0/0。 */
    private fun JsonObject.frameRate(): Double? {
        val raw = str("r_frame_rate") ?: str("avg_frame_rate") ?: return null
        val parts = raw.split('/')
        val numerator = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val denominator = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        if (denominator == 0.0 || numerator == 0.0) return null
        return numerator / denominator
    }

    private fun String?.pixBitDepth(): Int? {
        val value = this ?: return null
        return "p(\\d{2})".toRegex().find(value)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun Double.toMicros(): Long = (this * 1_000_000).toLong()
}
