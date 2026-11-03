package com.vivi.core.data.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.MediaSource
import com.vivi.core.data.model.VideoMedia
import java.io.File
import java.util.concurrent.TimeUnit

/** 可播放容器/裸流扩展名。MediaStore 只索引它能识别的部分，这里必须自己补齐。 */
object VideoExtensions {

    val ALL: Set<String> = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "f4v", "wmv", "asf", "rm", "rmvb",
        "ts", "m2ts", "mts", "mpg", "mpeg", "vob", "3gp", "3g2", "ogv", "ogm", "divx", "mxf",
        "mp3", "m4a", "aac", "flac", "opus", "ogg", "oga", "wav", "aiff", "ac3", "eac3", "dts", "amr",
    )

    /** 时长未知的条目只有超过这个大小才当作视频，过滤掉同名的封面/字幕包。 */
    const val MIN_BYTES = 1_000_000L

    /** 纯视频容器：这类扩展名几乎不会误报，门槛可以放低，否则几百 KB 的短片会被整批漏掉。 */
    private val VIDEO_ONLY = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "f4v", "wmv", "asf", "rm", "rmvb",
        "ts", "m2ts", "mts", "mpg", "mpeg", "vob", "3gp", "3g2", "ogv", "ogm", "divx", "mxf",
    )

    const val VIDEO_MIN_BYTES = 128L * 1024

    fun minBytesFor(name: String): Long =
        if (of(name) in VIDEO_ONLY) VIDEO_MIN_BYTES else MIN_BYTES

    fun of(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isVideo(name: String): Boolean = of(name) in ALL
}

/**
 * 直接文件遍历，只在用户授予「所有文件访问权限」后启用。
 *
 * 它的价值是能看到 MediaStore 不索引的路径（NAS 挂载、.hidden 目录、第二存储的任意层级）。
 * 时长/分辨率不逐个解析——那需要每条都开一次 MediaMetadataRetriever，改成进入详情时用 ffprobe 补。
 */
class FileSystemVideoSource(private val context: Context) {

    /**
     * 扫描根。
     *
     * Android 11+ 之后 `/sdcard/Android/data` 本身不可枚举，但**本应用自己的**外部私有目录
     * 可以用绝对路径直接访问，所以把它单独作为根加进来，否则「扫描 Android/data」永远是空的。
     */
    fun defaultRoots(includeOwnAppDirs: Boolean): List<String> {
        val roots = LinkedHashSet(StorageVolumes.list(context).map { it.path })
        if (includeOwnAppDirs) {
            runCatching { context.getExternalFilesDir(null)?.absolutePath }.getOrNull()?.let { roots.add(it) }
            runCatching { context.externalMediaDirs.mapNotNull { it?.absolutePath } }.getOrNull()
                ?.forEach { roots.add(it) }
        }
        return roots.toList()
    }

    fun load(
        roots: List<String>? = null,
        includeAndroidData: Boolean = true,
    ): List<VideoMedia> {
        val output = ArrayList<VideoMedia>()
        for (root in (roots ?: defaultRoots(includeOwnAppDirs = includeAndroidData))) {
            val directory = File(root)
            if (!directory.isDirectory || !directory.canRead()) continue
            walk(directory, 0, output, includeAndroidData)
        }
        return output
    }

    private fun walk(directory: File, depth: Int, output: MutableList<VideoMedia>, includeAndroidData: Boolean) {
        if (depth > StorageVolumes.MAX_SCAN_DEPTH) return
        val children = directory.listFiles() ?: return
        for (child in children) {
            val name = child.name
            if (child.isDirectory) {
                if (name.startsWith(".")) continue
                if (StorageVolumes.skipAppDirectories(child.absolutePath, includeAndroidData)) continue
                walk(child, depth + 1, output, includeAndroidData)
            } else if (VideoExtensions.isVideo(name) && child.length() >= VideoExtensions.minBytesFor(name)) {
                val modified = child.lastModified()
                output += VideoMedia(
                    key = MediaKey.ofPath(child.absolutePath),
                    uri = Uri.fromFile(child),
                    displayName = name,
                    path = child.absolutePath,
                    folderPath = child.parentFile?.absolutePath,
                    durationMicros = 0,
                    sizeBytes = child.length(),
                    width = 0,
                    height = 0,
                    dateAddedMillis = StorageVolumes.creationTime(child),
                    lastModifiedMillis = modified,
                    source = MediaSource.FILE_SYSTEM,
                )
            }
        }
    }
}

/**
 * SAF 授权目录。用户主动挑一个网络存储/加密目录时，MediaStore 和文件遍历都可能失效，
 * 只有 persistable URI 权限这条路能走通。
 */
class SafTreeVideoSource(private val context: Context) {

    fun loadGrantedTrees(): List<Uri> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }

    fun load(treeUri: Uri): List<VideoMedia> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val output = ArrayList<VideoMedia>()
        collect(root, 0, output)
        return output
    }

    private fun collect(folder: DocumentFile, depth: Int, output: MutableList<VideoMedia>) {
        if (depth > StorageVolumes.MAX_SCAN_DEPTH) return
        for (child in folder.listFiles()) {
            if (child.isDirectory) {
                collect(child, depth + 1, output)
                continue
            }
            val name = child.name ?: continue
            if (!VideoExtensions.isVideo(name) || child.length()!! < VideoExtensions.minBytesFor(name)) continue
            val path = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(
                    child.uri,
                    DocumentsContract.getTreeDocumentId(child.uri),
                ).toString()
            }.getOrDefault(child.uri.toString())
            output += VideoMedia(
                key = MediaKey.ofUri(child.uri),
                uri = child.uri,
                displayName = name,
                path = path,
                folderPath = child.parentFile?.uri?.toString(),
                durationMicros = 0,
                sizeBytes = child.length() ?: 0L,
                width = 0,
                height = 0,
                dateAddedMillis = child.lastModified() ?: 0L,
                lastModifiedMillis = child.lastModified() ?: 0L,
                source = MediaSource.SAF,
            )
        }
    }
}
