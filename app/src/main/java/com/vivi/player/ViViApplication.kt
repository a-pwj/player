package com.vivi.player

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import com.vivi.core.ffmpeg.FFmpegEnvironment
import com.vivi.player.di.AppContainer

class ViViApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        FFmpegEnvironment.init(this)
        createChannels()
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(container.lifecycleObserver)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PLAYBACK,
                getString(R.string.notification_channel_playback),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCAN,
                getString(R.string.notification_channel_scan),
                NotificationManager.IMPORTANCE_MIN,
            )
        )
    }

    override fun onTerminate() {
        container.shutdown()
        super.onTerminate()
    }

    companion object {
        const val CHANNEL_PLAYBACK = "vivi_playback"
        const val CHANNEL_SCAN = "vivi_scan"

        lateinit var instance: ViViApplication
            private set

        fun from(context: Context): ViViApplication =
            context.applicationContext as ViViApplication
    }
}

internal val Context.appContainer: AppContainer get() = ViViApplication.from(this).container
