package com.vivi.player.di

import android.app.Application
import android.net.Uri
import com.vivi.core.data.db.ViViDatabaseProvider
import com.vivi.core.data.repository.PlaybackRepository
import com.vivi.core.data.repository.PlaylistRepository
import com.vivi.core.data.repository.SettingsRepository
import com.vivi.core.data.repository.VaultRepository
import com.vivi.core.data.repository.VideoRepository
import com.vivi.core.data.scan.FileSystemVideoSource
import com.vivi.core.data.scan.MediaPermissions
import com.vivi.core.data.scan.MediaStoreVideoSource
import com.vivi.core.data.scan.SafTreeVideoSource
import com.vivi.core.ffmpeg.FFmpegRunner
import com.vivi.core.ffmpeg.MediaInputResolver
import com.vivi.core.ffmpeg.MediaProbeService
import com.vivi.core.ffmpeg.PlaybackPreparer
import com.vivi.core.ffmpeg.SubtitleService
import com.vivi.core.ffmpeg.ThumbnailService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * 手写依赖图。
 *
 * 只有一个进程级共享播放器实例：通知栏/锁屏（MediaSessionService）和播放器界面必须操作同一个
 * ExoPlayer，否则会出现「通知栏暂停了，界面还在跑」。它由容器持有，Activity 与 Service 都不许自行 release。
 */
class AppContainer(private val app: Application) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** UI 层需要 Context 时统一从这里取，避免到处传。 */
    val context: android.content.Context get() = app

    private val database by lazy { ViViDatabaseProvider.get(app) }

    val settings by lazy { SettingsRepository(app) }

    val probeService by lazy { MediaProbeService(app) }

    val ffmpegRunner by lazy { FFmpegRunner(app) }

    val inputResolver by lazy { MediaInputResolver(app) }

    val thumbnailService by lazy { ThumbnailService(app, ffmpegRunner, inputResolver) }

    val subtitleService by lazy { SubtitleService(app, ffmpegRunner, inputResolver) }

    val playbackPreparer by lazy { PlaybackPreparer(app, ffmpegRunner, inputResolver) }

    val videoRepository: VideoRepository by lazy {
        VideoRepository(
            context = app,
            mediaStore = MediaStoreVideoSource(app),
            fileSystem = FileSystemVideoSource(app),
            safTrees = SafTreeVideoSource(app),
            playbackDao = database.playbackDao(),
            metadataDao = database.metadataDao(),
            folderSettingsDao = database.folderSettingsDao(),
        ).apply {
            // 「所有文件」授权到位时，扫描范围才扩展到 MediaStore 索引不到的目录
            fileSystemScanEnabled = MediaPermissions.hasAllFilesAccess()
        }
    }

    val playbackRepository: PlaybackRepository by lazy {
        PlaybackRepository(
            playbackDao = database.playbackDao(),
            favoriteDao = database.favoriteDao(),
            metadataDao = database.metadataDao(),
            videoRepository = videoRepository,
        )
    }

    val vaultRepository: VaultRepository by lazy { VaultRepository(app, database.vaultDao()) }

    val playlists: PlaylistRepository by lazy {
        PlaylistRepository(
            playlists = database.playlistDao(),
            playlistItems = database.playlistItemDao(),
            resolveKeys = videoRepository::resolveKeys,
        )
    }

    /** 外部「用其他应用打开」带进来的地址，由 MainActivity 消费。 */
    val pendingExternalUri = kotlinx.coroutines.flow.MutableStateFlow<Uri?>(null)

    /** 画中画状态与开关缓存到容器，Service / Activity 都要读，避免每次 runBlocking。 */
    val pipActive = MutableStateFlow(false)

    @Volatile
    var pipEnabled: Boolean = true
        private set

    /** 生命周期回调里要读设置，但不能阻塞等 Flow，所以留一份快照。 */
    @Volatile
    var lastSettings: com.vivi.core.data.repository.AppSettings =
        com.vivi.core.data.repository.AppSettings()
        private set

        fun stopMediaService() {
        runCatching {
            app.stopService(android.content.Intent(app, com.vivi.player.service.ViViMediaService::class.java))
        }
    }

    /**
     * 退到后台时是否继续播放，由「后台播放」开关决定。
     * 画中画不算后台：PiP 窗口里 Activity 仍是 STARTED，不会被这里暂停。
     */
    val lifecycleObserver = object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
            if (!lastSettings.backgroundPlayback) PlaybackHolder.current()?.pause()
        }
    }

    init {
        scope.launch {
            settings.settings.collect { value ->
                lastSettings = value
                pipEnabled = value.pipEnabled
                val previousAndroidData = videoRepository.includeAndroidData
                videoRepository.includeAndroidData = value.scanAndroidData
                videoRepository.fileSystemScanEnabled = MediaPermissions.hasAllFilesAccess()
                // 开关切换后必须重扫，否则新纳入的目录要等到下次手动刷新才出现
                if (previousAndroidData != value.scanAndroidData) {
                    videoRepository.refreshExtras()
                }
                // ExoPlayer 只允许在主线程访问，设置里改字幕/语言偏好时必须切回去
                withContext(Dispatchers.Main.immediate) {
                    PlaybackHolder.current()?.applySettingsSnapshot(value)
                }
            }
        }
    }

    /**
     * Media3 的 MediaSessionService 不会自己起来。起播前显式拉一次，
     * 播放器、MediaSession、通知栏、锁屏控件、前台服务保护才都在同一个进程里生效。
     *
     * 只能用普通 `startService()`：`startForegroundService()` 会向系统承诺「10 秒内调用
     * startForeground()」，而 Media3 的 `onStartCommand` 只处理媒体键/自定义动作，普通启动
     * intent 它什么都不做，前台化要等播放状态回调里由 `MediaNotificationManager` 自己做。
     * 真机实测：这里用 startForegroundService 必然抛
     * `ForegroundServiceDidNotStartInTimeException` 并整个进程被杀。
     */
    fun ensureMediaService() {
        runCatching {
            val intent = android.content.Intent(app, com.vivi.player.service.ViViMediaService::class.java)
            app.startService(intent)
        }
    }

    fun refreshScanScope() {
        videoRepository.fileSystemScanEnabled = MediaPermissions.hasAllFilesAccess()
        scope.launch { videoRepository.refreshExtras() }
    }

    fun shutdown() {
        PlaybackHolder.release()
        scope.cancel()
    }
}
