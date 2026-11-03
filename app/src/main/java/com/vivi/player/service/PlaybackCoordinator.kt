package com.vivi.player.service

import android.content.Context
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.vivi.core.data.model.MediaKey
import com.vivi.core.data.model.MediaSource
import com.vivi.core.data.model.TrackPreferences
import com.vivi.core.data.model.VideoMedia
import com.vivi.core.data.model.VideoMetadata
import com.vivi.core.data.repository.AppSettings
import com.vivi.core.ffmpeg.CompatibilityPolicy
import com.vivi.core.ffmpeg.MediaInfo
import com.vivi.core.ffmpeg.PlaybackPreparer
import com.vivi.core.ffmpeg.SubtitleService
import com.vivi.core.ffmpeg.ThumbnailService
import com.vivi.core.player.ChapterMarker
import com.vivi.core.player.PlayerStatus
import com.vivi.core.player.PlayerUiState
import com.vivi.core.player.RenderedSubtitle
import com.vivi.core.player.RepeatMode
import com.vivi.core.player.SubtitleFormat
import com.vivi.core.player.SubtitleParser
import com.vivi.core.player.ViViPlayer
import com.vivi.player.di.AppContainer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class PrepStage { IDLE, PROBING, REMUXING, TRANSCODING, READY, FAILED }

data class PrepState(
    val stage: PrepStage = PrepStage.IDLE,
    val progress: Float = 0f,
    val note: String? = null,
    val error: String? = null,
    val needsTranscodeConsent: String? = null,
    val usedFfmpeg: Boolean = false,
)

/**
 * 播放器的唯一所有者。
 *
 * 归 Service 持有（A 方案）：MediaSession、通知栏、锁屏控件、前台服务保护都挂在同一个
 * ExoPlayer 上，UI 只是观察者。这样退后台时进程有前台服务兜底，不会出现
 * 「通知栏什么都没有 + 十几秒后被 MIUI 杀掉」。
 *
 * 线程约束：ExoPlayer 只允许在主线程访问，所有触碰 player 的方法都切回 Main。
 */
class PlaybackCoordinator(
    private val appContext: Context,
    private val container: AppContainer,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _player: ViViPlayer = ViViPlayer(
        context = appContext,
        scope = scope,
        configuration = com.vivi.core.player.PlayerConfiguration(),
    )

    /** Service 用它建 MediaSession。 */
    val player: ViViPlayer get() = _player

    val state: StateFlow<PlayerUiState> = _player.state

    private val _prep = MutableStateFlow(PrepState())
    val prep: StateFlow<PrepState> = _prep.asStateFlow()

    private val _sheet = MutableStateFlow<ThumbnailService.SpriteSheet?>(null)
    val sheet: StateFlow<ThumbnailService.SpriteSheet?> = _sheet.asStateFlow()

    private val _current = MutableStateFlow<VideoMedia?>(null)
    val current: StateFlow<VideoMedia?> = _current.asStateFlow()

    private val _subtitleChoices = MutableStateFlow<List<SubtitleChoice>>(emptyList())
    val subtitleChoices: StateFlow<List<SubtitleChoice>> = _subtitleChoices.asStateFlow()

    data class SubtitleChoice(
        val label: String,
        val embeddedGroup: Int? = null,
        val embeddedTrack: Int = 0,
        val rendered: RenderedSubtitle? = null,
        /** 位图字幕（PGS/DVD/DVB）ExoPlayer 画不了，FFmpeg 也没法转成文本。 */
        val unsupported: Boolean = false,
    )

    /** 起播请求：要么给媒体库条目，要么给外部「用其他应用打开」的 Uri。 */
    data class OpenRequest(
        val key: MediaKey,
        /** 外部「用其他应用打开」时直接给地址；媒体库条目留空，由这里自己解析。 */
        val uri: Uri = Uri.EMPTY,
        val title: String = "",
        val folderPath: String? = null,
        /** 用户已点过「转码后播放」，本轮不再看设置快照，避免 DataStore 回流竞态。 */
        val forceVideoTranscode: Boolean = false,
        /** 播放列表连播：非空时队列就是这个顺序，不再按同目录自动排。 */
        val queueKeys: List<MediaKey> = emptyList(),
    )

    private var settings: AppSettings = AppSettings()
    private var probe: MediaInfo? = null
    private var progressJob: kotlinx.coroutines.Job? = null
    private var boosting = false
    private var speedBeforeBoost = 1f
    private var currentQueue: List<VideoMedia> = emptyList()
    private var lastRecoveredKey: String? = null

    init {
        scope.launch { container.settings.settings.collect { settings = it } }
        // 队列是交给 ExoPlayer 自己往前推进的，它不会走我们这套探测/混流流水线：
        // 播完一条接着播下一条不兼容格式时只会抛 Source error。这里把出错的那条重新送回入口。
        _player.player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = recoverFromError()
        })
    }

    private fun recoverFromError() {
        val failedKey = _player.player.currentMediaItem?.mediaId ?: return
        if (failedKey == lastRecoveredKey) return
        val media = currentQueue.firstOrNull { it.key.value == failedKey } ?: return
        lastRecoveredKey = failedKey
        open(OpenRequest(key = media.key, title = media.displayName, folderPath = media.folderPath))
    }

    fun applySettingsSnapshot(snapshot: AppSettings) {
        settings = snapshot
        _player.setSubtitleStyle(snapshot.subtitleScale, snapshot.subtitleDelayMicros)
    }

    fun open(request: OpenRequest) {
        scope.launch {
            _prep.value = PrepState(stage = PrepStage.PROBING, note = "正在读取媒体信息…")
            _current.value = resolveMedia(request)
            val plan = runCatching { buildPlan(request) }.getOrNull()
            if (plan == null) {
                if (_prep.value.stage != PrepStage.FAILED) {
                    _prep.value = PrepState(stage = PrepStage.FAILED, error = "找不到这个视频，可能已被移动或删除")
                }
                return@launch
            }
            applyPlan(plan)
        }
    }

    private suspend fun buildPlan(request: OpenRequest): Plan? {
        val media = _current.value ?: return null
        val info = runCatching { container.probeService.probe(media.uri) }.getOrNull()
        info?.let { saveMetadata(media, it) }

        val decision = CompatibilityPolicy.decide(
            info,
            media.extension,
            CompatibilityPolicy.RiskyBehaviour(
                transcodeRiskyVideo = false,
                transcodeRiskyAudio = settings.autoRemux,
            ),
        )
        val playableUri = when (decision) {
            is CompatibilityPolicy.Decision.Native -> media.uri

            is CompatibilityPolicy.Decision.TranscodeVideo ->
                if (!request.forceVideoTranscode && !settings.allowVideoTranscode) {
                    _prep.value = PrepState(
                        stage = PrepStage.FAILED,
                        error = decision.reason,
                        needsTranscodeConsent = decision.reason,
                    )
                    null
                } else {
                    prepare(media, info, decision, transcoding = true)
                }

            else -> prepare(media, info, decision, transcoding = false)
        } ?: return null

        val queue = buildQueue(media, request.queueKeys)
        val startIndex = queue.indexOfFirst { it.key == media.key }.coerceAtLeast(0)
        val resume = if (settings.resumePlayback) {
            container.playbackRepository.resumePositionFor(media.key, settings.resumeSkipUnderSec) ?: 0L
        } else {
            0L
        }
        return Plan(media, info, playableUri, queue, startIndex, resume, decision)
    }

    private data class Plan(
        val media: VideoMedia,
        val info: MediaInfo?,
        val playableUri: Uri,
        val queue: List<VideoMedia>,
        val startIndex: Int,
        val resumeMicros: Long,
        val decision: CompatibilityPolicy.Decision,
    )

    private fun applyPlan(plan: Plan) {
        currentQueue = plan.queue
        _player.setQueue(
            entries = plan.queue.mapIndexed { index, item ->
                ViViPlayer.QueueEntry(
                    key = if (index == plan.startIndex) plan.media.key.value else item.key.value,
                    uri = if (index == plan.startIndex) plan.playableUri else item.uri,
                    title = item.displayName,
                    durationMicros = if (index == plan.startIndex) plan.info?.durationMicros ?: item.durationMicros
                    else item.durationMicros,
                )
            },
            startIndex = plan.startIndex,
            startPositionMicros = plan.resumeMicros,
        )
        _player.setPreferredLanguages(
            settings.subtitlePreferredLanguage.ifBlank { null },
            settings.audioPreferredLanguage.ifBlank { null },
        )
        _player.setSubtitleStyle(settings.subtitleScale, settings.subtitleDelayMicros)
        plan.info?.let { mediaInfo ->
            _player.setChapters(
                mediaInfo.chapters.map { chapter ->
                    ChapterMarker(chapter.startMicros, chapter.endMicros, chapter.title ?: "章节")
                }
            )
        }
        _prep.value = PrepState(
            stage = PrepStage.READY,
            usedFfmpeg = plan.decision !is CompatibilityPolicy.Decision.Native,
            note = if (plan.decision is CompatibilityPolicy.Decision.Native) null
            else "已用 FFmpeg 处理：${plan.decision.reason}",
        )
        _player.play()
        startProgressSaver()
        buildSubtitleChoices(plan.media, plan.info)
        loadScrubSheet(plan.media, plan.info)
    }

    private suspend fun prepare(
        media: VideoMedia,
        info: MediaInfo?,
        decision: CompatibilityPolicy.Decision,
        transcoding: Boolean,
    ): Uri? {
        _prep.value = PrepState(
            stage = if (transcoding) PrepStage.TRANSCODING else PrepStage.REMUXING,
            note = decision.reason,
        )
        val output = container.playbackPreparer.prepare(
            source = media.uri,
            info = info,
            extension = media.extension,
            options = PlaybackPreparer.Options(allowVideoTranscode = transcoding),
            risky = CompatibilityPolicy.RiskyBehaviour(transcodeRiskyAudio = true),
            onProgress = { fraction ->
                scope.launch {
                    _prep.value = _prep.value.copy(
                        stage = if (transcoding) PrepStage.TRANSCODING else PrepStage.REMUXING,
                        progress = fraction,
                        note = decision.reason,
                    )
                }
            },
        )
        return when (output) {
            is PlaybackPreparer.Output.Prepared -> Uri.fromFile(output.file)
            is PlaybackPreparer.Output.Direct -> output.uri
            is PlaybackPreparer.Output.Failed -> {
                _prep.value = PrepState(stage = PrepStage.FAILED, error = "FFmpeg 处理失败：${output.message}")
                null
            }
        }
    }

    private suspend fun resolveMedia(request: OpenRequest): VideoMedia? {
        container.videoRepository.findByKey(request.key)?.let { return it }
        val vaultItem = container.vaultRepository.observeItems().first()
            .firstOrNull { it.key == request.key }
        if (vaultItem != null && File(vaultItem.currentPath).exists()) {
            return container.vaultRepository.toVideoMedia(vaultItem, vaultItem.displayName)
        }
        if (request.uri == Uri.EMPTY) return null
        return VideoMedia(
            key = request.key,
            uri = request.uri,
            displayName = request.title,
            path = request.uri.path,
            folderPath = request.folderPath,
            durationMicros = 0,
            sizeBytes = 0,
            width = 0,
            height = 0,
            dateAddedMillis = System.currentTimeMillis(),
            lastModifiedMillis = 0,
            source = MediaSource.SAF,
        )
    }

    private suspend fun buildQueue(media: VideoMedia, queueKeys: List<MediaKey>): List<VideoMedia> {
        if (queueKeys.isNotEmpty()) {
            // 播放列表连播：顺序以列表为准；列表里被移走的文件 resolveKeys 会自动跳过
            val ordered = container.videoRepository.resolveKeys(queueKeys)
            if (ordered.isNotEmpty()) return ordered
        }
        val folder = media.folderPath ?: return listOf(media)
        val siblings = container.videoRepository.snapshotItems()
            .filter { it.folderPath == folder && it.key != media.key }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
            .take(200)
        return (listOf(media) + siblings).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }

    private fun saveMetadata(media: VideoMedia, info: MediaInfo) {
        scope.launch {
            container.playbackRepository.saveMetadata(
                media.key,
                VideoMetadata(
                    key = media.key,
                    container = info.format.formatName,
                    videoCodec = info.primaryVideo?.codecName,
                    audioCodec = info.audioStreams.maxByOrNull { it.bitRate }?.codecName,
                    bitRate = info.bitrate,
                    frameRate = info.primaryVideo?.frameRate,
                    bitDepth = info.primaryVideo?.bitDepth,
                    hasEmbeddedSubtitles = info.subtitleStreams.isNotEmpty(),
                    audioTrackCount = info.audioStreams.size,
                    subtitleTrackCount = info.subtitleStreams.size,
                    probedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun buildSubtitleChoices(media: VideoMedia, info: MediaInfo?) {
        val embedded = _player.state.value.subtitleTracks.map { option ->
            SubtitleChoice(
                label = option.label,
                embeddedGroup = option.groupId,
                embeddedTrack = option.trackIndex,
                unsupported = option.codec?.lowercase() in CompatibilityPolicy.BITMAP_SUBTITLE_CODECS,
            )
        }
        _subtitleChoices.value = embedded
        val allSubtitles = info?.subtitleStreams.orEmpty()
        val assLike = allSubtitles.filter { it.codecName?.lowercase() in setOf("ass", "ssa") }
        if (assLike.isEmpty()) return
        scope.launch {
            val rendered = withContextIo {
                assLike.mapNotNull { stream ->
                    val outcome = container.subtitleService.extract(media.uri, media.key.value, stream, allSubtitles)
                    if (outcome.status != SubtitleService.ExtractResult.SUCCESS) return@mapNotNull null
                    val file = outcome.file ?: return@mapNotNull null
                    RenderedSubtitle(
                        label = "内封 ${stream.language ?: stream.index} (${stream.codecName})",
                        cues = SubtitleParser.parse(file),
                        format = if (file.name.endsWith(".ass")) SubtitleFormat.ASS else SubtitleFormat.SRT,
                    )
                }
            }
            _subtitleChoices.value = _subtitleChoices.value + rendered.map {
                SubtitleChoice(label = it.label, rendered = it)
            }
        }
    }

    fun importExternalSubtitle(uri: Uri) {
        val identity = _current.value?.key?.value ?: uri.toString()
        scope.launch {
            val file = withContextIo { container.subtitleService.importExternal(uri, identity) }
            if (file == null) {
                _prep.value = _prep.value.copy(note = "字幕文件读取失败")
                return@launch
            }
            val cues = withContextIo { SubtitleParser.parse(file) }
            val label = uri.lastPathSegment?.substringAfterLast('/') ?: "外挂字幕"
            val subtitle = RenderedSubtitle(label, cues, SubtitleParser.detect(file.name, emptyList()))
            _subtitleChoices.value = _subtitleChoices.value + SubtitleChoice(label, rendered = subtitle)
            _player.applyRenderedSubtitle(subtitle)
            _prep.value = _prep.value.copy(note = "已加载 $label（${cues.size} 条）")
        }
    }

    private fun loadScrubSheet(media: VideoMedia, info: MediaInfo?) {
        scope.launch {
            val duration = info?.durationMicros ?: media.durationMicros
            if (duration <= 0) return@launch
            _sheet.value = withContextIo {
                container.thumbnailService.cachedSheet(media.key.value)
                    ?: container.thumbnailService.ensureScrubSheet(media.uri, media.key.value, duration, info)
            }
        }
    }

    private fun startProgressSaver() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(3_000)
                persistProgress()
            }
        }
    }

    fun persistProgress() {
        val media = _current.value ?: return
        val snapshot = _player.state.value
        if (snapshot.durationMicros <= 0) return
        scope.launch {
            container.playbackRepository.saveProgress(
                key = media.key,
                positionMicros = snapshot.positionMicros,
                durationMicros = snapshot.durationMicros,
                preferences = TrackPreferences(
                    audioStreamIndex = snapshot.selectedAudioGroup,
                    subtitleStreamIndex = snapshot.selectedSubtitleGroup,
                    speed = snapshot.speed,
                ),
            )
        }
    }

    /** 用户点「转码后播放」：本轮强制转码，不能等 DataStore 回流。 */
    fun retryWithTranscode() {
        val media = _current.value ?: return
        scope.launch {
            container.settings.setAllowVideoTranscode(true)
            open(
                OpenRequest(
                    key = media.key,
                    uri = media.uri,
                    title = media.displayName,
                    folderPath = media.folderPath,
                    forceVideoTranscode = true,
                )
            )
        }
    }

    fun chooseSubtitle(choice: SubtitleChoice) {
        when {
            choice.rendered != null -> _player.applyRenderedSubtitle(choice.rendered)
            choice.embeddedGroup != null -> _player.selectSubtitle(choice.embeddedGroup, choice.embeddedTrack)
            else -> _player.disableSubtitles()
        }
    }

    fun disableSubtitle() = _player.disableSubtitles()

    fun selectAudio(groupId: Int, trackIndex: Int) = _player.selectAudio(groupId, trackIndex)

    fun setSpeed(speed: Float) = _player.setSpeed(speed)

    fun setVolume(volume: Float) = _player.setVolume(volume)

    fun seekTo(micros: Long) = _player.seekTo(micros)

    fun seekBy(seconds: Int) = _player.seekBy(seconds)

    fun togglePlayPause() = _player.togglePlayPause()

    fun next() = _player.next()

    fun previous() = _player.previous()

    fun setAspectMode(mode: com.vivi.core.player.AspectMode) = _player.setAspectMode(mode)

    fun seekToChapter(index: Int) = _player.seekToChapter(index)

    fun pause() = _player.pause()

    fun previousItem() = _player.previousItem()

    val gestureSeekEnabled: Boolean get() = settings.gestureSeek

    /** 双击屏幕快进/快退的步长，来自设置。 */
    val doubleTapSeekSec: Int get() = settings.doubleTapSeekSec

    fun cycleRepeat() {
        val next = when (_player.state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _player.setRepeatMode(next)
    }

    val longPressSpeed: Float get() = settings.longPressSpeed

    fun beginLongPressBoost(): Float {
        if (boosting) return longPressSpeed
        speedBeforeBoost = _player.state.value.speed
        boosting = true
        _player.setSpeed(longPressSpeed)
        return longPressSpeed
    }

    fun endLongPressBoost() {
        if (!boosting) return
        boosting = false
        _player.setSpeed(speedBeforeBoost)
    }

    /** 供 Activity 判断是否还值得进画中画。 */
    fun isPlayingNow(): Boolean = _player.state.value.status == PlayerStatus.PLAYING

    fun release() {
        persistProgress()
        progressJob?.cancel()
        _player.release()
        scope.cancel()
    }

    private suspend fun <T> withContextIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
}
