package com.vivi.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vivi.core.data.model.SortOrder
import com.vivi.core.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "viviv_settings")

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val sort: SortOrder = SortOrder.DATE_ADDED_DESC,
    val gridColumns: Int = 2,
    val minVideoDurationSec: Int = 20,
    val hideSystemFolders: Boolean = true,
    val showAllFiles: Boolean = false,
    /** 扫描 Android/data、Android/obb 等应用私有目录（默认开，按用户要求）。 */
    val scanAndroidData: Boolean = true,
    val resumePlayback: Boolean = true,
    val resumeSkipUnderSec: Int = 5,
    val hardwareDecode: Boolean = true,
    val autoRemux: Boolean = true,
    val allowVideoTranscode: Boolean = false,
    val seekStepSec: Int = 10,
    val doubleTapSeekSec: Int = 30,
    /** 横向拖动画面是否等于拖动进度。上下滑动固定用于切换上一个/下一个视频。 */
    val gestureSeek: Boolean = true,
    val keepScreenOn: Boolean = true,
    val backgroundPlayback: Boolean = false,
    val pipEnabled: Boolean = true,
    val playbackSpeed: Float = 1f,
    /** 长按屏幕时的临时倍速，松手恢复。 */
    val longPressSpeed: Float = 1.25f,
    val subtitleScale: Float = 1f,
    val subtitleDelayMicros: Long = 0,
    val subtitlePreferredLanguage: String = "zh",
    val subtitleOutline: Boolean = true,
    val subtitleShadow: Boolean = true,
    val subtitleBackground: Boolean = false,
    val audioPreferredLanguage: String = "",
    val thumbnailCacheMb: Int = 200,
    val lastScanMillis: Long = 0,
)

/**
 * 所有可调项集中在这里，UI 只读一个 Flow。
 *
 * 播放偏好（音轨/字幕选择）不放在全局设置，因为它们是按视频记忆的，存在 Room 的
 * playback_state 表里。
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.dynamicColor] ?: true,
            sort = prefs[Keys.sort]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
                ?: SortOrder.DATE_ADDED_DESC,
            gridColumns = prefs[Keys.gridColumns] ?: 2,
            minVideoDurationSec = prefs[Keys.minDurationSec] ?: 20,
            hideSystemFolders = prefs[Keys.hideSystemFolders] ?: true,
            showAllFiles = prefs[Keys.showAllFiles] ?: false,
            scanAndroidData = prefs[Keys.scanAndroidData] ?: true,
            resumePlayback = prefs[Keys.resumePlayback] ?: true,
            resumeSkipUnderSec = prefs[Keys.resumeSkipUnderSec] ?: 5,
            hardwareDecode = prefs[Keys.hardwareDecode] ?: true,
            autoRemux = prefs[Keys.autoRemux] ?: true,
            allowVideoTranscode = prefs[Keys.allowVideoTranscode] ?: false,
            seekStepSec = prefs[Keys.seekStepSec] ?: 10,
            doubleTapSeekSec = prefs[Keys.doubleTapSeekSec] ?: 30,
            gestureSeek = prefs[Keys.gestureSeek] ?: true,
            keepScreenOn = prefs[Keys.keepScreenOn] ?: true,
            backgroundPlayback = prefs[Keys.backgroundPlayback] ?: false,
            pipEnabled = prefs[Keys.pipEnabled] ?: true,
            playbackSpeed = prefs[Keys.playbackSpeed] ?: 1f,
            longPressSpeed = prefs[Keys.longPressSpeed] ?: 1.25f,
            subtitleScale = prefs[Keys.subtitleScale] ?: 1f,
            subtitleDelayMicros = prefs[Keys.subtitleDelayMicros] ?: 0L,
            subtitlePreferredLanguage = prefs[Keys.subtitleLanguage] ?: "zh",
            subtitleOutline = prefs[Keys.subtitleOutline] ?: true,
            subtitleShadow = prefs[Keys.subtitleShadow] ?: true,
            subtitleBackground = prefs[Keys.subtitleBackground] ?: false,
            audioPreferredLanguage = prefs[Keys.audioLanguage] ?: "",
            thumbnailCacheMb = prefs[Keys.thumbnailCacheMb] ?: 200,
            lastScanMillis = prefs[Keys.lastScanMillis] ?: 0L,
        )
    }

    suspend fun setTheme(mode: ThemeMode) = put(Keys.theme, mode.name)

    suspend fun setDynamicColor(enabled: Boolean) = put(Keys.dynamicColor, enabled)

    suspend fun setSort(order: SortOrder) = put(Keys.sort, order.name)

    suspend fun setGridColumns(columns: Int) = put(Keys.gridColumns, columns.coerceIn(1, 4))

    suspend fun setMinVideoDuration(seconds: Int) = put(Keys.minDurationSec, seconds.coerceIn(0, 600))

    suspend fun setShowAllFiles(enabled: Boolean) = put(Keys.showAllFiles, enabled)

    suspend fun setScanAndroidData(enabled: Boolean) = put(Keys.scanAndroidData, enabled)

    suspend fun setResumePlayback(enabled: Boolean) = put(Keys.resumePlayback, enabled)

    suspend fun setHardwareDecode(enabled: Boolean) = put(Keys.hardwareDecode, enabled)

    suspend fun setAutoRemux(enabled: Boolean) = put(Keys.autoRemux, enabled)

    suspend fun setAllowVideoTranscode(enabled: Boolean) = put(Keys.allowVideoTranscode, enabled)

    suspend fun setSeekStep(seconds: Int) = put(Keys.seekStepSec, seconds.coerceIn(5, 120))

    suspend fun setDoubleTapSeek(seconds: Int) = put(Keys.doubleTapSeekSec, seconds.coerceIn(5, 120))



    suspend fun setGestureSeek(enabled: Boolean) = put(Keys.gestureSeek, enabled)

    suspend fun setBackgroundPlayback(enabled: Boolean) = put(Keys.backgroundPlayback, enabled)

    suspend fun setPipEnabled(enabled: Boolean) = put(Keys.pipEnabled, enabled)

    suspend fun setPlaybackSpeed(speed: Float) = put(Keys.playbackSpeed, speed.coerceIn(0.25f, 4f))

    suspend fun setLongPressSpeed(speed: Float) = put(Keys.longPressSpeed, speed.coerceIn(1f, 4f))

    suspend fun setSubtitleScale(scale: Float) = put(Keys.subtitleScale, scale.coerceIn(0.5f, 3f))

    suspend fun setSubtitleDelay(micros: Long) = put(Keys.subtitleDelayMicros, micros)

    suspend fun setSubtitleLanguage(language: String) = put(Keys.subtitleLanguage, language)

    suspend fun setSubtitleOutline(enabled: Boolean) = put(Keys.subtitleOutline, enabled)

    suspend fun setSubtitleShadow(enabled: Boolean) = put(Keys.subtitleShadow, enabled)

    suspend fun setSubtitleBackground(enabled: Boolean) = put(Keys.subtitleBackground, enabled)

    suspend fun setAudioLanguage(language: String) = put(Keys.audioLanguage, language)

    suspend fun setThumbnailCacheMb(mb: Int) = put(Keys.thumbnailCacheMb, mb.coerceIn(20, 2000))

    suspend fun setLastScan(millis: Long) = put(Keys.lastScanMillis, millis)

    suspend fun setKeepScreenOn(enabled: Boolean) = put(Keys.keepScreenOn, enabled)


    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.settingsStore.edit { it[key] = value }
    }

    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val sort = stringPreferencesKey("sort_order")
        val gridColumns = intPreferencesKey("grid_columns")
        val minDurationSec = intPreferencesKey("min_duration_sec")
        val hideSystemFolders = booleanPreferencesKey("hide_system_folders")
        val showAllFiles = booleanPreferencesKey("show_all_files")
        val scanAndroidData = booleanPreferencesKey("scan_android_data")
        val resumePlayback = booleanPreferencesKey("resume_playback")
        val resumeSkipUnderSec = intPreferencesKey("resume_skip_under_sec")
        val hardwareDecode = booleanPreferencesKey("hardware_decode")
        val autoRemux = booleanPreferencesKey("auto_remux")
        val allowVideoTranscode = booleanPreferencesKey("allow_video_transcode")
        val seekStepSec = intPreferencesKey("seek_step_sec")
        val doubleTapSeekSec = intPreferencesKey("double_tap_seek_sec")
        val gestureSeek = booleanPreferencesKey("gesture_seek")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val backgroundPlayback = booleanPreferencesKey("background_playback")
        val pipEnabled = booleanPreferencesKey("pip_enabled")
        val playbackSpeed = floatPreferencesKey("playback_speed")
        val longPressSpeed = floatPreferencesKey("long_press_speed")
        val subtitleScale = floatPreferencesKey("subtitle_scale")
        val subtitleDelayMicros = longPreferencesKey("subtitle_delay_micros")
        val subtitleLanguage = stringPreferencesKey("subtitle_language")
        val subtitleOutline = booleanPreferencesKey("subtitle_outline")
        val subtitleShadow = booleanPreferencesKey("subtitle_shadow")
        val subtitleBackground = booleanPreferencesKey("subtitle_background")
        val audioLanguage = stringPreferencesKey("audio_language")
        val thumbnailCacheMb = intPreferencesKey("thumbnail_cache_mb")
        val lastScanMillis = longPreferencesKey("last_scan_millis")
    }
}
