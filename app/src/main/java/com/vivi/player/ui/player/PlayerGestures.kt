package com.vivi.player.ui.player

/**
 * 播放界面手势里与「框架无关」的判定部分。
 *
 * 抽出来是为了能在 JVM 单测里覆盖：Compose 的手势识别本身不归我们测，
 * 但「落在屏幕哪一半 → 前进还是后退」「滑动方向和距离 → 上一条还是下一条」
 * 「长按阈值 → 是否算长按」这些规则是我们的业务。
 */
object PlayerGestures {

    /** 长按判定延迟，短于此时间的按下不算加速。 */
    const val LONG_PRESS_DELAY_MS = 400L

    /** 超过这个位移就认为用户在拖动，而不是长按。 */
    const val MOVE_SLOP_PX = 40f

    /** 上下滑切换视频的最小位移（px）。 */
    const val SWITCH_MIN_DRAG_PX = 248f

    /** 双击快进/快退：左半屏后退，右半屏前进。 */
    fun doubleTapSeekSeconds(x: Float, width: Float, stepSec: Int): Int {
        if (width <= 0f) return 0
        return if (x < width / 2f) -stepSec else stepSec
    }

    /**
     * 上下滑的意图。
     * @return true 下一条，false 上一条，null 表示位移不够、不切换。
     */
    fun swipeSwitchesToNext(totalDragPx: Float, thresholdPx: Float = SWITCH_MIN_DRAG_PX): Boolean? {
        if (kotlin.math.abs(totalDragPx) < thresholdPx) return null
        // 手指上移 = 内容向上走 = 下一条
        return totalDragPx < 0f
    }

    /** 长按是否应当触发临时倍速。 */
    fun shouldBoost(pressedMillis: Long, downUptimeMillis: Long, movedPx: Float): Boolean =
        movedPx <= MOVE_SLOP_PX && pressedMillis - downUptimeMillis >= LONG_PRESS_DELAY_MS
}
