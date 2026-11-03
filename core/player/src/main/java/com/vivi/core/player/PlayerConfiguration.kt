package com.vivi.core.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 缓冲与解码策略。
 *
 * 本地文件不需要像网络流那样保守：缓冲太小会让高码率 4K 在 UFS 随机读上抖动，
 * 太大又让 seek 响应变慢，所以默认取「时间优先」的中档。
 */
data class PlayerConfiguration(
    val minBufferMicros: Int = 15_000_000,
    val maxBufferMicros: Int = 60_000_000,
    val playbackBufferMicros: Int = 8_000_000,
    val additionalBufferMicros: Int = 12_000_000,
    val doubleTapSeekSec: Int = 30,
    val hardwareDecode: Boolean = true,
    val allowExtensions: Boolean = false,
    var repeatMode: RepeatMode = RepeatMode.OFF,
    var startPaused: Boolean = false,
)

/** 睡眠定时器：到点暂停并把剩余时间暴露给 UI。 */
class SleepTimer {

    private val _remainingMicros = MutableStateFlow(0L)
    val remainingMicros: StateFlow<Long> = _remainingMicros.asStateFlow()

    private var job: kotlinx.coroutines.Job? = null

    fun start(minutes: Int, scope: kotlinx.coroutines.CoroutineScope, onExpired: () -> Unit) {
        cancel()
        if (minutes <= 0) return
        _remainingMicros.value = minutes * 60_000_000L
        job = scope.launch {
            while (_remainingMicros.value > 0) {
                delay(1_000L)
                _remainingMicros.value = (_remainingMicros.value - 1_000_000L).coerceAtLeast(0L)
            }
            onExpired()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _remainingMicros.value = 0L
    }

    fun addMinutes(minutes: Int) {
        _remainingMicros.value += minutes * 60_000_000L
    }
}
