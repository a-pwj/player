package com.vivi.player.ui.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.vivi.core.data.model.MediaKey
import com.vivi.core.player.AspectMode
import com.vivi.core.player.PlayerUiState
import com.vivi.core.player.ViViPlayer
import com.vivi.player.di.AppContainer
import com.vivi.player.di.PlaybackHolder
import com.vivi.player.service.PlaybackCoordinator
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放界面的薄壳。
 *
 * A 方案之后这里不再持有 ExoPlayer，也不再跑探测/混流流水线 —— 那些都在
 * [PlaybackCoordinator]（由 MediaSessionService 创建）里。ViewModel 清掉时也只落进度，
 * 绝不释放播放器，否则退到后台就断播。
 */
class PlayerViewModel(
    private val coordinator: PlaybackCoordinator,
    private val container: AppContainer,
) : ViewModel() {

    val player: ViViPlayer = coordinator.player
    val state: StateFlow<PlayerUiState> = coordinator.state
    val prep = coordinator.prep
    val sheet = coordinator.sheet
    val subtitleChoices = coordinator.subtitleChoices
    val current = coordinator.current

    val longPressSpeed: Float get() = coordinator.longPressSpeed

    /** 媒体库条目：只给 key，地址由协调器解析。 */
    fun openKey(key: MediaKey) {
        container.ensureMediaService()
        coordinator.open(PlaybackCoordinator.OpenRequest(key = key))
    }

    /** 从播放列表起播：连播顺序按列表，而不是按同目录文件名。 */
    fun openKeyInPlaylist(key: MediaKey, queueKeys: List<MediaKey>) {
        container.ensureMediaService()
        coordinator.open(PlaybackCoordinator.OpenRequest(key = key, queueKeys = queueKeys))
    }

    /** 外部「用其他应用打开」：只有地址，没有媒体库条目。 */
    fun openUri(uri: Uri) {
        container.ensureMediaService()
        coordinator.open(
            PlaybackCoordinator.OpenRequest(
                key = MediaKey.ofUri(uri),
                uri = uri,
                title = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "外部视频" },
                folderPath = uri.path?.substringBeforeLast('/'),
            )
        )
    }

    fun retryWithTranscode() = coordinator.retryWithTranscode()

    fun togglePlayPause() = coordinator.togglePlayPause()

    fun seekTo(micros: Long) = coordinator.seekTo(micros)

    fun seekBy(seconds: Int) = coordinator.seekBy(seconds)

    fun next() = coordinator.next()

    fun previous() = coordinator.previous()

    fun previousItem() = coordinator.previousItem()

    fun setSpeed(speed: Float) = coordinator.setSpeed(speed)

    fun setVolume(volume: Float) = coordinator.setVolume(volume)

    fun setAspectMode(mode: AspectMode) = coordinator.setAspectMode(mode)

    fun seekToChapter(index: Int) = coordinator.seekToChapter(index)

    fun pause() = coordinator.pause()

    val gestureSeekEnabled: Boolean get() = coordinator.gestureSeekEnabled

    val doubleTapSeekSec: Int get() = coordinator.doubleTapSeekSec

    fun cycleRepeat() = coordinator.cycleRepeat()

    fun chooseSubtitle(choice: PlaybackCoordinator.SubtitleChoice) = coordinator.chooseSubtitle(choice)

    fun disableSubtitle() = coordinator.disableSubtitle()

    fun selectAudio(groupId: Int, trackIndex: Int) = coordinator.selectAudio(groupId, trackIndex)

    fun importExternalSubtitle(uri: Uri) = coordinator.importExternalSubtitle(uri)

    fun beginLongPressBoost(): Float = coordinator.beginLongPressBoost()

    fun endLongPressBoost() = coordinator.endLongPressBoost()

    fun persistProgress() = coordinator.persistProgress()

    fun isPlayingNow(): Boolean = coordinator.isPlayingNow()

    override fun onCleared() {
        // 只落进度；播放器归 Service，界面销毁不能打断后台播放
        coordinator.persistProgress()
        super.onCleared()
    }
}
