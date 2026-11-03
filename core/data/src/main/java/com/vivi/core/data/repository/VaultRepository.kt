package com.vivi.core.data.repository

import android.content.Context
import android.media.MediaScannerConnection
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vivi.core.data.db.VaultDao
import com.vivi.core.data.db.VaultItemEntity
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.MediaSource
import com.vivi.core.data.model.VaultItem
import com.vivi.core.data.model.VaultMode
import com.vivi.core.data.model.VideoMedia
import java.io.File
import java.security.MessageDigest
import com.vivi.core.data.security.PinPolicy
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.vaultStore: DataStore<Preferences> by preferencesDataStore(name = "viviv_vault")

/**
 * 隐私空间。
 *
 * 两档强度由用户自己选，因为代价不同：
 * - HIDDEN_ONLY 不挪文件，本应用内不可见即可，但系统相册还是能看到，适合「不想被顺手翻到」；
 * - RELOCATED 把文件复制进 filesDir/vault 并改成不可读名字，其它应用读不到，代价是占内部存储、
 *   大文件复制需要时间。
 *
 * PIN 用 PBKDF2 + 随机盐存储，不落明文；连续错误按次数递增锁定时间，避免被当面暴力试。
 */
class VaultRepository(
    private val context: Context,
    private val dao: VaultDao,
) {

    sealed interface UnlockResult {
        data object Unlocked : UnlockResult
        data object WrongPin : UnlockResult
        data object Duress : UnlockResult
        data class LockedOut(val untilMillis: Long) : UnlockResult
    }

    enum class MoveFailure { NONE, NOT_ENOUGH_SPACE, SOURCE_MISSING }

    data class MoveOutcome(val moved: Int, val failure: MoveFailure, val confirmIntent: Any? = null)

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    val isConfigured: Flow<Boolean> = context.vaultStore.data.map { !it[Keys.pinHash].isNullOrBlank() }

    val hideEntry: Flow<Boolean> = context.vaultStore.data.map { it[Keys.hideEntry] ?: false }

    val autoLockMinutes: Flow<Int> = context.vaultStore.data.map { it[Keys.autoLockMinutes] ?: 1 }

    fun vaultDir(): File = File(context.filesDir, "vault").apply { mkdirs() }

    fun lock() {
        _unlocked.value = false
    }

    suspend fun createPin(pin: String) {
        require(PinPolicy.accepts(pin)) { "PIN 至少 ${PinPolicy.MIN_PIN} 位纯数字" }
        val salt = PinPolicy.randomSalt()
        val hash = derive(pin, salt)
        context.vaultStore.edit {
            it[Keys.pinSalt] = salt.toHex()
            it[Keys.pinHash] = hash.toHex()
        }
        // 刚设完 PIN 就要求再输一次是反直觉的：创建动作本身已经是身份验证
        _unlocked.value = true
    }

    /** 胁迫 PIN：解锁后只展示空列表，用于被人逼着打开时不暴露内容。 */
    suspend fun setDuressPin(pin: String?) {
        context.vaultStore.edit { prefs ->
            if (pin == null) {
                prefs.remove(Keys.duressHash)
            } else {
                val salt = (prefs[Keys.pinSalt] ?: DEFAULT_SALT).hexToBytes()
                prefs[Keys.duressHash] = derive(pin, salt).toHex()
            }
        }
    }

    suspend fun unlock(pin: String): UnlockResult {
        val now = System.currentTimeMillis()
        val lockUntil = currentLockUntil()
        if (lockUntil > now) return UnlockResult.LockedOut(lockUntil)

        val prefs = context.vaultStore.data.first()
        val storedHash = prefs[Keys.pinHash] ?: return UnlockResult.WrongPin
        val salt = (prefs[Keys.pinSalt] ?: DEFAULT_SALT).hexToBytes()
        val candidate = derive(pin, salt).toHex()

        return when {
            prefs[Keys.duressHash]?.let { PinPolicy.equalsConstantTime(it, candidate) } == true -> {
                _unlocked.value = true
                UnlockResult.Duress
            }

            PinPolicy.equalsConstantTime(candidate, storedHash) -> {
                resetFailures()
                _unlocked.value = true
                UnlockResult.Unlocked
            }

            else -> {
                val attempts = (prefs[Keys.failedAttempts] ?: 0) + 1
                val penaltyMinutes = PinPolicy.penaltyMinutes(attempts)
                context.vaultStore.edit {
                    it[Keys.failedAttempts] = attempts
                    if (penaltyMinutes > 0) {
                        it[Keys.lockUntil] = now + penaltyMinutes * 60_000L
                    }
                }
                UnlockResult.WrongPin
            }
        }
    }

    private suspend fun currentLockUntil(): Long =
        context.vaultStore.data.first()[Keys.lockUntil] ?: 0L

    private suspend fun resetFailures() {
        context.vaultStore.edit {
            it[Keys.failedAttempts] = 0
            it.remove(Keys.lockUntil)
        }
    }

    suspend fun changePin(oldPin: String, newPin: String): Boolean =
        when (unlock(oldPin)) {
            is UnlockResult.Unlocked -> {
                createPin(newPin)
                true
            }

            else -> false
        }

    suspend fun setAutoLockMinutes(minutes: Int) {
        context.vaultStore.edit { it[Keys.autoLockMinutes] = minutes.coerceIn(0, 120) }
    }

    suspend fun setHideEntry(hidden: Boolean) {
        context.vaultStore.edit { it[Keys.hideEntry] = hidden }
    }

    fun observeItems(): Flow<List<VaultItem>> = dao.observeAll().map { rows ->
        rows.map { it.toModel() }
    }

    /** 供媒体库过滤：这些 key 永远不出现在普通列表里。 */
    fun observeHiddenKeys(): Flow<Set<MediaKey>> = dao.observeKeys().map { keys -> keys.map(::MediaKey).toSet() }

    suspend fun hiddenKeysSnapshot(): Set<MediaKey> =
        dao.getAll().map { MediaKey(it.mediaKey) }.toSet()

    /**
     * 移入隐私空间。
     *
     * RELOCATED 需要真实复制文件：先复制到目标名，校验大小一致后才删原文件，
     * 中途失败绝不留下「两边都没有」的状态。空间不足直接提前返回。
     */
    suspend fun moveIn(
        items: List<VideoMedia>,
        mode: VaultMode,
        onProgress: (VideoMedia, Float) -> Unit = { _, _ -> },
    ): MoveOutcome = withContext(Dispatchers.IO) {
        var moved = 0
        for (media in items) {
            val source = media.path?.let(::File)
            if (mode == VaultMode.HIDDEN_ONLY) {
                dao.put(
                    VaultItemEntity(
                        id = media.key.value,
                        mediaKey = media.key.value,
                        mode = mode.name,
                        currentPath = media.path ?: media.uri.toString(),
                        originalPath = media.path,
                        displayName = media.displayName,
                        durationMicros = media.durationMicros,
                        sizeBytes = media.sizeBytes,
                        addedAtMillis = System.currentTimeMillis(),
                    )
                )
                moved++
                onProgress(media, 1f)
                continue
            }

            if (source == null || !source.isFile) return@withContext MoveOutcome(moved, MoveFailure.SOURCE_MISSING)
            if (!hasRoomFor(source.length())) {
                return@withContext MoveOutcome(moved, MoveFailure.NOT_ENOUGH_SPACE)
            }
            val target = File(vaultDir(), opaqueName(media))
            val copied = runCatching {
                source.inputStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BUFFER)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (source.length() > 0) onProgress(media, written.toFloat() / source.length())
                        }
                    }
                }
                target.length() == source.length()
            }.getOrDefault(false)
            if (!copied) {
                target.delete()
                return@withContext MoveOutcome(moved, MoveFailure.NOT_ENOUGH_SPACE)
            }
            onProgress(media, 1f)
            dao.put(
                VaultItemEntity(
                    id = media.key.value,
                    mediaKey = media.key.value,
                    mode = mode.name,
                    currentPath = target.absolutePath,
                    originalPath = source.absolutePath,
                    displayName = media.displayName,
                    durationMicros = media.durationMicros,
                    sizeBytes = source.length(),
                    addedAtMillis = System.currentTimeMillis(),
                )
            )
            source.delete()
            media.uri.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            moved++
        }
        MoveOutcome(moved, MoveFailure.NONE)
    }

    /** 移出：回到原目录；原目录不可写则落到 Download/ViVi 并触发媒体扫描，让它重新出现在系统图库。 */
    suspend fun moveOut(key: MediaKey, onProgress: (Float) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            val row = dao.getByKey(key.value) ?: return@withContext false
            val current = File(row.currentPath)
            dao.remove(key.value)
            if (row.mode == VaultMode.HIDDEN_ONLY.name) return@withContext true
            if (!current.isFile) return@withContext false

            val preferred = row.originalPath?.let(::File)
            val target = if (preferred != null && preferred.parentFile?.canWrite() == true) {
                preferred
            } else {
                val downloads = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS,
                    ),
                    "ViVi",
                ).apply { mkdirs() }
                File(downloads, row.displayName)
            }
            val ok = runCatching {
                current.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
                }
                target.length() == current.length()
            }.getOrDefault(false)
            onProgress(1f)
            if (!ok) return@withContext false
            current.delete()
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
            true
        }

    /** 关闭隐私空间：可选择把已移入的文件放回原位。 */
    suspend fun disable(restoreFiles: Boolean, onProgress: (String) -> Unit = {}) {
        if (restoreFiles) {
            dao.getAll().forEach { row ->
                onProgress(row.displayName)
                moveOut(MediaKey(row.mediaKey))
            }
        }
        context.vaultStore.edit {
            it.remove(Keys.pinHash)
            it.remove(Keys.pinSalt)
            it.remove(Keys.duressHash)
            it.remove(Keys.failedAttempts)
            it.remove(Keys.lockUntil)
        }
        _unlocked.value = false
    }

    private fun opaqueName(media: VideoMedia): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((media.key.value + media.displayName).toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        val extension = media.extension.takeIf { it.length in 1..4 } ?: "mp4"
        return "${hex.take(24)}.$extension"
    }

    private fun hasRoomFor(bytes: Long): Boolean {
        val stat = android.os.StatFs(vaultDir().absolutePath)
        return stat.availableBytes > bytes + MIN_FREE_MARGIN
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray = PinPolicy.derive(pin, salt)

    private fun VaultItemEntity.toModel() = VaultItem(
        key = MediaKey(mediaKey),
        mode = runCatching { VaultMode.valueOf(mode) }.getOrDefault(VaultMode.HIDDEN_ONLY),
        currentPath = currentPath,
        originalPath = originalPath,
        displayName = displayName,
        addedAtMillis = addedAtMillis,
    )

    /**
     * 隐私空间里的条目也要能进媒体库模型播放，这里做一次性转换。
     *
     * 磁盘上是不可读的哈希名，但列表要显示用户认识的原名（存在 entity 里），
     * 否则隐私空间里全是 `f4c4fae4….flv` 这种没法辨认的条目。
     */
    fun toVideoMedia(row: VaultItem, originalName: String? = null): VideoMedia = VideoMedia(
        key = row.key,
        uri = if (row.mode == VaultMode.RELOCATED) {
            android.net.Uri.fromFile(File(row.currentPath))
        } else {
            android.net.Uri.parse(row.currentPath)
        },
        displayName = originalName ?: row.displayName,
        path = row.currentPath,
        folderPath = vaultDir().absolutePath,
        durationMicros = 0,
        sizeBytes = File(row.currentPath).length(),
        width = 0,
        height = 0,
        dateAddedMillis = row.addedAtMillis,
        lastModifiedMillis = File(row.currentPath).lastModified(),
        source = MediaSource.VAULT,
    )

    private companion object {
        const val MIN_PIN = 4
        const val SALT_BYTES = 16
        const val ITERATIONS = 120_000
        const val KEY_BITS = 256
        const val COPY_BUFFER = 512 * 1024
        const val MIN_FREE_MARGIN = 64L * 1024 * 1024
        val DEFAULT_SALT = "00".repeat(SALT_BYTES)
    }

    private object Keys {
        val pinHash = stringPreferencesKey("pin_hash")
        val pinSalt = stringPreferencesKey("pin_salt")
        val duressHash = stringPreferencesKey("duress_hash")
        val failedAttempts = intPreferencesKey("failed_attempts")
        val lockUntil = longPreferencesKey("lock_until")
        val autoLockMinutes = intPreferencesKey("auto_lock_minutes")
        val hideEntry = booleanPreferencesKey("hide_entry")
    }
}
