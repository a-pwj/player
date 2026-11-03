package com.vivi.player.ui.library

import android.app.PendingIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivi.core.data.filter.DurationBand
import com.vivi.core.data.filter.Facets
import com.vivi.core.data.filter.LibraryFilter
import com.vivi.player.ui.playlist.AddToPlaylistDialog
import com.vivi.player.ui.playlist.LibraryModeChips
import com.vivi.player.ui.playlist.PlaylistsPanel
import com.vivi.core.data.filter.MediaCategory
import com.vivi.core.data.filter.ResolutionBand
import com.vivi.core.data.filter.SizeBand
import com.vivi.core.data.filter.WatchState
import com.vivi.core.data.model.FolderSummary
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.SortOrder
import com.vivi.core.data.model.VaultMode
import com.vivi.core.data.model.VideoMedia
import com.vivi.core.data.repository.ResumableItem
import com.vivi.player.di.appContainer
import com.vivi.player.di.appViewModel
import com.vivi.player.ui.common.BadgeText
import com.vivi.player.ui.common.VideoCard
import com.vivi.player.ui.common.formatDuration
import com.vivi.player.ui.common.formatSize

/**
 * 媒体库主界面：搜索 / 多维筛选 / 排序 / 多选（收藏、移入隐私空间、删除）/ 文件夹视图 / 继续观看。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    folderPath: String? = null,
    showFoldersOnly: Boolean = false,
    onPlay: (MediaKey) -> Unit,
    onPlayFromPlaylist: (MediaKey, String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenVault: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFoldersTab: () -> Unit,
    confirmDelete: (PendingIntent) -> Unit,
) {
    val container = appContainer()
    val vm = appViewModel(LibraryViewModel::class) { LibraryViewModel(it) }
    val state by vm.ui.collectAsState()

    var showFilter by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var detailsFor by remember { mutableStateOf<VideoMedia?>(null) }
    var addToPlaylistFor by remember { mutableStateOf<MediaKey?>(null) }
    var showPlaylists by remember { mutableStateOf(false) }
    val playlists by container.playlists.observePlaylists.collectAsState(initial = emptyList())
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    LaunchedEffect(folderPath) { vm.setFolder(folderPath) }

    val selection = state.selection
    val selecting = selection.isNotEmpty()

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("已选 ${selection.size} / ${state.visible.size}") },
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = "取消选择")
                        }
                    },
                    actions = {
                        TextButton(onClick = vm::selectAll) { Text("全选") }
                    },
                )
            } else {
                LibraryTopBar(
                    folderPath = folderPath,
                    showFoldersOnly = showFoldersOnly,
                    count = state.visible.size,
                    scanning = state.scanning,
                    searchActive = searchActive,
                    query = state.filter.search,
                    onQuery = vm::setSearch,
                    onToggleSearch = { searchActive = !searchActive },
                    filterActive = state.filter.isActive,
                    onFilter = { showFilter = true },
                    showSort = showSort,
                    onSortToggle = { showSort = !showSort },
                    onSortPick = { order ->
                        vm.setSort(order)
                        showSort = false
                    },
                    onRefresh = vm::refresh,
                    onOpenFoldersTab = onOpenFoldersTab,
                    onOpenVault = onOpenVault,
                    onOpenSettings = onOpenSettings,
                )
            }
        },
        bottomBar = {
            if (selecting) {
                BottomAppBar {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        TextButton(onClick = { selection.forEach(vm::toggleSelection); vm.toggleFavorite(selection.first()) }) {
                            Text("收藏")
                        }
                        TextButton(onClick = { vm.moveToVault(selection, VaultMode.HIDDEN_ONLY, onOpenVault) }) {
                            Text("隐藏")
                        }
                        TextButton(onClick = { vm.moveToVault(selection, VaultMode.RELOCATED, onOpenVault) }) {
                            Text("移入隐私空间")
                        }
                        TextButton(onClick = { vm.delete(selection, confirmDelete) }) { Text("删除") }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        if (showFoldersOnly) {
            FolderGrid(state.folders, onOpenFolder, insets)
        } else if (showPlaylists) {
            PlaylistsPanel(
                playlists = playlists,
                allCount = state.visible.size,
                onPickMode = { showPlaylists = it },
                onOpenPlaylist = onOpenPlaylist,
                contentPadding = insets,
            )
        } else {
            VideoGrid(
                state = state,
                contentPadding = insets,
                onPlay = onPlay,
                onSelect = vm::toggleSelection,
                onDetails = { detailsFor = it },
                showPlaylists = false,
                playlistCount = playlists.size,
                onPickMode = { showPlaylists = it },
            )
        }
    }

    if (showFilter) {
        FilterSheet(
            filter = state.filter,
            facets = state.facets,
            onDismiss = { showFilter = false },
            onChange = vm::updateFilter,
            onReset = vm::resetFilter,
        )
    }

    addToPlaylistFor?.let { key ->
        AddToPlaylistDialog(mediaKey = key, onDismiss = { addToPlaylistFor = null })
    }

    detailsFor?.let { media ->
        DetailsSheet(
            media = media,
            isFavorite = media.key in state.favorites,
            onDismiss = { detailsFor = null },
            onPlay = {
                detailsFor = null
                onPlay(media.key)
            },
            onFavorite = { vm.toggleFavorite(media.key) },
            onAddToPlaylist = {
                detailsFor = null
                addToPlaylistFor = media.key
            },
            onVault = { mode ->
                detailsFor = null
                vm.moveToVault(setOf(media.key), mode, onOpenVault)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    folderPath: String?,
    showFoldersOnly: Boolean,
    count: Int,
    scanning: Boolean,
    searchActive: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onToggleSearch: () -> Unit,
    filterActive: Boolean,
    onFilter: () -> Unit,
    showSort: Boolean,
    onSortToggle: () -> Unit,
    onSortPick: (SortOrder) -> Unit,
    onRefresh: () -> Unit,
    onOpenFoldersTab: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var moreOpen by remember { mutableStateOf(false) }
    Column {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = when {
                            showFoldersOnly -> "文件夹"
                            folderPath != null -> folderPath.substringAfterLast('/').ifBlank { folderPath }
                            else -> "媒体库"
                        },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (scanning) "扫描中…" else "$count 个视频",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            },
            actions = {
                IconButton(onClick = onToggleSearch) { Icon(Icons.Filled.Search, contentDescription = "搜索") }
                IconButton(onClick = onFilter) {
                    Box {
                        Icon(Icons.Filled.FilterAlt, contentDescription = "筛选")
                        if (filterActive) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = onSortToggle) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                    }
                    DropdownMenu(expanded = showSort, onDismissRequest = onSortToggle) {
                        SortOrder.entries.forEach { order ->
                            DropdownMenuItem(
                                text = { Text(order.label) },
                                onClick = { onSortPick(order) },
                            )
                        }
                    }
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "重新扫描") }
                // 其余入口收进「更多」，七个图标会把标题挤到竖排
                Box {
                    IconButton(onClick = { moreOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(text = { Text("文件夹") }, onClick = { moreOpen = false; onOpenFoldersTab() })
                        DropdownMenuItem(text = { Text("隐私空间") }, onClick = { moreOpen = false; onOpenVault() })
                        DropdownMenuItem(text = { Text("设置") }, onClick = { moreOpen = false; onOpenSettings() })
                    }
                }
            },
        )
        if (searchActive) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("搜索视频名") },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "清空")
                        }
                    }
                },
                singleLine = true,
            )
        }
    }
}

@Composable
private fun VideoGrid(
    state: LibraryUiState,
    contentPadding: PaddingValues,
    onPlay: (MediaKey) -> Unit,
    onSelect: (MediaKey) -> Unit,
    onDetails: (VideoMedia) -> Unit,
    showPlaylists: Boolean,
    playlistCount: Int,
    onPickMode: (Boolean) -> Unit,
) {
    val selecting = state.selection.isNotEmpty()
    if (state.visible.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (state.filter.isActive) "没有符合筛选条件的视频" else "还没有扫描到视频",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.filter.isActive) {
                    Text("点右上角筛选图标放宽条件，或重新扫描", fontSize = 12.sp)
                }
            }
        }
        return
    }

    val showHero = state.filter.folderPath == null && !state.filter.isActive

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 10.dp,
            end = 10.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (showHero && state.resumable.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "resumable") {
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    SectionTitle("继续观看")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        items(state.resumable, key = { it.media.key.value }) { entry ->
                            ResumableCard(entry = entry, onClick = { onPlay(entry.media.key) })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        if (showHero) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "categories") {
                LibraryModeChips(
                    showPlaylists = showPlaylists,
                    allCount = state.visible.size,
                    playlistCount = playlistCount,
                    onPick = onPickMode,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        items(state.visible, key = { it.key.value }) { media ->
            VideoCard(
                media = media,
                progress = state.progress[media.key],
                isFavorite = media.key in state.favorites,
                isSelected = media.key in state.selection,
                onClick = { if (selecting) onSelect(media.key) else onPlay(media.key) },
                onLongClick = { if (selecting) onSelect(media.key) else onDetails(media) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun ResumableCard(entry: ResumableItem, onClick: () -> Unit) {
    val thumbnail = com.vivi.player.ui.common.rememberThumbnail(entry.media)
    // 媒体库里的时长可能是 0（MediaStore 没给），要退回进度里存的时长，否则剩余时间算成 0 会误标「已看完」
    val totalMicros = entry.media.durationMicros.takeIf { it > 0 } ?: entry.progress.durationMicros
    val remaining = if (totalMicros > 0) (totalMicros - entry.progress.positionMicros).coerceAtLeast(0) else 0
    Column(
        modifier = Modifier
            .width(152.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16 / 10f)
                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                .background(Color(0xFF0B0B0F)),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = when {
                    entry.progress.completed -> "已看完"
                    remaining > 0 -> "还剩 ${formatDuration(remaining)}"
                    else -> "看到 ${formatDuration(entry.progress.positionMicros)}"
                },
                color = Color.White,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            if (entry.progress.fraction > 0f) {
                LinearProgressIndicator(
                    progress = { entry.progress.fraction },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        }
        Text(
            text = entry.media.displayName,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

@Composable
private fun FolderGrid(
    folders: List<FolderSummary>,
    onOpenFolder: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        items(folders, key = { it.path }) { folder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFolder(folder.path) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "${folder.videoCount} 个视频 · ${formatSize(folder.totalBytes)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    filter: LibraryFilter,
    facets: Facets?,
    onDismiss: () -> Unit,
    onChange: ((LibraryFilter) -> LibraryFilter) -> Unit,
    onReset: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                FilterGroup("分类") {
                    MediaCategory.entries.forEach { category ->
                        FilterChip(
                            selected = filter.category == category,
                            onClick = { onChange { it.copy(category = category) } },
                            label = { Text("${category.label} ${facets?.categories?.get(category) ?: 0}", fontSize = 12.sp) },
                        )
                    }
                }
            }
            item {
                FilterGroup("清晰度") {
                    ResolutionBand.entries.forEach { band ->
                        FilterChip(
                            selected = band in filter.resolutions,
                            onClick = {
                                onChange {
                                    it.copy(
                                        resolutions = if (band in it.resolutions) it.resolutions - band
                                        else it.resolutions + band
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = "${band.label} ${facets?.resolutions?.get(band) ?: 0}",
                                    fontSize = 12.sp,
                                )
                            },
                        )
                    }
                }
            }
            item {
                FilterGroup("时长") {
                    DurationBand.entries.forEach { band ->
                        FilterChip(
                            selected = band in filter.durations,
                            onClick = {
                                onChange {
                                    it.copy(
                                        durations = if (band in it.durations) it.durations - band else it.durations + band
                                    )
                                }
                            },
                            label = { Text(band.label, fontSize = 12.sp) },
                        )
                    }
                }
            }
            item {
                FilterGroup("大小") {
                    SizeBand.entries.forEach { band ->
                        FilterChip(
                            selected = band in filter.sizes,
                            onClick = {
                                onChange { it.copy(sizes = if (band in it.sizes) it.sizes - band else it.sizes + band) }
                            },
                            label = { Text(band.label, fontSize = 12.sp) },
                        )
                    }
                }
            }
            item {
                FilterGroup("观看状态") {
                    WatchState.entries.forEach { state ->
                        FilterChip(
                            selected = filter.watchState == state,
                            onClick = { onChange { it.copy(watchState = state) } },
                            label = { Text(state.uiLabel(), fontSize = 12.sp) },
                        )
                    }
                }
            }
            item {
                FilterGroup("容器格式") {
                    (facets?.containers ?: emptyMap()).entries.sortedByDescending { it.value }.forEach { entry ->
                        FilterChip(
                            selected = entry.key in filter.containers,
                            onClick = {
                                onChange {
                                    it.copy(
                                        containers = if (entry.key in it.containers) it.containers - entry.key
                                        else it.containers + entry.key
                                    )
                                }
                            },
                            label = { Text("${entry.key.uppercase()} ${entry.value}", fontSize = 12.sp) },
                        )
                    }
                }
            }
            item {
                FilterGroup("存储位置") {
                    (facets?.volumes ?: emptyList()).forEach { volume ->
                        FilterChip(
                            selected = filter.volume == volume,
                            onClick = { onChange { it.copy(volume = if (it.volume == volume) null else volume) } },
                            label = { Text(volume.substringAfterLast('/'), fontSize = 12.sp) },
                        )
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Checkbox(
                        checked = filter.favoritesOnly,
                        onCheckedChange = { checked -> onChange { it.copy(favoritesOnly = checked) } },
                    )
                    Text("只看收藏")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                    TextButton(onClick = onReset) { Text("重置") }
                    Button(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

private fun WatchState.uiLabel(): String = when (this) {
    WatchState.ALL -> "全部"
    WatchState.UNFINISHED -> "未看完"
    WatchState.FINISHED -> "已看完"
    WatchState.NEVER_PLAYED -> "没看过"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(
    media: VideoMedia,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onVault: (VaultMode) -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(media.displayName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BadgeText(formatDuration(media.durationMicros))
                BadgeText(formatSize(media.sizeBytes))
                if (media.resolutionLabel.isNotEmpty()) BadgeText(media.resolutionLabel)
                BadgeText(media.extension.uppercase().ifBlank { "?" })
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = media.path ?: media.uri.toString(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("播放")
                }
                TextButton(onClick = onFavorite) { Text(if (isFavorite) "取消收藏" else "收藏") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onAddToPlaylist) { Text("加入播放列表") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { onVault(VaultMode.HIDDEN_ONLY) }) { Text("仅在本应用隐藏") }
                TextButton(onClick = { onVault(VaultMode.RELOCATED) }) { Text("移入隐私空间目录") }
            }
        }
    }
}
