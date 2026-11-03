package com.vivi.player.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.VideoMedia
import com.vivi.core.data.repository.PlaylistRepository
import com.vivi.player.di.appContainer
import com.vivi.player.ui.common.formatDuration
import com.vivi.player.ui.common.rememberThumbnail
import kotlinx.coroutines.launch

/**
 * 媒体库顶部的两个维度：整个库，或某个播放列表。
 * 分类（电影/剧集…）不再占一行，筛选面板里仍然可以按分类过滤。
 */
@Composable
fun LibraryModeChips(
    showPlaylists: Boolean,
    allCount: Int,
    playlistCount: Int,
    onPick: (Boolean) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        FilterChip(
            selected = !showPlaylists,
            onClick = { onPick(false) },
            label = { Text("全部 $allCount", fontSize = 13.sp) },
        )
        FilterChip(
            selected = showPlaylists,
            onClick = { onPick(true) },
            label = { Text("播放列表 $playlistCount", fontSize = 13.sp) },
        )
    }
}

@Composable
fun PlaylistsPanel(
    playlists: List<PlaylistRepository.Playlist>,
    allCount: Int,
    onPickMode: (Boolean) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item {
            LibraryModeChips(
                showPlaylists = true,
                allCount = allCount,
                playlistCount = playlists.size,
                onPick = onPickMode,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("播放列表", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建")
                }
            }
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    "还没有播放列表。在视频上长按 → 加入播放列表即可创建。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 18.dp),
                )
            }
        }
        items(playlists, key = { it.id }) { playlist ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .clickable { onOpenPlaylist(playlist.id) },
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(playlist.name, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${playlist.itemCount} 个视频", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "新建播放列表",
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                scope.launch { container.playlists.create(name) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    playlistId: String,
    onBack: () -> Unit,
    onPlay: (MediaKey, String) -> Unit,
) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val playlists by container.playlists.observePlaylists.collectAsState(initial = emptyList())
    val entries by container.playlists.observeEntries(playlistId).collectAsState(initial = emptyList())
    val playlist = playlists.firstOrNull { it.id == playlistId }

    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(playlist?.name ?: "播放列表", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) { Text("改名", fontSize = 13.sp) }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除列表")
                    }
                },
            )
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${entries.size} 个视频", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val first = entries.firstOrNull() ?: return@Button
                        onPlay(first.key, playlistId)
                    },
                    enabled = entries.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("播放全部")
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.key.value }) { media ->
                    PlaylistRow(
                        media = media,
                        onClick = { onPlay(media.key, playlistId) },
                        onRemove = { scope.launch { container.playlists.remove(playlistId, media.key) } },
                    )
                }
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = "重命名",
            initial = playlist?.name.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                scope.launch { container.playlists.rename(playlistId, name) }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除播放列表？") },
            text = { Text("只删除列表和里面的关联，视频文件不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        container.playlists.delete(playlistId)
                        onBack()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun PlaylistRow(media: VideoMedia, onClick: () -> Unit, onRemove: () -> Unit) {
    val thumbnail = rememberThumbnail(media)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 84.dp, height = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF16181C)),
            contentAlignment = Alignment.Center,
        ) {
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(media.displayName, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatDuration(media.durationMicros),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRemove) { Text("移出", fontSize = 12.sp) }
    }
}

/** 长按视频后的「加入播放列表」：可以选已有列表，也可以就地新建。 */
@Composable
fun AddToPlaylistDialog(mediaKey: MediaKey, onDismiss: () -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val playlists by container.playlists.observePlaylists.collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        NameDialog(
            title = "新建播放列表",
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                scope.launch {
                    val id = container.playlists.create(name)
                    container.playlists.add(id, mediaKey)
                    onDismiss()
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入播放列表") },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text("还没有播放列表，先新建一个。", fontSize = 13.sp)
                }
                playlists.forEach { playlist ->
                    TextButton(
                        onClick = {
                            scope.launch {
                                container.playlists.add(playlist.id, mediaKey)
                                onDismiss()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${playlist.name}（${playlist.itemCount}）", fontSize = 14.sp)
                    }
                }
                TextButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建列表", fontSize = 14.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
