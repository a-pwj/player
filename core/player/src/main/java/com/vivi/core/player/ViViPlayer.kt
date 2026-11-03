package com.vivi.core.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 一条待播请求，已由 app 层解析成播放器可直接消费的 Uri。 */
data class PlaybackRequest(
    val key: String,
    val uri: Uri,
    val title: String,
    val startPositionMicros: Long = 0,
    val speed: Float = 1f,
    val preferredTextLanguage: String? = null,
    val preferredAudioLanguage: String? = null,
)

/** ffmpeg 抽出或用户导入的文本字幕，在 app 内渲染。 */
data class RenderedSubtitle(
    val label: String,
    val cues: List<SubtitleCue>,
    val format: SubtitleFormat = SubtitleFormat.SRT,
)

/**
 * ExoPlayer 的状态收敛层。
 *
 * Media3 的回调是碎片化的（位置要轮询、轨道要读 currentTracks、字幕走 TextOutput），这里收敛成
 * 一个 StateFlow 给 UI 订阅。三条刻意的设计：
 * - 字幕一律自渲染（内封走 TextOutput，外挂走解析），这样延迟/缩放/描边设置对内封外挂都生效；
 * - 轨道切换用 TrackSelectionOverride，避免选了之后又被语言偏好改回去；
 * - 连续播放用多 MediaItem 队列，而不是「播完手动下一条」，通知栏/锁屏的上下一条才有意义。
 */
class ViViPlayer(
    context: Context,
    private val scope: CoroutineScope,
    private val configuration: PlayerConfiguration = PlayerConfiguration(),
) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val player: ExoPlayer = build()

    private var positionJob: Job? = null
    private var embeddedCues: List<SubtitleCue> = emptyList()
    private var externalSubtitle: RenderedSubtitle? = null
    private var queue: List<QueueEntry> = emptyList()
    private var isSeeking = false
    private var seekHoldUntilElapsed = 0L

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            publish()
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            _state.update { it.copy(videoWidth = size.width, videoHeight = size.height) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            refreshTracks(tracks)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            externalSubtitle = null
            embeddedCues = emptyList()
            val entry = queue.getOrNull(player.currentMediaItemIndex)
            _state.update {
                it.copy(
                    title = entry?.title ?: mediaItem?.mediaId.orEmpty(),
                    errorMessage = null,
                )
            }
            publish()
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update {
                it.copy(
                    status = PlayerStatus.ERROR,
                    errorMessage = "${error.errorCodeName}：${error.message ?: "播放失败"}",
                )
            }
        }

        override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
            _state.update { it.copy(speed = parameters.speed) }
        }
    }

    init {
        player.addListener(playerListener)
        // ExoPlayer 只允许在创建它的线程（主线程 looper）上访问，进度轮询必须回到 Main
        positionJob = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                publish()
                delay(POSITION_TICK_MS)
            }
        }
    }

    private fun build(): ExoPlayer {
        val renderers = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                configuration.minBufferMicros / 1000,
                configuration.maxBufferMicros / 1000,
                configuration.playbackBufferMicros / 1000,
                configuration.additionalBufferMicros / 1000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderers)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setSeekBackIncrementMs(configuration.doubleTapSeekSec * 1000L)
            .setSeekForwardIncrementMs(configuration.doubleTapSeekSec * 1000L)
            .build()
            .apply {
                repeatMode = configuration.repeatMode.toMedia3()
                playWhenReady = configuration.startPaused
            }
    }

    fun setQueue(entries: List<QueueEntry>, startIndex: Int, startPositionMicros: Long) {
        queue = entries
        val items = entries.map { entry ->
            // 通知栏与锁屏卡片读的是 MediaMetadata，不给标题就只剩应用名
            val metadata = MediaMetadata.Builder()
                .setTitle(entry.title)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MOVIE)
                .apply { if (entry.durationMicros > 0) setDurationMs(entry.durationMicros / 1000L) }
                .build()
            MediaItem.Builder().setUri(entry.uri).setMediaId(entry.key).setMediaMetadata(metadata).build()
        }
        _state.update { it.copy(title = entries.getOrNull(startIndex)?.title.orEmpty()) }
        player.setMediaItems(
            items,
            startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            (startPositionMicros / 1000L).coerceAtLeast(0L),
        )
        player.prepare()
        publish()
    }

    data class QueueEntry(
        val key: String,
        val uri: Uri,
        val title: String,
        val durationMicros: Long = 0,
    )

    fun play() {
        player.playWhenReady = true
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun togglePlayPause() {
        player.playWhenReady = !player.playWhenReady
    }

    fun seekTo(positionMicros: Long) {
        isSeeking = true
        seekHoldUntilElapsed = android.os.SystemClock.elapsedRealtime() + SEEK_HOLD_MS
        player.seekTo((positionMicros / 1000L).coerceAtLeast(0L))
        _state.update { it.copy(positionMicros = positionMicros) }
        publish()
    }

    fun seekBy(seconds: Int) {
        val duration = _state.value.durationMicros
        val target = (_state.value.positionMicros + seconds * 1_000_000L)
            .coerceIn(0L, duration.coerceAtLeast(0L))
        seekTo(target)
    }

    fun seekToChapter(index: Int) {
        _state.value.chapters.getOrNull(index)?.let { seekTo(it.startMicros) }
    }

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(0)
    }

    /** 播放超过 10 秒时「上一条」的语义是重播当前条目，和其它播放器一致。 */
    fun previous() {
        if (player.currentPosition / 1000 > RESTART_THRESHOLD_SEC) {
            player.seekTo(0)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        }
    }

    /** 上下滑切换视频时的「上一条」：始终换条目，不做「超过 10 秒先重播」。 */
    fun previousItem() {
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0)
    }

    fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        player.playbackParameters = player.playbackParameters.withSpeed(clamped)
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        player.volume = clamped
        _state.update { it.copy(volume = clamped, isMuted = clamped == 0f) }
    }

    fun setMuted(muted: Boolean) {
        if (muted) {
            _state.update { it.copy(volume = player.volume) }
            player.volume = 0f
        } else {
            player.volume = _state.value.volume.coerceAtLeast(0.05f)
        }
        _state.update { it.copy(isMuted = muted) }
    }

    fun setRepeatMode(mode: RepeatMode) {
        configuration.repeatMode = mode
        player.repeatMode = mode.toMedia3()
    }

    fun setAspectMode(mode: AspectMode) {
        _state.update { it.copy(aspectMode = mode) }
        player.videoScalingMode = when (mode) {
            AspectMode.CROP_16_9, AspectMode.CROP_4_3, AspectMode.CROP_FILL, AspectMode.ZOOM_2 ->
                C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            else -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }

    fun setChapters(chapters: List<ChapterMarker>) {
        _state.update { it.copy(chapters = chapters) }
    }

    fun setPreferredLanguages(text: String?, audio: String?) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (text != null) setPreferredTextLanguage(text)
            if (audio != null) setPreferredAudioLanguage(audio)
        }.build()
    }

    fun disableSubtitles() {
        externalSubtitle = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        _state.update { it.copy(selectedSubtitleGroup = null, externalSubtitleCount = 0) }
    }

    fun selectSubtitle(groupId: Int, trackIndex: Int = 0) {
        externalSubtitle = null
        val override = overrideFor(groupId, trackIndex)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .apply { if (override != null) setOverrideForType(override) else clearOverridesOfType(C.TRACK_TYPE_TEXT) }
            .build()
    }

    fun selectAudio(groupId: Int, trackIndex: Int = 0) {
        val override = overrideFor(groupId, trackIndex)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            if (override != null) setOverrideForType(override) else clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        }.build()
    }

    fun applyRenderedSubtitle(subtitle: RenderedSubtitle) {
        externalSubtitle = subtitle
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        _state.update { it.copy(externalSubtitleCount = subtitle.cues.size) }
        publish()
    }

    fun setSubtitleStyle(scale: Float, delayMicros: Long) {
        _state.update { it.copy(subtitleScale = scale, subtitleDelayMicros = delayMicros) }
    }

    fun setPreparingNote(note: String?) {
        _state.update { it.copy(preparingNote = note) }
    }

    /** 当前正在播放队列里的下标，UI 用来显示「第 x / y 集」。 */
    fun currentIndex(): Int = player.currentMediaItemIndex

    fun queueSize(): Int = queue.size

    fun release() {
        positionJob?.cancel()
        player.removeListener(playerListener)
        player.release()
    }

    private fun publish() {
        if (isSeeking && android.os.SystemClock.elapsedRealtime() > seekHoldUntilElapsed) isSeeking = false
        val positionMicros = if (isSeeking) {
            _state.value.positionMicros
        } else {
            player.currentPosition * 1000
        }
        embeddedCues = player.currentCues.cues.mapNotNull { cue ->
            cue.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { SubtitleCue(0, Long.MAX_VALUE, it) }
        }
        val durationMicros = if (player.duration == C.TIME_UNSET) 0L else player.duration * 1000L
        val status = when {
            player.playbackState == Player.STATE_ENDED -> PlayerStatus.ENDED
            player.playbackState == Player.STATE_IDLE -> PlayerStatus.IDLE
            player.isPlaying -> PlayerStatus.PLAYING
            player.playbackState == Player.STATE_BUFFERING -> PlayerStatus.PREPARING
            else -> PlayerStatus.PAUSED
        }
        _state.update {
            it.copy(
                status = if (it.errorMessage != null && status == PlayerStatus.IDLE) PlayerStatus.ERROR else status,
                positionMicros = positionMicros,
                durationMicros = durationMicros,
                bufferedMicros = (player.bufferedPosition * 1000).coerceAtLeast(positionMicros),
                activeCues = cuesAt(positionMicros),
                hasNext = player.hasNextMediaItem() || it.repeatMode != RepeatMode.OFF,
                hasPrevious = player.hasPreviousMediaItem() || it.repeatMode != RepeatMode.OFF,
            )
        }
    }

    private fun cuesAt(positionMicros: Long): List<SubtitleCue> {
        val rendered = externalSubtitle
        val source = rendered?.cues ?: embeddedCues
        if (source.isEmpty()) return emptyList()
        val shifted = positionMicros - _state.value.subtitleDelayMicros
        return if (rendered != null) {
            source.filter { shifted >= it.startMicros && shifted <= it.endMicros }
        } else {
            // 内封字幕由 ExoPlayer 决定何时有效，这里只做延迟修正
            source
        }
    }

    private fun overrideFor(groupId: Int, trackIndex: Int): TrackSelectionOverride? {
        val group = player.currentTracks.groups.getOrNull(groupId) ?: return null
        return TrackSelectionOverride(group.mediaTrackGroup, Collections.singletonList(trackIndex))
    }

    private fun refreshTracks(tracks: Tracks) {
        val audio = ArrayList<TrackOption>()
        val text = ArrayList<TrackOption>()
        var selectedAudio: Int? = null
        var selectedText: Int? = null
        tracks.groups.forEachIndexed { index, group ->
            val options = (0 until group.length).mapNotNull { trackIndex ->
                if (!group.isTrackSupported(trackIndex)) return@mapNotNull null
                val format = group.getTrackFormat(trackIndex)
                TrackOption(
                    groupId = index,
                    trackIndex = trackIndex,
                    label = format.label ?: format.language ?: "轨道 ${trackIndex + 1}",
                    language = format.language,
                    codec = format.codecs ?: format.sampleMimeType,
                    isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                )
            }
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    if (group.isSelected) selectedAudio = index
                    audio += options
                }

                C.TRACK_TYPE_TEXT -> {
                    if (group.isSelected) selectedText = index
                    text += options
                }

                else -> Unit
            }
        }
        _state.update {
            it.copy(
                audioTracks = audio,
                subtitleTracks = text,
                selectedAudioGroup = selectedAudio,
                selectedSubtitleGroup = selectedText,
            )
        }
    }

    private companion object {
        const val POSITION_TICK_MS = 120L
        const val SEEK_HOLD_MS = 260L
        const val RESTART_THRESHOLD_SEC = 10L
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
    }
}

private fun RepeatMode.toMedia3(): Int = when (this) {
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
}
