package com.vivi.core.ffmpeg

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * ffmpeg 缩略图。
 *
 * 进度条拖动预览是市面上播放器（VLC / MX / nPlayer）的基本体验。逐帧起 ffmpeg 进程不可行，
 * 所以用一条命令把整段视频按固定间隔抽帧并 tile 成雪碧图，UI 侧按偏移裁切。
 */
class ThumbnailService(
    private val context: Context,
    private val runner: FFmpegRunner = FFmpegRunner(context),
    private val resolver: MediaInputResolver = MediaInputResolver(context),
) {

    data class TileRef(val sheetIndex: Int, val column: Int, val row: Int)

    data class SpriteSheet(
        val directory: File,
        val sheetFiles: List<File>,
        val intervalMicros: Long,
        val columns: Int,
        val rows: Int,
        val tileWidth: Int,
        val tileHeight: Int,
        val framesPerSheet: Int,
        val durationMicros: Long,
    ) {
        fun tileAt(timeMicros: Long): TileRef? {
            if (intervalMicros <= 0 || columns <= 0 || sheetFiles.isEmpty()) return null
            // 整数除法会把 -0.5 截成 0，负数时间要显式挡掉
            if (timeMicros < 0) return null
            val capacity = sheetFiles.size * framesPerSheet
            val frame = (timeMicros / intervalMicros).toInt()
            // 片尾那一格也要有图：整张雪碧图是按固定间隔生成的，末尾常常差半格
            val clamped = if (frame >= capacity && timeMicros <= durationMicros + intervalMicros) {
                capacity - 1
            } else {
                frame
            }
            if (clamped < 0 || clamped >= capacity) return null
            return TileRef(clamped / framesPerSheet, clamped % columns, (clamped % framesPerSheet) / columns)
        }

        fun sheetFor(tile: TileRef): File? = sheetFiles.getOrNull(tile.sheetIndex)

        /** 单张雪碧图在预览浮层里的裁切偏移。 */
        fun offsetOf(tile: TileRef): Pair<Int, Int> =
            tile.column * tileWidth to tile.row * tileHeight
    }

    suspend fun ensureScrubSheet(
        source: Uri,
        identity: String,
        durationMicros: Long,
        info: MediaInfo? = null,
        maxSheets: Int = MAX_SHEETS,
        targetFrames: Int = TARGET_FRAMES,
    ): SpriteSheet? {
        if (durationMicros <= 0) return null
        val directory = File(FFmpegEnvironment.thumbDir(context), hash(identity))
        // ffmpeg 的 image2 打包器不会自己建目录，缺这一步真机上会报
        // "Could not open file : .../thumb/<hash>/sheet_001.jpg"
        directory.mkdirs()
        val existing = sheetFiles(directory)
        val intervalMicros = pickIntervalMicros(durationMicros, targetFrames)

        if (existing.isNotEmpty()) {
            val meta = readMeta(directory)
            if (meta != null && meta.intervalMicros == intervalMicros) return meta.copy(sheetFiles = existing)
        }

        val columns = 10
        val rows = 10
        val framesPerSheet = columns * rows
        val totalFrames = ((durationMicros + intervalMicros - 1) / intervalMicros).toInt().coerceAtLeast(1)
        val sheets = totalFrames.toFloat() / framesPerSheet
        val sheetCount = Math.ceil(sheets.toDouble()).toInt().coerceIn(1, maxSheets)
        val effectiveInterval = if (sheetCount * framesPerSheet >= totalFrames) {
            intervalMicros
        } else {
            durationMicros / (sheetCount.toLong() * framesPerSheet)
        }.coerceAtLeast(1L)

        val tileWidth = 160
        val tileHeight = tileHeightFor(info, tileWidth)
        val intervalSeconds = (effectiveInterval / 1_000_000.0).coerceAtLeast(0.5)

        val outcome = resolver.resolve(source).use { input ->
            val args = listOf(
                "-y",
                "-hide_banner",
                "-loglevel", "warning",
                "-i", CommandBuilder.input(input),
                "-vf", "fps=1/${"%.3f".format(intervalSeconds)},scale=$tileWidth:-2,tile=${columns}x$rows",
                "-an", "-sn", "-dn",
                "-frames:v", sheetCount.toString(),
                "-q:v", "5",
                CommandBuilder.path(File(directory, "sheet_%03d.jpg").absolutePath),
            )
            runner.execute(CommandBuilder.join(args))
        }
        if (!outcome.success) return null

        val produced = sheetFiles(directory)
        if (produced.isEmpty()) return null
        val sheet = SpriteSheet(
            directory = directory,
            sheetFiles = produced,
            intervalMicros = effectiveInterval,
            columns = columns,
            rows = rows,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            framesPerSheet = framesPerSheet,
            durationMicros = durationMicros,
        )
        writeMeta(directory, sheet)
        return sheet
    }

    /** 列表卡片用的单帧截图，快进到关键帧后再解码，代价约几十毫秒。 */
    suspend fun singleFrame(source: Uri, identity: String, timeMicros: Long, widthPx: Int): File? {
        val target = File(FFmpegEnvironment.thumbDir(context), "${hash(identity)}-${timeMicros / 1000}w$widthPx.jpg")
        if (target.isFile && target.length() > 0) return target
        return resolver.resolve(source).use { input ->
            val seconds = timeMicros / 1_000_000.0
            val args = listOf(
                "-y", "-hide_banner", "-loglevel", "warning",
                "-ss", "%.3f".format(seconds),
                "-i", CommandBuilder.input(input),
                "-frames:v", "1",
                "-vf", "scale=$widthPx:-2",
                "-q:v", "4",
                "-an", "-sn",
                CommandBuilder.path(target.absolutePath),
            )
            val outcome = runner.execute(CommandBuilder.join(args))
            if (outcome.success && target.isFile) target else null
        }
    }

    fun cachedSheet(identity: String): SpriteSheet? {
        val directory = File(FFmpegEnvironment.thumbDir(context), hash(identity))
        val files = sheetFiles(directory)
        if (files.isEmpty()) return null
        return readMeta(directory)?.copy(sheetFiles = files)
    }

    fun clearCache() {
        FFmpegEnvironment.thumbDir(context).listFiles()?.forEach { dir ->
            dir.deleteRecursively()
        }
    }

    private fun sheetFiles(directory: File): List<File> =
        directory.listFiles()?.filter { it.name.startsWith("sheet_") && it.name.endsWith(".jpg") }
            ?.sortedBy { it.name } ?: emptyList()

    private fun tileHeightFor(info: MediaInfo?, width: Int): Int {
        val w = info?.primaryVideo?.width ?: 0
        val h = info?.primaryVideo?.height ?: 0
        if (w <= 0 || h <= 0) return 90
        return ((width.toLong() * h / w).toInt().coerceAtLeast(2)).let { if (it % 2 == 0) it else it - 1 }
    }

    /** 间隔取整秒，长视频自动放宽，保证总帧数可控。 */
    private fun pickIntervalMicros(durationMicros: Long, targetFrames: Int): Long {
        val rawSeconds = durationMicros / 1_000_000.0 / targetFrames
        val rounded = when {
            rawSeconds <= 1 -> 1L
            rawSeconds <= 5 -> rawSeconds.toInt().coerceAtLeast(1).toLong()
            rawSeconds <= 30 -> ((rawSeconds / 5).toInt() * 5).coerceAtLeast(5).toLong()
            else -> ((rawSeconds / 30).toInt() * 30).coerceAtLeast(30).toLong()
        }
        return rounded * 1_000_000L
    }

    private fun hash(identity: String): String =
        MessageDigest.getInstance("SHA-1").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)

    /** 雪碧图元信息用文本存，避免引入序列化依赖。 */
    private fun writeMeta(directory: File, sheet: SpriteSheet) {
        val text = listOf(
            sheet.intervalMicros, sheet.columns, sheet.rows,
            sheet.tileWidth, sheet.tileHeight, sheet.framesPerSheet, sheet.durationMicros,
        ).joinToString("|")
        runCatching { File(directory, "meta.txt").writeText(text) }
    }

    private fun readMeta(directory: File): SpriteSheet? {
        val file = File(directory, "meta.txt")
        if (!file.isFile) return null
        val parts = runCatching { file.readText().split("|").map { it.toLong() } }.getOrNull() ?: return null
        if (parts.size < 7) return null
        return SpriteSheet(
            directory = directory,
            sheetFiles = emptyList(),
            intervalMicros = parts[0],
            columns = parts[1].toInt(),
            rows = parts[2].toInt(),
            tileWidth = parts[3].toInt(),
            tileHeight = parts[4].toInt(),
            framesPerSheet = parts[5].toInt(),
            durationMicros = parts[6],
        )
    }

    private companion object {
        const val MAX_SHEETS = 4
        const val TARGET_FRAMES = 300
    }
}
