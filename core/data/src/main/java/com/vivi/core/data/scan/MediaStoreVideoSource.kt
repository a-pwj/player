package com.vivi.core.data.scan

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.MediaSource
import com.vivi.core.data.model.VideoMedia
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * MediaStore 视频源。
 *
 * 系统媒体库是「不申请所有文件访问权限也能看到视频」的唯一途径，所以它是主数据源；
 * 文件遍历和 SAF 只补它覆盖不到的地方（未媒体索引的目录、隐藏目录）。
 * onChange 后整表重扫：视频条目是千级，比维护增量 diff 更可靠。
 */
class MediaStoreVideoSource(private val context: Context) {

    private companion object {
        const val TAG = "ViViMediaStore"
    }

    private val collections: List<Uri> by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_INTERNAL),
            )
        } else {
            listOf(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.INTERNAL_CONTENT_URI,
            )
        }
    }

    fun observe(): Flow<List<VideoMedia>> = callbackFlow {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val handler = Handler(Looper.getMainLooper())
        val observer = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                scope.launch { trySend(load()) }
            }
        }
        scope.launch { trySend(load()) }
        collections.forEach { uri ->
            runCatching { context.contentResolver.registerContentObserver(uri, true, observer) }
        }
        awaitClose {
            scope.cancel()
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }

    /** 一次性加载，供文件夹详情页与搜索使用。 */
    fun load(): List<VideoMedia> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATA,
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val output = ArrayList<VideoMedia>()
        for (collection in collections) {
            val cursor = runCatching {
                context.contentResolver.query(collection, projection, null, null, sortOrder)
            }.onFailure { android.util.Log.w(TAG, "查询 $collection 失败", it) }.getOrNull() ?: continue
            val before = output.size
            cursor.use { source -> while (source.moveToNext()) source.toVideoMedia(collection)?.let(output::add) }
            android.util.Log.d(TAG, "$collection -> ${output.size - before} 条")
        }
        return output
    }

    private fun Cursor.toVideoMedia(collection: Uri): VideoMedia? {
        val id = long(0) ?: return null
        val name = string(1) ?: return null
        val dataPath = string(8)
        val fallbackPath = dataPath ?: relativePath(name)
        return VideoMedia(
            // 能拿到真实路径就用路径当 key：同一个文件在「文件扫描」和「MediaStore」里
            // 必须是同一个 key，否则进度、收藏、播放列表会随索引状态来回失效。
            key = dataPath?.let { MediaKey.ofPath(it) } ?: MediaKey.ofMediaStore(id),
            uri = ContentUris.withAppendedId(collection, id),
            displayName = name,
            path = fallbackPath,
            folderPath = fallbackPath?.substringBeforeLast('/'),
            durationMicros = (long(3) ?: 0L) * 1_000L,
            sizeBytes = long(2) ?: 0L,
            width = int(4) ?: 0,
            height = int(5) ?: 0,
            dateAddedMillis = (long(6) ?: 0L) * 1_000L,
            lastModifiedMillis = (long(7) ?: 0L) * 1_000L,
            source = MediaSource.MEDIA_STORE,
        )
    }

    /** scoped storage 下 DATA 可能为 null，用 relative_path 拼出展示路径。 */
    private fun Cursor.relativePath(name: String): String? {
        val index = getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
        if (index < 0 || isNull(index)) return null
        val relative = getString(index) ?: return null
        return File(Environment.getExternalStorageDirectory(), "$relative$name").absolutePath
    }

    private fun Cursor.long(index: Int): Long? = if (isNull(index)) null else getLong(index)

    private fun Cursor.int(index: Int): Int? = if (isNull(index)) null else getInt(index)

    private fun Cursor.string(index: Int): String? = if (isNull(index)) null else getString(index)
}
