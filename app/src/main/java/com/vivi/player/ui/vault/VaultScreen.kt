package com.vivi.player.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.VaultMode
import com.vivi.player.di.appViewModel
import com.vivi.player.ui.common.VideoCard

/** 隐私空间入口：未配置 → 引导设置；已锁 → 数字键盘；已解锁 → 私密列表。 */
@Composable
fun VaultScreen(
    onPlay: (MediaKey) -> Unit,
    onSetup: () -> Unit,
    onBack: () -> Unit,
) {
    val vm = appViewModel(VaultViewModel::class) { VaultViewModel(it) }
    val state by vm.ui.collectAsState()

    when {
        !state.configured -> VaultIntro(onSetup = onSetup, onBack = onBack)
        !state.unlocked -> PinPad(
            title = "输入隐私空间 PIN",
            error = state.error,
            onDone = vm::unlock,
            onBack = onBack,
        )

        else -> VaultList(
            state = state,
            onPlay = onPlay,
            onMoveOut = vm::moveOut,
            onUnhide = vm::removeFromVault,
            onLock = vm::lock,
            onChangePin = vm::changePin,
            onDisable = vm::disable,
            onBack = {
                vm.lock()
                onBack()
            },
        )
    }
}

@Composable
private fun VaultIntro(onSetup: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Top),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text("隐私空间", fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text(
            text = "两种强度可选：\n" +
                "· 仅在本应用隐藏 —— 文件不动，ViVi 的媒体库/搜索/文件夹里都不出现，但系统相册仍然能看到；\n" +
                "· 移入应用私有目录 —— 文件被复制进 App 私有目录并改成不可读文件名，其它应用与文件管理器都访问不到，" +
                "代价是占用内部存储。",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "PIN 以 PBKDF2 + 随机盐存储，不保存明文；连续输错会按次数递增锁定时间。" +
                "还可以设置「胁迫 PIN」：用它解锁时只显示空列表。",
            fontSize = 13.sp,
        )
        Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) { Text("设置 PIN 并开启") }
        TextButton(onClick = onBack) { Text("返回") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultList(
    state: VaultUiState,
    onPlay: (MediaKey) -> Unit,
    onMoveOut: (MediaKey) -> Unit,
    onUnhide: (MediaKey) -> Unit,
    onLock: () -> Unit,
    onChangePin: (String, String) -> Unit,
    onDisable: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var changePinStage by remember { mutableStateOf(ChangePinStage.OLD) }
    var showChangePin by remember { mutableStateOf(false) }
    var showDisable by remember { mutableStateOf(false) }
    var firstPin by remember { mutableStateOf("") }
    var keepFiles by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("隐私空间 · ${state.media.size} 条") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    TextButton(onClick = onLock) { Text("锁定") }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "隐私空间设置")
                    }
                    androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("修改 PIN") },
                            onClick = {
                                menuOpen = false
                                changePinStage = ChangePinStage.OLD
                                showChangePin = true
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("关闭隐私空间") },
                            onClick = {
                                menuOpen = false
                                showDisable = true
                            },
                        )
                    }
                },
            )
        },
    ) { insets ->
        if (state.media.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(insets), contentAlignment = Alignment.Center) {
                Text("隐私空间是空的。在媒体库里长按视频 → 移入隐私空间。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 168.dp),
                contentPadding = PaddingValues(
                    top = insets.calculateTopPadding(),
                    bottom = insets.calculateBottomPadding() + 12.dp,
                    start = 10.dp,
                    end = 10.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.media, key = { it.key.value }) { media ->
                    Column {
                        VideoCard(
                            media = media,
                            progress = null,
                            isFavorite = false,
                            isSelected = false,
                            onClick = { onPlay(media.key) },
                        )
                        val mode = state.items.firstOrNull { it.key == media.key }?.mode
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (mode == VaultMode.RELOCATED) {
                                TextButton(onClick = { onMoveOut(media.key) }) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(4.dp))
                                    Text("放回原目录")
                                }
                            } else {
                                TextButton(onClick = { onUnhide(media.key) }) { Text("取消隐藏") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showChangePin) {
        when (changePinStage) {
            ChangePinStage.OLD -> PinPad(
                title = "输入当前 PIN",
                error = state.error,
                onDone = { firstPin = it; changePinStage = ChangePinStage.NEW },
                onBack = { showChangePin = false },
            )

            ChangePinStage.NEW -> PinPad(
                title = "输入新 PIN",
                error = state.error,
                confirm = true,
                onDone = { pin ->
                    onChangePin(firstPin, pin)
                    showChangePin = false
                },
                onBack = { changePinStage = ChangePinStage.OLD },
            )
        }
    }

    if (showDisable) {
        AlertDialog(
            onDismissRequest = { showDisable = false },
            title = { Text("关闭隐私空间") },
            text = {
                Column {
                    Text("PIN 会被清除。已移入应用私有目录的文件可以选择放回原目录，否则将保留在私有目录但不再显示。")
                    androidx.compose.material3.FilterChip(
                        selected = keepFiles,
                        onClick = { keepFiles = !keepFiles },
                        label = { Text(if (keepFiles) "把文件放回原目录" else "删除文件") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDisable(!keepFiles)
                    showDisable = false
                }) { Text("确认关闭") }
            },
            dismissButton = { TextButton(onClick = { showDisable = false }) { Text("取消") } },
        )
    }
}

private enum class ChangePinStage { OLD, NEW }

/** 数字键盘：PIN 4–8 位，逐位显示圆点。 */
@Composable
fun PinPad(
    title: String,
    error: String?,
    onDone: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    confirm: Boolean = false,
    onConfirmMismatch: (() -> Unit)? = null,
) {
    var pin by remember { mutableStateOf("") }
    var firstRound by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (pin.length < 4) return
        if (!confirm) {
            // 提交后必须清空：否则输错一次再按，会把两次输入拼成 8 位，永远解不开
            val entered = pin
            pin = ""
            onDone(entered)
            return
        }
        when {
            firstRound == null -> {
                firstRound = pin
                pin = ""
            }

            firstRound == pin -> onDone(pin)

            else -> {
                onConfirmMismatch?.invoke()
                pin = ""
                firstRound = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (onBack != null) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                }
            }
            Text(
                text = if (confirm && firstRound != null) "再次输入以确认" else title,
                fontSize = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(8) { index ->
                    Box(
                        modifier = Modifier.size(14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = if (index < pin.length) {
                                Modifier
                                    .size(11.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            } else {
                                Modifier
                                    .size(7.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            },
                        )
                    }
                }
            }
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { ch ->
                        OutlinedButton(onClick = { if (pin.length < 8) pin += ch }, modifier = Modifier.size(64.dp)) {
                            Text(ch.toString(), fontSize = 22.sp)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pin = pin.dropLast(1) }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "退格")
                }
                OutlinedButton(onClick = { if (pin.length < 8) pin += '0' }, modifier = Modifier.size(64.dp)) {
                    Text("0", fontSize = 22.sp)
                }
                Button(onClick = ::submit, modifier = Modifier.size(64.dp)) {
                    Text("确定", fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 首次设置：两遍 PIN + 可选胁迫 PIN + 自动锁定时间。 */
@Composable
fun VaultSetupScreen(onDone: () -> Unit, onBack: () -> Unit) {
    val vm = appViewModel(VaultViewModel::class) { VaultViewModel(it) }
    val state by vm.ui.collectAsState()
    var step by remember { mutableStateOf(SetupStep.ENTER_PIN)
    }
    var pin by remember { mutableStateOf("") }
    var duress by remember { mutableStateOf<String?>(null) }
    var autoLock by remember { mutableStateOf(1) }
    var hideEntry by remember { mutableStateOf(false) }

    when (step) {
        SetupStep.ENTER_PIN -> PinPad(
            title = "设置 4–8 位 PIN",
            error = state.error,
            confirm = true,
            onConfirmMismatch = { },
            onDone = { entered ->
                pin = entered
                step = SetupStep.DURESS
            },
            onBack = onBack,
        )

        SetupStep.DURESS -> Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("胁迫 PIN（可选）", fontSize = 17.sp)
            Text(
                "如果有人逼你打开隐私空间，用这个 PIN 解锁：界面看起来正常，但列表是空的。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(value = duress ?: "", onValueChange = { duress = it }, label = { Text("留空表示不设置") }, singleLine = true)
            Text("自动锁定", fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 5, 15).forEach { minutes ->
                    FilterChip(
                        selected = autoLock == minutes,
                        onClick = { autoLock = minutes },
                        label = { Text(if (minutes == 0) "不自动" else "$minutes 分钟") },
                    )
                }
            }
            FilterChip(
                selected = hideEntry,
                onClick = { hideEntry = !hideEntry },
                label = { Text("在首页隐藏入口") },
            )
            Button(
                onClick = {
                    vm.setPin(pin, duress?.takeIf { it.isNotBlank() }, autoLock, hideEntry)
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("完成") }
            TextButton(onClick = onBack) { Text("取消") }
        }
    }
}

private enum class SetupStep { ENTER_PIN, DURESS }
