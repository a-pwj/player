package com.vivi.player.ui.library

import android.app.PendingIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vivi.core.data.filter.Facets
import com.vivi.core.data.filter.LibraryFilter
import com.vivi.core.data.filter.LibraryFilterEngine
import com.vivi.core.data.filter.MediaCategory
import com.vivi.core.data.filter.VideoClassifier
import com.vivi.core.data.model.FolderSummary
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.PlaybackProgress
import com.vivi.core.data.model.SortOrder
import com.vivi.core.data.model.VaultMode
import com.vivi.core.data.model.VideoMedia
import com.vivi.core.data.repository.DeleteResult
import com.vivi.core.data.repository.ResumableItem
import com.vivi.core.data.repository.VaultRepository
import com.vivi.player.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val visible: List<VideoMedia> = emptyList(),
    val totalCount: Int = 0,
    val resumable: List<ResumableItem> = emptyList(),
    val folders: List<FolderSummary> = emptyList(),
    val facets: Facets? = null,
    val favorites: Set<MediaKey> = emptySet(),
    val progress: Map<MediaKey, PlaybackProgress> = emptyMap(),
    val selection: Set<MediaKey> = emptySet(),
    val categories: Map<MediaKey, MediaCategory> = emptyMap(),
    val filter: LibraryFilter = LibraryFilter(),
    val scanning: Boolean = false,
    val message: String? = null,
)

/**
 * 媒体库状态。
 *
 * 各数据源单独收集再统一 recompute，而不是套 5 层 combine：筛选条件变化时只重算派生列表，
 * 不会重新触发一次存储扫描，反过来扫描完成也不会重置选择集。
 */
class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(LibraryUiState())
    val ui: StateFlow<LibraryUiState> = _ui.asStateFlow()

    private val filter = MutableStateFlow(LibraryFilter())
    private val selection = MutableStateFlow<Set<MediaKey>>(emptySet())
    private val scanning = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val items = MutableStateFlow<List<VideoMedia>>(emptyList())
    private val favorites = MutableStateFlow<Set<MediaKey>>(emptySet())
    private val progress = MutableStateFlow<Map<MediaKey, PlaybackProgress>>(emptyMap())
    private val vaultHidden = MutableStateFlow<Set<MediaKey>>(emptySet())
    private val resumable = MutableStateFlow<List<ResumableItem>>(emptyList())
    private val folders = MutableStateFlow<List<FolderSummary>>(emptyList())
    /** 「最小视频时长」设置，参与筛选而不是摆设。 */
    private val minDurationSec = MutableStateFlow(20)

    init {
        viewModelScope.launch {
            container.videoRepository.observeItems().collect {
                items.value = it
                recompute()
            }
        }
        viewModelScope.launch {
            container.playbackRepository.observeFavoriteKeys().collect {
                favorites.value = it
                recompute()
            }
        }
        viewModelScope.launch {
            container.playbackRepository.observeAllProgress().collect {
                progress.value = it
                recompute()
            }
        }
        viewModelScope.launch {
            container.vaultRepository.observeHiddenKeys().collect {
                vaultHidden.value = it
                recompute()
            }
        }
        viewModelScope.launch {
            container.playbackRepository.observeContinueWatching(12).collect { resumable.value = it }
        }
        viewModelScope.launch {
            container.videoRepository.observeFolders().collect { folders.value = it }
        }
        viewModelScope.launch { container.settings.settings.collect { settings ->
            if (settings.minVideoDurationSec != minDurationSec.value) {
                minDurationSec.value = settings.minVideoDurationSec
            }
            recompute()
        } }
        viewModelScope.launch { filter.collect { recompute() } }
        viewModelScope.launch { selection.collect { recompute() } }
        viewModelScope.launch { scanning.collect { recompute() } }
        viewModelScope.launch { message.collect { recompute() } }
        refresh()
    }

    private fun recompute() {
        val pool = items.value.filterNot { it.key in vaultHidden.value }
        val categories = pool.associate { it.key to VideoClassifier.classify(it) }
        val currentFilter = filter.value
        val effective = currentFilter.copy(minDurationMicros = minDurationSec.value * 1_000_000L)
        _ui.value = LibraryUiState(
            visible = LibraryFilterEngine.apply(pool, effective, favorites.value, progress.value, categories),
            totalCount = pool.size,
            resumable = resumable.value,
            folders = folders.value,
            facets = LibraryFilterEngine.facets(pool, effective, categories),
            favorites = favorites.value,
            progress = progress.value,
            selection = selection.value,
            categories = categories,
            filter = currentFilter,
            scanning = scanning.value,
            message = message.value,
        )
    }

    fun updateFilter(transform: (LibraryFilter) -> LibraryFilter) {
        filter.update(transform)
    }

    fun setSearch(query: String) = updateFilter { it.copy(search = query) }

    fun setSort(order: SortOrder) = updateFilter { it.copy(sort = order) }

    fun setFolder(path: String?) = updateFilter { it.copy(folderPath = path) }

    fun resetFilter() = updateFilter { LibraryFilter(sort = it.sort, search = it.search, folderPath = it.folderPath) }

    fun toggleSelection(key: MediaKey) {
        selection.update { if (key in it) it - key else it + key }
    }

    fun selectAll() {
        selection.value = _ui.value.visible.map { it.key }.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun toggleFavorite(key: MediaKey) = viewModelScope.launch {
        val added = container.playbackRepository.toggleFavorite(key)
        toast(if (added) "已加入收藏" else "已取消收藏")
    }

    fun refresh() {
        viewModelScope.launch {
            scanning.value = true
            recompute()
            val extra = container.videoRepository.refreshExtras()
            container.settings.setLastScan(System.currentTimeMillis())
            scanning.value = false
            toast("扫描完成：补充 $extra 条媒体库未索引的视频")
        }
    }

    /** 移入隐私空间。没配 PIN 时先去设置，避免用户以为文件凭空消失。 */
    fun moveToVault(keys: Set<MediaKey>, mode: VaultMode, onNeedSetup: () -> Unit) {
        viewModelScope.launch {
            val configured = runCatching { container.vaultRepository.isConfigured.first() }.getOrDefault(false)
            if (!configured) {
                toast("请先设置隐私空间 PIN")
                onNeedSetup()
                return@launch
            }
            val targets = keys.mapNotNull { key -> items.value.firstOrNull { it.key == key } }
            if (targets.isEmpty()) return@launch
            val outcome = container.vaultRepository.moveIn(targets, mode)
            toast(
                when (outcome.failure) {
                    VaultRepository.MoveFailure.NONE -> "已移入隐私空间 ${outcome.moved} 条"
                    VaultRepository.MoveFailure.NOT_ENOUGH_SPACE -> "内部存储剩余空间不足，移入失败"
                    VaultRepository.MoveFailure.SOURCE_MISSING -> "原文件已不存在，移入失败"
                }
            )
            if (outcome.moved > 0) clearSelection()
        }
    }

    fun delete(keys: Set<MediaKey>, onConfirmationRequired: (PendingIntent) -> Unit) {
        viewModelScope.launch {
            var deleted = 0
            for (key in keys) {
                val media = items.value.firstOrNull { it.key == key } ?: continue
                when (val result = container.videoRepository.delete(media)) {
                    is DeleteResult.Deleted -> deleted++
                    is DeleteResult.RequiresConfirmation -> onConfirmationRequired(result.pendingIntent)
                    is DeleteResult.Failed -> toast(result.message)
                }
            }
            if (deleted > 0) toast("已删除 $deleted 条")
            clearSelection()
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    private fun toast(text: String) {
        message.value = text
        recompute()
    }
}
