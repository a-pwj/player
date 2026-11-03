package com.vivi.player.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.vivi.player.MainActivity
import com.vivi.player.ViViApplication
import com.vivi.player.di.PlaybackHolder

/**
 * 播放器的宿主。
 *
 * A 方案：ExoPlayer 由本 Service 创建并持有，`MediaSession` 直接挂在它上面，
 * 于是通知栏、锁屏控件、媒体键、前台服务保护都作用在同一个实例上；
 * UI 通过 [PlaybackHolder] 观察同一个协调器，不再自己 new 播放器。
 *
 * 关键是下面那句 `addSession()`。Media3 1.5.1 只在「控制端通过 binder 连进来」时才把会话登记
 * 进 `MediaSessionService.sessions`，而进程内 `MediaSession.Builder.build()` 不会触发它。
 * 没登记的后果是链式失效：`MediaNotificationManager.updateNotification()` 第一句
 * `isSessionAdded()` 为假就直接 return —— 没有通知、没有前台服务，进程在后台十几秒后被 MIUI 杀掉。
 */
class ViViMediaService : MediaSessionService() {

    private var session: MediaSession? = null
    private var coordinator: PlaybackCoordinator? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as ViViApplication).container
        val holder = PlaybackHolder.ensure(this, container)
        coordinator = holder

        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)

        val built = MediaSession.Builder(this, holder.player.player)
            .setSessionActivity(openApp)
            .build()
        session = built
        addSession(built)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        val keepRunning = player != null && player.playWhenReady && player.mediaItemCount > 0
        if (!keepRunning) {
            coordinator?.persistProgress()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        coordinator?.persistProgress()
        session?.let { runCatching { removeSession(it) } }
        session?.release()
        session = null
        coordinator = null
        PlaybackHolder.release()
        super.onDestroy()
    }
}
