package com.vivi.player.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import com.vivi.core.data.model.MediaSource
import com.vivi.core.data.model.VideoMedia
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 媒体库缩略图。
 *
 * 优先级：系统 MediaStore 已经生成好的缩略图（零成本）-> 系统 ThumbnailUtils -> 
 * ffmpeg 兜底（AVI/RMVB 这类系统解码器打不开的容器）。
 * 列表一次可见十几条，所以内存 LRU + 磁盘缓存 + 相同尺寸复用，避免滚动时反复解码。
 */
object ThumbnailLoader {

    private val memory = object : LruCache<String, Bitmap>(48) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }

    private fun diskDir(context: Context) = File(context.filesDir, "thumbs").apply { mkdirs() }

    suspend fun load(
        context: Context,
        media: VideoMedia,
        widthPx: Int,
        ffmpegFallback: (suspend () -> Bitmap?)? = null,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${media.key.value}-$widthPx"
        memory.get(cacheKey)?.let { return@withContext it }
        val diskFile = File(diskDir(context), "${cacheKey.hashCode()}.jpg")
        if (diskFile.isFile) {
            val decoded = runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
            if (decoded != null) {
                memory.put(cacheKey, decoded)
                return@withContext decoded
            }
        }
        val bitmap = mediaStoreThumbnail(context, media)
            ?: systemThumbnail(context, media, widthPx)
            ?: ffmpegFallback?.invoke()
            ?: return@withContext null
        memory.put(cacheKey, bitmap)
        runCatching {
            diskFile.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out) }
        }
        bitmap
    }

    private fun mediaStoreThumbnail(context: Context, media: VideoMedia): Bitmap? {
        if (media.source != MediaSource.MEDIA_STORE) return null
        val id = media.key.value.removePrefix("ms:").toLongOrNull() ?: return null
        val collection = MediaStore.Video.Thumbnails.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Video.Thumbnails.DATA)
        return runCatching {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Video.Thumbnails.VIDEO_ID} = ?",
                arrayOf(id.toString()),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val path = cursor.getString(0) ?: return@use null
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            }
        }.getOrNull()
    }

    private fun systemThumbnail(context: Context, media: VideoMedia, widthPx: Int): Bitmap? {
        val target = (widthPx / 3f).coerceIn(96f, 512f).toInt()
        val path = media.path
        if (Build.VERSION.SDK_INT >= 31 && path != null) {
            val byFile = runCatching {
                android.media.ThumbnailUtils.createVideoThumbnail(
                    File(path),
                    Size(target, target),
                    null,
                )
            }.getOrNull()
            if (byFile != null) return byFile
        }
        return retrieverThumbnail(context, media.uri, target)
    }

    /** 兜底：抓 10% 处的一帧，避开黑场片头。 */
    private fun retrieverThumbnail(context: Context, uri: Uri, target: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            retriever.getFrameAtTime(duration * 1000 / 10, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { frame ->
                    val ratio = target.toFloat() / frame.width.coerceAtLeast(1)
                    if (ratio < 1f) {
                        Bitmap.createScaledBitmap(frame, target, (frame.height * ratio).toInt(), true)
                    } else {
                        frame
                    }
                }
        }.getOrNull().also { runCatching { retriever.release() } }
    }

    fun clearCache(context: Context) {
        memory.evictAll()
        diskDir(context).listFiles()?.forEach { it.delete() }
    }

    fun cacheSizeBytes(context: Context): Long =
        diskDir(context).listFiles()?.sumOf { it.length() } ?: 0L
}
