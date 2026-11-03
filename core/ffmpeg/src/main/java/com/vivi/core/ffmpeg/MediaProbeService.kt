package com.vivi.core.ffmpeg

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest

/**
 * ffprobe 结果缓存。探测一条 10GB 的 MKV 需要打开容器并遍历索引，是播放前最贵的一步，
 * 所以按 `来源 + 修改时间 + 大小` 落盘缓存原始 JSON；文件被外部改动后 key 自然失效。
 */
class MediaProbeService(
    private val context: Context,
    private val runner: FFmpegRunner = FFmpegRunner(context),
    private val resolver: MediaInputResolver = MediaInputResolver(context),
) {

    private val cacheDir = File(context.filesDir, "ffmpeg-probe").apply { mkdirs() }

    suspend fun probe(uri: Uri): MediaInfo? = resolver.resolve(uri).use { input ->
        val key = cacheKey(input.path, uri.toString())
        readCache(key)?.let { return@use it }
        val probed = runCatching { runProbe(input) }.getOrNull() ?: return@use null
        writeCache(key, probed.raw)
        probed.info
    }

    /** 已知本地路径时跳过 Uri 解析，用于文件夹批量探测。 */
    suspend fun probePath(path: String): MediaInfo? {
        val key = cacheKey(path, path)
        readCache(key)?.let { return it }
        val probed = runCatching { runProbe(MediaInput.opened(path)) }.getOrNull() ?: return null
        writeCache(key, probed.raw)
        return probed.info
    }

    private suspend fun runProbe(input: MediaInput): Probed? {
        val args = listOf(
            "-hide_banner",
            "-loglevel", "quiet",
            "-print_format", "json",
            "-show_format",
            "-show_streams",
            "-show_chapters",
            "-analyzeduration", "20000000",
            "-probesize", "10000000",
            "-i", CommandBuilder.input(input),
        )
        val raw = runner.probe(CommandBuilder.join(args))
        val info = ProbeJson.parse(raw)?.takeIf { it.streams.isNotEmpty() } ?: return null
        return Probed(info, raw)
    }

    private data class Probed(val info: MediaInfo, val raw: String)

    private fun cacheKey(path: String, source: String): String {
        val fingerprint = runCatching {
            val file = File(path)
            if (file.isFile) "${file.length()}-${file.lastModified()}" else "0-0"
        }.getOrDefault("0-0")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((source + fingerprint).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun readCache(key: String): MediaInfo? {
        val file = File(cacheDir, key)
        if (!file.isFile) return null
        return runCatching { ProbeJson.parse(file.readText()) }.getOrNull()
    }

    /** 缓存写 ffprobe 原始 JSON，读的时候复用同一套解析逻辑，避免两套序列化。 */
    private fun writeCache(key: String, raw: String) {
        runCatching { File(cacheDir, key).writeText(raw) }
    }

    fun invalidate(pathOrUri: String) {
        val file = File(cacheDir, cacheKey(pathOrUri, pathOrUri))
        if (file.isFile) file.delete()
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
