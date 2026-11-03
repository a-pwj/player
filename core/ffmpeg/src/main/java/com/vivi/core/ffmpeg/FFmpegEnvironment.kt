package com.vivi.core.ffmpeg

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import java.io.File

/** ffmpeg 相关目录与全局会话配置，进程内一次初始化。 */
object FFmpegEnvironment {

    @Volatile
    private var initialised = false

    fun init(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            val root = File(context.cacheDir, "ffmpeg").apply { mkdirs() }
            File(root, "thumb").mkdirs()
            File(root, "sub").mkdirs()
            File(root, "play").mkdirs()
            File(context.filesDir, "ffmpeg-probe").mkdirs()

            FFmpegKitConfig.setLogLevel(Level.AV_LOG_WARNING)
            // 混流/转码是长任务，抽帧与探测并发跑，限制在 3 条以免抢满 CPU 与 IO
            FFmpegKitConfig.setAsyncConcurrencyLimit(3)
            FFmpegKitConfig.enableRedirection()
            initialised = true
        }
    }

    fun cacheRoot(context: Context): File = File(context.cacheDir, "ffmpeg").apply { mkdirs() }

    fun thumbDir(context: Context): File = File(cacheRoot(context), "thumb").apply { mkdirs() }

    fun subtitleDir(context: Context): File = File(cacheRoot(context), "sub").apply { mkdirs() }

    fun playbackDir(context: Context): File = File(cacheRoot(context), "play").apply { mkdirs() }

    fun workingDir(context: Context): File = cacheRoot(context)

    /** 给设置页展示，避免 app 层直接依赖 ffmpeg-kit 的类。 */
    fun version(): String = runCatching { FFmpegKitConfig.getVersion() }.getOrDefault("未就绪")

    /** 混流/转码产物体积大，按 LRU 只保留最近使用的 [keepBytes] 字节。 */
    fun trimPlaybackCache(context: Context, keepBytes: Long) {
        val files = playbackDir(context).listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= keepBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= keepBytes) return
            total -= file.length()
            file.delete()
        }
    }
}
