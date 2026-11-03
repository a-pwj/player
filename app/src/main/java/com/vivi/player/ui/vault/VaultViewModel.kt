package com.vivi.player.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.VaultItem
import com.vivi.core.data.model.VaultMode
import com.vivi.core.data.model.VideoMedia
import com.vivi.core.data.repository.VaultRepository
import com.vivi.player.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class VaultUiState(
    val configured: Boolean = false,
    val unlocked: Boolean = false,
    val items: List<VaultItem> = emptyList(),
    val media: List<VideoMedia> = emptyList(),
    val error: String? = null,
    val lockedUntil: Long = 0,
    val autoLockMinutes: Int = 1,
    val hideEntry: Boolean = false,
    val busy: Boolean = false,
)

class VaultViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(VaultUiState())
    val ui: StateFlow<VaultUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            container.vaultRepository.isConfigured.collect { configured ->
                _ui.value = _ui.value.copy(configured = configured)
            }
        }
        viewModelScope.launch {
            container.vaultRepository.unlocked.collect { unlocked ->
                _ui.value = _ui.value.copy(unlocked = unlocked)
                if (unlocked) loadItems()
            }
        }
        viewModelScope.launch {
            container.vaultRepository.autoLockMinutes.collect { minutes ->
                _ui.value = _ui.value.copy(autoLockMinutes = minutes)
            }
        }
        viewModelScope.launch {
            container.vaultRepository.hideEntry.collect { hide ->
                _ui.value = _ui.value.copy(hideEntry = hide)
            }
        }
    }

    private fun loadItems() {
        viewModelScope.launch {
            val rows = container.vaultRepository.observeItems().first()
            val media = rows.map { container.vaultRepository.toVideoMedia(it, it.displayName) }
                .filter { it.path != null }
            _ui.value = _ui.value.copy(items = rows, media = media)
        }
    }

    fun unlock(pin: String) {
        viewModelScope.launch {
            when (val result = container.vaultRepository.unlock(pin)) {
                is VaultRepository.UnlockResult.Unlocked -> {
                    _ui.value = _ui.value.copy(error = null, lockedUntil = 0)
                    loadItems()
                }

                is VaultRepository.UnlockResult.Duress -> {
                    // 胁迫 PIN：进入但只给空列表，看起来像「隐私空间是空的」
                    _ui.value = _ui.value.copy(items = emptyList(), media = emptyList(), error = null)
                }

                is VaultRepository.UnlockResult.LockedOut -> _ui.value = _ui.value.copy(
                    error = "尝试次数过多，请 ${((result.untilMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)} 秒后再试",
                    lockedUntil = result.untilMillis,
                )

                is VaultRepository.UnlockResult.WrongPin -> _ui.value = _ui.value.copy(error = "PIN 不正确")
            }
        }
    }

    fun lock() = container.vaultRepository.lock()

    fun setPin(pin: String, duress: String?, autoLockMinutes: Int, hideEntry: Boolean) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            container.vaultRepository.createPin(pin)
            if (!duress.isNullOrBlank()) container.vaultRepository.setDuressPin(duress)
            container.vaultRepository.setAutoLockMinutes(autoLockMinutes)
            container.vaultRepository.setHideEntry(hideEntry)
            _ui.value = VaultUiState(configured = true, unlocked = true, busy = false)
        }
    }

    fun changePin(oldPin: String, newPin: String) {
        viewModelScope.launch {
            val ok = container.vaultRepository.changePin(oldPin, newPin)
            _ui.value = _ui.value.copy(error = if (ok) null else "原 PIN 不正确")
        }
    }

    fun moveOut(key: MediaKey) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val ok = container.vaultRepository.moveOut(key)
            _ui.value = _ui.value.copy(
                busy = false,
                error = if (ok) null else "移出失败：目标目录不可写或空间不足",
            )
            loadItems()
        }
    }

    /** 只撤销「隐藏」标记，不动文件。 */
    fun removeFromVault(key: MediaKey) {
        viewModelScope.launch {
            container.vaultRepository.moveOut(key)
            loadItems()
        }
    }

    fun disable(deleteFiles: Boolean) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            container.vaultRepository.disable(restoreFiles = !deleteFiles)
            if (deleteFiles) {
                container.vaultRepository.vaultDir().listFiles()?.forEach { it.delete() }
            }
            _ui.value = VaultUiState(busy = false)
        }
    }

    fun mediaFor(key: MediaKey): VideoMedia? = _ui.value.media.firstOrNull { it.key == key }
}
