package com.vivi.player.ui.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VideoLabel
import androidx.compose.material.icons.filled.ZoomInMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.vivi.core.ffmpeg.ThumbnailService
import com.vivi.core.player.AspectMode
import com.vivi.core.player.PlayerStatus
import com.vivi.core.player.PlayerUiState
import com.vivi.core.player.RepeatMode
import com.vivi.core.player.ViViPlayer
import com.vivi.player.MainActivity
import com.vivi.player.di.PlaybackHolder
import com.vivi.player.di.appContainer
import com.vivi.player.di.appViewModel
import com.vivi.player.service.PlaybackCoordinator
import com.vivi.player.service.PrepStage
import com.vivi.player.service.PrepState
import com.vivi.player.ui.common.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CONTROLS_TIMEOUT_MS = 3_500L

@Composable
fun PlayerScreen(
    mediaKey: com.vivi.core.data.model.MediaKey? = null,
    directUri: Uri? = null,
    /** 非空时按这个播放列表的顺序连播。 */
    playlistId: String? = null,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    confirmDelete: (android.app.PendingIntent) -> Unit,
) {
    val container = appContainer()
    val vm = appViewModel(PlayerViewModel::class) {
        PlayerViewModel(PlaybackHolder.ensure(it.context, it), it)
    }
    val state by vm.state.collectAsState()
    val prep by vm.prep.collectAsState()
    val sheet by vm.sheet.collectAsState()
    val choices by vm.subtitleChoices.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    // 默认竖屏，横竖屏由界面上的旋转按钮手动切换
    var landscape by rememberSaveable { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(0L) }
    var hud by remember { mutableStateOf<Hud?>(null) }
    var bottomSheet by remember { mutableStateOf<SheetKind?>(null) }

    val pipActive = container.pipActive.collectAsState().value
    LaunchedEffect(mediaKey, directUri, playlistId) {
        val key = mediaKey
        when {
            key != null && playlistId != null -> {
                val queue = container.playlists.entriesOnce(playlistId).map { it.key }
                vm.openKeyInPlaylist(key, queue)
            }

            key != null -> vm.openKey(key)
            else -> directUri?.let(vm::openUri)
        }
    }

    LaunchedEffect(controlsVisible, state.status) {
        if (controlsVisible && state.status == PlayerStatus.PLAYING && !locked) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(hud) {
        val current = hud ?: return@LaunchedEffect
        // 加速提示要一直显示到松手，其它提示短暂显示即可
        if (current is Hud.Speed) return@LaunchedEffect
        delay(900)
        hud = null
    }
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            vm.persistProgress()
        }
    }
    DisposableEffect(activity, landscape) {
        activity?.requestedOrientation = if (landscape) {
            // 用固定 LANDSCAPE 而不是 SENSOR_LANDSCAPE：MIUI 在系统「自动旋转」关掉时会
            // 忽略所有 sensor 系朝向，点旋转按钮没反应（模拟器能转、Redmi 10X 不能转）。
            // 这是手动切换，不需要跟着重力走。
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // 播放器全程沉浸：状态栏/导航栏一律隐藏，控件叠加层自己处理安全区。
    // 必须是 DisposableEffect：整个 App 只有一个 Activity，系统栏隐藏状态挂在 Window 上，
    // 返回首页时如果不在 onDispose 里还回来，首页 Scaffold 读到的 statusBars inset 仍是 0，
    // 顶栏会被状态栏压住（MIUI 上实测）。
    DisposableEffect(activity, pipActive) {
        applyImmersive(activity, hideSystemUi = !pipActive)
        onDispose { applyImmersive(activity, hideSystemUi = false) }
    }

    val subtitleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importExternalSubtitle(it) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(state.durationMicros, locked, pipActive) {
                if (locked || pipActive) return@pointerInput
                val widthPx = size.width.toFloat()
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        val step = PlayerGestures.doubleTapSeekSeconds(offset.x, widthPx, vm.doubleTapSeekSec)
                        if (step != 0) {
                            vm.seekBy(step)
                            hud = Hud.Seek(step.toLong())
                        }
                    },
                    // 长按由下面的独立手势处理（需要在松手时恢复倍速）
                    onLongPress = { },
                )
            }
            .pointerInput(state.durationMicros, locked, pipActive) {
                if (locked || pipActive) return@pointerInput
                // 长按屏幕 = 临时倍速（设置里可调，默认 1.25x），松手立即恢复
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    val startY = down.position.y
                    var boosting = false
                    var moved = 0f
                    while (true) {
                        // 带超时等事件：手指按住不动（或注入的定点长按）不会产生中间 MOVE，
                        // 只等 MOVE 的话长按倍速永远起不来，所以超时也要按墙上时间判一次。
                        val event = withTimeoutOrNull(50L) { awaitPointerEvent(PointerEventPass.Main) }
                        val change = event?.changes?.firstOrNull()
                        if (change != null) {
                            // 只看物理按下状态：点按探测器会消费 down，用它当结束条件的话长按永远进不来
                            if (!change.pressed) {
                                if (boosting) {
                                    vm.endLongPressBoost()
                                    hud = null
                                }
                                break
                            }
                            moved = kotlin.math.abs(change.position.x - startX).coerceAtLeast(
                                kotlin.math.abs(change.position.y - startY),
                            )
                        }
                        val now = change?.uptimeMillis ?: android.os.SystemClock.uptimeMillis()
                        if (!boosting && PlayerGestures.shouldBoost(now, down.uptimeMillis, moved)) {
                            boosting = true
                            hud = Hud.Speed(vm.beginLongPressBoost())
                        }
                    }
                }
            }
            .pointerInput(state.durationMicros, locked, pipActive, vm.gestureSeekEnabled) {
                if (locked || pipActive || state.durationMicros <= 0 || !vm.gestureSeekEnabled) return@pointerInput
                val widthPx = size.width.toFloat()
                detectHorizontalDragGestures(
                    onDragStart = {
                        scrubbing = true
                        scrubPosition = state.positionMicros
                        controlsVisible = true
                    },
                    onDragEnd = {
                        vm.seekTo(scrubPosition)
                        scrubbing = false
                    },
                    onDragCancel = { scrubbing = false },
                ) { _, dragAmount ->
                    val fraction = (dragAmount / widthPx).coerceIn(-0.2f, 0.2f)
                    scrubPosition = (scrubPosition + (fraction * state.durationMicros).toLong())
                        .coerceIn(0L, state.durationMicros)
                }
            }
            .pointerInput(locked, pipActive) {
                if (locked || pipActive) return@pointerInput
                // 上下滑 = 上一个/下一个视频（主流短视频式操作，不再用来调亮度和音量）
                val thresholdPx = PlayerGestures.SWITCH_MIN_DRAG_PX
                var totalDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd = {
                        when (val next = PlayerGestures.swipeSwitchesToNext(totalDrag, thresholdPx)) {
                            true -> {
                                vm.next()
                                hud = Hud.Track(next = true)
                            }

                            false -> {
                                vm.previousItem()
                                hud = Hud.Track(next = false)
                            }

                            null -> Unit
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                ) { _, dragAmount -> totalDrag += dragAmount }
            },
    ) {
        VideoSurface(vm.player, state.aspectMode, modifier = Modifier.fillMaxSize())

        SubtitleOverlay(state)

        if (!pipActive) {
            // 起播失败时不要叠一层播放控件，否则错误提示和按钮互相压住
            // 只用淡入淡出：AnimatedVisibility 默认的 enter 带 expandVertically，从 0 高度展开时
            // 底栏节点会被无障碍桥接缓存成 [0,0][0,0]（MIUI 上实测），TalkBack 与自动化都点不到。
            AnimatedVisibility(
                visible = controlsVisible && prep.stage != PrepStage.FAILED,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                ControlsOverlay(
                    state = state,
                    sheet = sheet,
                    scrubbing = scrubbing,
                    scrubPosition = scrubPosition,
                    locked = locked,
                    onBack = {
                        vm.persistProgress()
                        if (locked) locked = false else onBack()
                    },
                    onTogglePlay = vm::togglePlayPause,
                    onSeek = vm::seekTo,
                    onScrubStart = { scrubbing = true; controlsVisible = true },
                    onScrub = { scrubPosition = it },
                    onScrubEnd = { scrubbing = false; vm.seekTo(scrubPosition) },
                    onNext = vm::next,
                    onPrevious = vm::previous,
                    onSpeed = { bottomSheet = SheetKind.SPEED },
                    onSubtitles = { bottomSheet = SheetKind.SUBTITLE },
                    onAudio = { bottomSheet = SheetKind.AUDIO },
                    onAspect = { bottomSheet = SheetKind.ASPECT },
                    onMore = { bottomSheet = SheetKind.MORE },
                    onLock = { locked = true; controlsVisible = true },
                    onRotate = { landscape = !landscape },
                    onPip = {
                        if (Build.VERSION.SDK_INT >= 26) {
                            runCatching {
                                activity?.enterPictureInPictureMode(
                                    MainActivity.pipParamsFor(state.videoWidth, state.videoHeight)
                                )
                            }
                        }
                    },
                    onRepeat = vm::cycleRepeat,
                )
            }

            hud?.let { HudOverlay(it) }

            if (prep.stage == PrepStage.PROBING || prep.stage == PrepStage.REMUXING || prep.stage == PrepStage.TRANSCODING) {
                PrepOverlay(prep)
            }
            if (prep.stage == PrepStage.FAILED) {
                FailureOverlay(
                    prep = prep,
                    onBack = onBack,
                    onSettings = onOpenSettings,
                    onAllowTranscode = vm::retryWithTranscode,
                )
            }
        }
    }

    bottomSheet?.let { kind ->
        PlayerSheet(
            kind = kind,
            state = state,
            longPressSpeed = vm.longPressSpeed,
            choices = choices,
            onDismiss = { bottomSheet = null },
            onSpeed = vm::setSpeed,
            onSubtitle = vm::chooseSubtitle,
            onDisableSubtitle = vm::disableSubtitle,
            onPickSubtitleFile = { subtitleLauncher.launch(arrayOf("*/*")) },
            onAudio = vm::selectAudio,
            onAspect = { mode ->
                vm.setAspectMode(mode)
                bottomSheet = null
            },
            onRepeat = vm::cycleRepeat,
            onChapter = vm::seekToChapter,
            onSleep = { minutes ->
                if (minutes == 0) vm.pause() else scope.launch {
                    delay(minutes * 60_000L)
                    vm.pause()
                }
            },
        )
    }
}

private enum class SheetKind { SPEED, SUBTITLE, AUDIO, ASPECT, MORE }

private sealed interface Hud {
    data class Seek(val seconds: Long) : Hud
    data class Speed(val value: Float) : Hud
    data class Track(val next: Boolean) : Hud
}

@Composable
private fun VideoSurface(player: ViViPlayer, aspect: AspectMode, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = {
            // 必须用 layout 里的 texture_view：SurfaceView 那层会盖住所有 Compose 叠加层
            android.view.LayoutInflater.from(context)
                .inflate(com.vivi.player.R.layout.vivi_player_surface, null) as PlayerView
        },
        update = { view ->
            view.player = player.player
            view.resizeMode = when (aspect) {
                AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectMode.CROP_16_9, AspectMode.CROP_4_3, AspectMode.CROP_FILL, AspectMode.ZOOM_2 ->
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        onRelease = { view -> view.player = null },
    )
}

@Composable
private fun SubtitleOverlay(state: PlayerUiState) {
    if (state.activeCues.isEmpty()) return
    val scale = state.subtitleScale
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            state.activeCues.forEach { cue ->
                Text(
                    text = cue.text,
                    color = cue.primaryColorRgb?.let { rgb ->
                        Color(0xFF000000L or (rgb.toLong() and 0xFFFFFFL))
                    } ?: Color.White,
                    fontSize = (20 * scale).sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .padding(bottom = 3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x66000000))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ControlsOverlay(
    state: PlayerUiState,
    sheet: ThumbnailService.SpriteSheet?,
    scrubbing: Boolean,
    scrubPosition: Long,
    locked: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSpeed: () -> Unit,
    onSubtitles: () -> Unit,
    onAudio: () -> Unit,
    onAspect: () -> Unit,
    onMore: () -> Unit,
    onLock: () -> Unit,
    onRotate: () -> Unit,
    onPip: () -> Unit,
    onRepeat: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x59000000))
            .windowInsetsPadding(WindowInsets.safeDrawing),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = state.title,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                fontSize = 13.sp,
            )
            if (state.preparingNote != null) {
                Text("FFmpeg", color = Color(0xFFFFB08A), fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
            }
            IconButton(onClick = onRotate, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.ScreenRotation,
                    contentDescription = "切换横竖屏",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onLock, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.Lock, contentDescription = "锁定界面", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onMore, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!locked) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一个", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(52.dp)) {
                Icon(
                    imageVector = if (state.status == PlayerStatus.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "播放/暂停",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
            if (!locked) {
                IconButton(onClick = onNext, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一个", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }

        // 底部要避开手势导航条：贴着屏幕最下沿时，MIUI 的无障碍桥接会把这一行报成 [0,0][0,0]
        // （TalkBack 同样读不到），实测倍速/字幕/音轨/适应一排全在导航条区域里。
        Column(modifier = Modifier.padding(horizontal = 10.dp).windowInsetsPadding(WindowInsets.navigationBars)) {
            val shown = if (scrubbing) scrubPosition else state.positionMicros
            if (scrubbing) {
                ScrubPreview(sheet = sheet, positionMicros = scrubPosition, durationMicros = state.durationMicros)
            }
            ScrubBar(
                state = state,
                shownMicros = shown,
                onScrubStart = onScrubStart,
                onScrub = onScrub,
                onScrubEnd = onScrubEnd,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(shown), color = Color.White, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${(state.positionMicros * 100 / state.durationMicros.coerceAtLeast(1)).toInt()}%",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(formatDuration(state.durationMicros), color = Color.White, fontSize = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                if (!locked) {
                    TextButton(onClick = onSpeed, modifier = Modifier.height(34.dp)) {
                        Text("${state.speed}x", color = Color.White, fontSize = 11.sp)
                    }
                    TextButton(onClick = onSubtitles, modifier = Modifier.height(34.dp)) {
                        Icon(Icons.Filled.ClosedCaption, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("字幕", color = Color.White, fontSize = 11.sp)
                    }
                    TextButton(onClick = onAudio, modifier = Modifier.height(34.dp)) {
                        Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("音轨", color = Color.White, fontSize = 11.sp)
                    }
                    TextButton(onClick = onAspect, modifier = Modifier.height(34.dp)) {
                        Icon(Icons.Filled.VideoLabel, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(state.aspectMode.label(), color = Color.White, fontSize = 11.sp)
                    }
                    TextButton(onClick = onRepeat, modifier = Modifier.height(34.dp)) {
                        Icon(
                            imageVector = if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    TextButton(onClick = onPip, modifier = Modifier.height(34.dp)) {
                        Icon(Icons.Filled.PictureInPicture, contentDescription = "画中画", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/**
 * 拖动进度时的缩略图预览：从 FFmpeg 生成的雪碧图里裁出对应格子。
 * 雪碧图整张约 1600x900，只在进入拖动状态时解码一次并跟着 sheet 文件缓存。
 */
@Composable
private fun ScrubPreview(sheet: ThumbnailService.SpriteSheet?, positionMicros: Long, durationMicros: Long) {
    val tile = sheet?.tileAt(positionMicros)
    val sheetFile = if (tile != null) sheet?.sheetFor(tile) else null
    var bitmap by remember(sheetFile?.absolutePath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(sheetFile?.absolutePath) {
        bitmap = sheetFile?.takeIf { it.isFile }?.let { file ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 6.dp)) {
        Text(
            text = "${formatDuration(positionMicros)} / ${formatDuration(durationMicros)}",
            color = Color.White,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(4.dp))
        val image = bitmap
        if (tile != null && image != null && sheet != null) {
            val (offsetX, offsetY) = sheet.offsetOf(tile)
            Canvas(
                modifier = Modifier
                    .size(sheet.tileWidth.dp, sheet.tileHeight.dp)
                    .clip(RoundedCornerShape(6.dp)),
            ) {
                drawImage(
                    image = image.asImageBitmap(),
                    srcOffset = androidx.compose.ui.unit.IntOffset(offsetX, offsetY),
                    srcSize = androidx.compose.ui.unit.IntSize(sheet.tileWidth, sheet.tileHeight),
                    dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                )
            }
        }
    }
}

private fun AspectMode.label(): String = when (this) {
    AspectMode.FIT -> "适应"
    AspectMode.STRETCH -> "拉伸"
    AspectMode.CROP_16_9 -> "16:9"
    AspectMode.CROP_4_3 -> "4:3"
    AspectMode.CROP_FILL -> "填满"
    AspectMode.ZOOM_2 -> "放大"
}

@Composable
private fun ScrubBar(
    state: PlayerUiState,
    shownMicros: Long,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
) {
    val duration = state.durationMicros.coerceAtLeast(1L)
    val fraction = (shownMicros.toFloat() / duration).coerceIn(0f, 1f)
    // 不用 Material Slider：它这版不支持自定义尺寸，而这里要的是一条很细的进度条
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onScrubStart()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    onScrub(((down.position.x / width).coerceIn(0f, 1f) * duration).toLong())
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        onScrub(((change.position.x / width).coerceIn(0f, 1f) * duration).toLong())
                    }
                    onScrubEnd()
                }
            },
    ) {
        val y = size.height / 2
        val track = 2.5.dp.toPx()
        val active = 3.dp.toPx()
        drawRoundRect(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = androidx.compose.ui.geometry.Offset(0f, y - track / 2),
            size = androidx.compose.ui.geometry.Size(size.width, track),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(track / 2),
        )
        drawRoundRect(
            color = Color(0xFFFF8A65),
            topLeft = androidx.compose.ui.geometry.Offset(0f, y - active / 2),
            size = androidx.compose.ui.geometry.Size(size.width * fraction, active),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(active / 2),
        )
        drawCircle(color = Color.White, radius = 4.5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * fraction, y))
    }
}

/**
 * 操作反馈。倍速与切片的提示放在顶部小字，避免挡住画面中央；
 * 快进快退是瞬时动作，仍用居中提示。
 */
@Composable
private fun HudOverlay(hud: Hud) {
    when (hud) {
        is Hud.Seek -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    Icons.Filled.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = (if (hud.seconds > 0) "+" else "") + hud.seconds + " 秒",
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        }

        is Hud.Speed -> TopHud(text = "${hud.value}x 加速中", accent = true)
        is Hud.Track -> TopHud(text = if (hud.next) "下一个视频" else "上一个视频", accent = false)
    }
}

@Composable
private fun TopHud(text: String, accent: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = 6.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = text,
            color = if (accent) Color(0xFFFFB08A) else Color.White,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x80000000))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun PrepOverlay(prep: PrepState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(prep.note ?: "准备中…", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            if (prep.stage == PrepStage.REMUXING || prep.stage == PrepStage.TRANSCODING) {
                LinearProgressIndicator(progress = { prep.progress }, modifier = Modifier.width(200.dp))
                Spacer(Modifier.height(6.dp))
                Text("FFmpeg ${if (prep.stage == PrepStage.TRANSCODING) "转码" else "混流"}中，完成后可正常播放与拖动进度", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            } else {
                LinearProgressIndicator(modifier = Modifier.width(120.dp))
            }
        }
    }
}

@Composable
private fun FailureOverlay(
    prep: PrepState,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onAllowTranscode: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(prep.error ?: "播放失败", color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onBack) { Text("返回", color = Color.White) }
                if (prep.needsTranscodeConsent != null) {
                    TextButton(onClick = onAllowTranscode) { Text("用 FFmpeg 转码后播放", color = Color(0xFFFFB08A)) }
                }
                TextButton(onClick = onSettings) { Text("解码设置", color = Color.White) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(
    kind: SheetKind,
    state: PlayerUiState,
    longPressSpeed: Float,
    choices: List<PlaybackCoordinator.SubtitleChoice>,
    onDismiss: () -> Unit,
    onSpeed: (Float) -> Unit,
    onSubtitle: (PlaybackCoordinator.SubtitleChoice) -> Unit,
    onDisableSubtitle: () -> Unit,
    onPickSubtitleFile: () -> Unit,
    onAudio: (Int, Int) -> Unit,
    onAspect: (AspectMode) -> Unit,
    onRepeat: () -> Unit,
    onChapter: (Int) -> Unit,
    onSleep: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            when (kind) {
                SheetKind.SPEED -> {
                    item { SheetTitle("播放速度") }
                    item {
                        // 9 个速度放一行会被屏幕截断，1.5x 以上就点不到了
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f), key = { it }) { speed ->
                                FilterChip(
                                    selected = state.speed == speed,
                                    onClick = { onSpeed(speed) },
                                    label = { Text("${speed}x", fontSize = 12.sp) },
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            text = "长按屏幕临时 %.2fx、松手恢复（可在设置修改）".format(longPressSpeed),
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                SheetKind.SUBTITLE -> {
                    item { SheetTitle("字幕") }
                    item {
                        TextButton(onClick = onDisableSubtitle) { Text("关闭字幕") }
                    }
                    item {
                        TextButton(onClick = onPickSubtitleFile) { Text("选择本地字幕文件…") }
                    }
                    items(choices, key = { it.label }) { choice ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(choice.label, modifier = Modifier.weight(1f))
                            if (choice.unsupported) Text("位图字幕，无法转换", fontSize = 11.sp, color = Color.Gray)
                            else TextButton(onClick = { onSubtitle(choice); onDismiss() }) { Text("选用") }
                        }
                    }
                    if (choices.isEmpty()) {
                        item { Text("这条视频没有可用字幕", fontSize = 12.sp, color = Color.Gray) }
                    }
                }

                SheetKind.AUDIO -> {
                    item { SheetTitle("音轨") }
                    items(state.audioTracks, key = { "${it.groupId}-${it.trackIndex}" }) { track ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = buildString {
                                    append(track.label)
                                    track.codec?.let { append(" · $it") }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onAudio(track.groupId, track.trackIndex); onDismiss() }) { Text("选用") }
                        }
                    }
                    if (state.audioTracks.isEmpty()) item { Text("只有一条音轨", fontSize = 12.sp, color = Color.Gray) }
                }

                SheetKind.ASPECT -> {
                    item { SheetTitle("画面比例") }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AspectMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.aspectMode == mode,
                                    onClick = { onAspect(mode) },
                                    label = { Text(mode.label(), fontSize = 12.sp) },
                                )
                            }
                        }
                    }
                }

                SheetKind.MORE -> {
                    item { SheetTitle("更多") }
                    item {
                        TextButton(onClick = onRepeat) {
                            Text("循环：${when (state.repeatMode) { RepeatMode.OFF -> "不循环"; RepeatMode.ONE -> "单曲"; RepeatMode.ALL -> "列表" }}")
                        }
                    }
                    item { SheetTitle("睡眠定时") }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 45, 60, 90).forEach { minutes ->
                                FilterChip(selected = false, onClick = { onSleep(minutes) }, label = { Text("$minutes 分", fontSize = 12.sp) })
                            }
                            FilterChip(selected = false, onClick = { onSleep(0) }, label = { Text("取消", fontSize = 12.sp) })
                        }
                    }
                    if (state.chapters.isNotEmpty()) {
                        item { SheetTitle("章节") }
                        itemsIndexed(state.chapters) { index, chapter ->
                            TextButton(onClick = { onChapter(index) }) {
                                Text("${formatDuration(chapter.startMicros)} ${chapter.title}", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
}

private fun applyImmersive(activity: Activity?, hideSystemUi: Boolean) {
    val window = activity?.window ?: return
    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
    if (hideSystemUi) {
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
}
