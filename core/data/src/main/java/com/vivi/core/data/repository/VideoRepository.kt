package com.vivi.core.data.repository

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.vivi.core.data.db.FolderSettingsDao
import com.vivi.core.data.db.FolderSettingsEntity
import com.vivi.core.data.db.MetadataDao
import com.vivi.core.data.db.PlaybackDao
import com.vivi.core.data.model.FolderSummary
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.MediaSource
import com.vivi.core.data.model.SortOrder
import com.vivi.core.data.model.VideoMedia
import com.vivi.core.data.scan.FileSystemVideoSource
import com.vivi.core.data.scan.MediaStoreVideoSource
import com.vivi.core.data.scan.SafTreeVideoSource
import com.vivi.core.data.scan.StorageVolumes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface DeleteResult {
    data object Deleted : DeleteResult

    /** 需要用户通过系统确认对话框授权删除（Android 10+ 非本应用文件）。 */
    data class RequiresConfirmation(val pendingIntent: PendingIntent) : DeleteResult

    data class Failed(val message: String) : DeleteResult
}

/**
 * 视频列表的唯一入口。
 *
 * MediaStore 是响应式的（ContentObserver），文件遍历与 SAF 树是快照式的，
 * 所以合并策略是「MediaStore 流 + 可刷新的补充列表」，补充列表只在权限变化或用户下拉刷新时重扫。
 * 这里只负责「拿到全部条目并去重」，筛选/排序/隐私屏蔽都在上层用 LibraryFilterEngine 做，
 * 因为筛选条件变化不应该触发一次存储扫描。
 */
class VideoRepository(
    private val context: Context,
    private val mediaStore: MediaStoreVideoSource,
    private val fileSystem: FileSystemVideoSource,
    private val safTrees: SafTreeVideoSource,
    private val playbackDao: PlaybackDao,
    private val metadataDao: MetadataDao,
    private val folderSettingsDao: FolderSettingsDao,
) {

    private val extraItems = kotlinx.coroutines.flow.MutableStateFlow<List<VideoMedia>>(emptyList())
    private val scanMutex = Mutex()

    @Volatile
    var fileSystemScanEnabled: Boolean = false

    /** Android/data 等应用私有目录是否纳入扫描，由设置项驱动。 */
    @Volatile
    var includeAndroidData: Boolean = true

    /** 全部条目（已按路径/Uri 去重），筛选交给上层。 */
    fun observeItems(): Flow<List<VideoMedia>> =
        combine(mediaStore.observe(), extraItems, folderSettingsDao.observeAll()) { store, extras, folders ->
            val hiddenFolders = folders.filter { it.hidden }.map { it.path.lowercase() }.toSet()
            merge(store, extras)
                .filterNot { isHidden(it.folderPath, hiddenFolders) }
                .filterNot { StorageVolumes.skipAppDirectories(it.path.orEmpty(), includeAndroidData) }
        }

    fun observeFolders(): Flow<List<FolderSummary>> =
        observeItems().map { items ->
            items.groupBy { it.folderPath ?: "未知位置" }
                .map { (path, group) ->
                    FolderSummary(
                        path = path,
                        name = File(path).name.ifBlank { path },
                        videoCount = group.size,
                        totalBytes = group.sumOf { it.sizeBytes },
                        coverKey = group.maxByOrNull { it.lastModifiedMillis }?.key,
                        lastPlayedMillis = group.maxOfOrNull { it.lastModifiedMillis } ?: 0L,
                        isHidden = false,
                    )
                }
                .sortedBy { it.name.lowercase() }
        }

    suspend fun refreshExtras(): Int = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            val extras = buildList {
                if (fileSystemScanEnabled) addAll(fileSystem.load(includeAndroidData = includeAndroidData))
                safTrees.loadGrantedTrees().forEach { tree -> addAll(safTrees.load(tree)) }
            }
            extraItems.value = extras
            // 提示的是「新补到的条数」，而不是遍历到的总条数，否则用户以为扫到了一堆新片
            val known = mediaStore.load().mapNotNull { it.path?.lowercase() }.toHashSet()
            extras.count { it.path?.lowercase() !in known }
        }
    }

    /** 一次性快照，播放器构造同目录连播队列时用。 */
    suspend fun snapshotItems(): List<VideoMedia> = withContext(Dispatchers.IO) {
        merge(mediaStore.load(), extraItems.value)
    }

    suspend fun findByKey(key: MediaKey): VideoMedia? = withContext(Dispatchers.IO) {
        val store = mediaStore.load()
        (store + extraItems.value).resolveKey(key)
            ?: fileSystem.load().resolveKey(key)
            ?: safTrees.loadGrantedTrees().asSequence()
                .flatMap { safTrees.load(it).asSequence() }
                .firstOrNull { it.key == key }
    }

    /** 「继续观看」只需要按 key 找回条目，一次加载比逐条查询划算。 */
    suspend fun resolveKeys(keys: List<MediaKey>): List<VideoMedia> = withContext(Dispatchers.IO) {
        val pool = mediaStore.load() + extraItems.value
        keys.mapNotNull { pool.resolveKey(it) }
    }

    private fun List<VideoMedia>.resolveKey(key: MediaKey): VideoMedia? = resolveMediaKey(key)

    suspend fun setFolderHidden(path: String, hidden: Boolean) {
        val existing = folderSettingsDao.get(path) ?: FolderSettingsEntity(path)
        folderSettingsDao.upsert(existing.copy(hidden = hidden))
    }

    suspend fun setFolderLocked(path: String, locked: Boolean, pinHash: String?) {
        val existing = folderSettingsDao.get(path) ?: FolderSettingsEntity(path)
        folderSettingsDao.upsert(existing.copy(locked = locked, pinHash = pinHash))
    }

    suspend fun delete(media: VideoMedia): DeleteResult = withContext(Dispatchers.IO) {
        playbackDao.delete(media.key.value)
        metadataDao.delete(media.key.value)
        val file = media.path?.let(::File)
        if (file != null && file.isFile && file.canWrite()) {
            return@withContext if (file.delete()) DeleteResult.Deleted
            else DeleteResult.Failed("无法删除 ${file.name}")
        }
        runCatching {
            val rows = context.contentResolver.delete(media.uri, null, null)
            if (rows > 0) DeleteResult.Deleted else DeleteResult.Failed("媒体库中已不存在")
        }.getOrElse { error ->
            val pending = fromRecoverable(error) ?: confirmationIntent(media)
            if (pending != null) DeleteResult.RequiresConfirmation(pending)
            else DeleteResult.Failed(error.message ?: "删除失败")
        }
    }

    /** Android 10+ 删除非本应用文件必须走系统确认框，入口 Intent 藏在异常或 deleteRequest 里。 */
    private fun confirmationIntent(media: VideoMedia): PendingIntent? {
        if (Build.VERSION.SDK_INT >= 30 && media.source == MediaSource.MEDIA_STORE) {
            return runCatching {
                MediaStore.createDeleteRequest(context.contentResolver, listOf(media.uri))
            }.getOrNull()
        }
        return null
    }

    private fun fromRecoverable(error: Throwable): PendingIntent? {
        if (Build.VERSION.SDK_INT >= 29 && error is android.app.RecoverableSecurityException) {
            // RemoteAction.action 才是能直接 startIntentSender 的那个 PendingIntent
            return error.userAction?.actionIntent
        }
        return null
    }

    private fun merge(store: List<VideoMedia>, extras: List<VideoMedia>): List<VideoMedia> {
        if (extras.isEmpty()) return store
        val byPath = store.mapNotNull { it.path?.lowercase() }.toHashSet()
        val byUri = store.map { it.uri.toString() }.toHashSet()
        return store + extras.filterNot {
            (it.path?.lowercase() in byPath) || it.uri.toString() in byUri
        }
    }

    private fun isHidden(folderPath: String?, hidden: Set<String>): Boolean {
        val path = folderPath?.lowercase() ?: return false
        return hidden.any { path == it || path.startsWith("$it/") }
    }

}

/**
 * 同一个文件先后可能有两种 key：被 MediaStore 索引之前是 `fs:<路径>`，索引之后是 `ms:<id>`。
 * 进度、收藏、播放列表存的是当时看到的那个 key，所以按键找不到时按路径回退一次，
 * 否则媒体库一完成索引，「继续观看」和收藏就会整条失联。
 */
fun List<VideoMedia>.resolveMediaKey(key: MediaKey): VideoMedia? {
    firstOrNull { it.key == key }?.let { return it }
    val raw = key.value.substringAfter(':', "")
    if (!raw.startsWith("/")) return null
    val lowered = raw.lowercase()
    return firstOrNull { it.path?.lowercase() == lowered }
}
