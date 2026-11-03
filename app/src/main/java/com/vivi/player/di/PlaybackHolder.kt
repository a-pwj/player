package com.vivi.player.di

import android.content.Context
import com.vivi.player.service.PlaybackCoordinator

/**
 * 进程内唯一播放协调器的持有者。
 *
 * 播放器归 `ViViMediaService` 所有（A 方案），但 UI 与 Service 在同一个进程里，
 * UI 直接观察同一个协调器即可，不需要跨进程的 MediaController —— 少一层异步与状态分裂。
 * Service 是唯一创建者；UI 只读/调用，绝不持有 ExoPlayer。
 */
object PlaybackHolder {

    @Volatile
    private var coordinator: PlaybackCoordinator? = null

    fun ensure(context: Context, container: AppContainer): PlaybackCoordinator =
        coordinator ?: synchronized(this) {
            coordinator ?: PlaybackCoordinator(context.applicationContext, container)
                .also { coordinator = it }
        }

    fun current(): PlaybackCoordinator? = coordinator

    /** Service 销毁时释放；UI 退出播放页不调用，否则后台播放会被打断。 */
    fun release() {
        val existing = coordinator ?: return
        coordinator = null
        runCatching { existing.release() }
    }
}
