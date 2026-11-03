package com.vivi.core.data.scan

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 存储卷枚举。
 *
 * 只扫主存储的播放器在插了 SD 卡/OTG U 盘的机器上一定被吐槽「找不到文件」。
 * `android.storage.StorageVolume` 的取路径方法在不同 API 级别上名字不一样（getPath / getFile），
 * 而且部分属于非公开接口，所以统一反射 + 兜底，任何一条失败都不影响主存储。
 */
object StorageVolumes {

    data class Volume(val name: String, val path: String, val isRemovable: Boolean)

    fun primary(): String = Environment.getExternalStorageDirectory().absolutePath

    fun list(context: Context): List<Volume> {
        val result = LinkedHashMap<String, Volume>()
        putVolume(result, "内部存储", primary(), isRemovable = false)

        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val raw = manager?.let { reflective(it, "getStorageVolumes") as? List<*> }.orEmpty()
        raw.filterNotNull().forEach { volume ->
            val path = (reflective(volume, "getFile") as? File)?.absolutePath
                ?: reflective(volume, "getPath") as? String
            if (path.isNullOrBlank()) return@forEach
            val name = (reflective(volume, "getDescription") as? CharSequence)?.toString()
                ?: path.substringAfterLast('/')
            val removable = reflective(volume, "isRemovable") as? Boolean ?: false
            putVolume(result, name, path, removable)
        }

        // 少数 ROM 只把扩展存储在环境变量里
        System.getenv("SECONDARY_STORAGE")?.split(File.pathSeparator)?.forEach { path ->
            putVolume(result, path.substringAfterLast('/'), path, isRemovable = true)
        }
        return result.values.filter { File(it.path).canRead() }
    }

    private fun putVolume(target: MutableMap<String, Volume>, name: String, path: String, isRemovable: Boolean) {
        val normalized = File(path).absolutePath
        if (normalized.isNotBlank()) target.putIfAbsent(normalized, Volume(name, normalized, isRemovable))
    }

    private fun reflective(target: Any, methodName: String): Any? =
        runCatching {
            target.javaClass.getMethod(methodName).invoke(target)
        }.getOrNull()

    /**
     * 真正的系统分区，任何情况下都不扫。
     *
     * Android/data 与 Android/obb 不在这里：用户明确要求把它们纳入搜索，
     * 是否扫描由设置项 scanAndroidData 决定（见 [isProtectedAppDirectory]）。
     */
    private val EXCLUDED_SEGMENTS = listOf("/proc", "/sys", "/system", "/vendor", "/apex", "/config")

    /** Android 11+ 即使授予「所有文件访问」，这三个目录仍可能被存储层拒绝读取。 */
    private val PROTECTED_APP_DIRS = listOf("/android/data", "/android/obb", "/android/media")

    fun isExcluded(path: String): Boolean {
        val lowered = path.lowercase()
        return EXCLUDED_SEGMENTS.any { lowered.contains(it) }
    }

    fun isProtectedAppDirectory(path: String): Boolean {
        val lowered = path.lowercase()
        return PROTECTED_APP_DIRS.any { "/$lowered".contains(it) || lowered.contains(it) }
    }

    /**
     * 是否按用户设置跳过应用私有目录。
     * @param include true 时 Android/data 也会被扫（能否真的读到取决于系统限制）。
     */
    fun skipAppDirectories(path: String, include: Boolean): Boolean =
        isExcluded(path) || (!include && isProtectedAppDirectory(path))

    fun creationTime(file: File): Long = runCatching {
        val attributes = java.nio.file.Files.readAttributes(
            file.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
        )
        attributes.creationTime().to(TimeUnit.MILLISECONDS)
    }.getOrDefault(file.lastModified())

    /** 递归深度上限，防止异常符号链接导致失控。 */
    const val MAX_SCAN_DEPTH = 8
}
