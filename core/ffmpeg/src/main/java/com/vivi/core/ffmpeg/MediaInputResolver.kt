package com.vivi.core.ffmpeg

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKitConfig
import java.io.File

/**
 * 把播放/分析用的 Uri 变成 ffmpeg 能打开的输入参数。
 *
 * 优先级：MediaStore 的真实路径 -> ffmpeg-kit 官方的 SAF 参数（`getSafParameterForRead`，
 * 它把 content:// 转成 ffmpeg 内部的 saf 协议地址）-> 自己持有 fd 走 /proc/self/fd -> 整文件复制到缓存。
 * SAF 参数是 ffmpeg 专用语法，不能加引号，所以用 [MediaInput.rawCommandParameter] 标记。
 */
class MediaInputResolver(private val context: Context) {

    fun resolve(uri: Uri): MediaInput {
        when (uri.scheme) {
            null, "file" -> {
                val path = uri.path ?: uri.toString().removePrefix("file://")
                val file = File(path)
                if (file.canRead()) return MediaInput.opened(file.absolutePath)
            }

            "content" -> {
                mediaStorePath(uri)?.let { return MediaInput.opened(it) }
                FFmpegKitConfig.getSafParameterForRead(context, uri)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return MediaInput(path = it, rawCommandParameter = true) }
                val descriptor = runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")
                }.getOrNull()
                if (descriptor != null) {
                    return MediaInput(path = "/proc/self/fd/${descriptor.fd}", descriptor = descriptor)
                }
            }

            "http", "https", "rtsp", "rtmp", "udp" -> return MediaInput.opened(uri.toString())
        }
        return copyToCache(uri)
    }

    private fun mediaStorePath(uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        val path = runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                if (cursor.isNull(index)) null else cursor.getString(index)
            }
        }.getOrNull() ?: return null
        val file = File(path)
        return if (file.canRead()) file.absolutePath else null
    }

    private fun copyToCache(uri: Uri): MediaInput {
        val target = File(FFmpegEnvironment.workingDir(context), "input-${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开 $uri" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return MediaInput(path = target.absolutePath, copiedFile = target)
    }
}

class MediaInput(
    val path: String,
    val rawCommandParameter: Boolean = false,
    private val descriptor: ParcelFileDescriptor? = null,
    private val copiedFile: File? = null,
) : AutoCloseable {

    val isLocalFile: Boolean get() = !rawCommandParameter && path.startsWith("/")

    override fun close() {
        descriptor?.close()
        copiedFile?.delete()
    }

    companion object {
        fun opened(path: String) = MediaInput(path)
    }
}
