package com.vivi.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放界面手势判定的单测。这些规则决定「双击落在哪半边」「上下滑够不够换一条」
 * 「长按多久才算加速」，都是纯计算，不需要真机。
 */
class PlayerGesturesTest {

    @Test
    fun doubleTapRightHalfSeeksForward() {
        assertEquals(30, PlayerGestures.doubleTapSeekSeconds(x = 800f, width = 1080f, stepSec = 30))
    }

    @Test
    fun doubleTapLeftHalfSeeksBackward() {
        assertEquals(-30, PlayerGestures.doubleTapSeekSeconds(x = 200f, width = 1080f, stepSec = 30))
    }

    @Test
    fun doubleTapUsesTheConfiguredStep() {
        assertEquals(15, PlayerGestures.doubleTapSeekSeconds(x = 900f, width = 1080f, stepSec = 15))
    }

    @Test
    fun zeroWidthIsIgnored() {
        assertEquals(0, PlayerGestures.doubleTapSeekSeconds(x = 10f, width = 0f, stepSec = 30))
    }

    @Test
    fun swipeUpSwitchesToNext() {
        // 手指上滑时 dragAmount 为负
        assertEquals(true, PlayerGestures.swipeSwitchesToNext(totalDragPx = -600f))
    }

    @Test
    fun swipeDownSwitchesToPrevious() {
        assertEquals(false, PlayerGestures.swipeSwitchesToNext(totalDragPx = 600f))
    }

    @Test
    fun shortSwipeDoesNotSwitch() {
        assertNull(PlayerGestures.swipeSwitchesToNext(totalDragPx = -100f))
        assertNull(PlayerGestures.swipeSwitchesToNext(totalDragPx = 0f))
        assertNull(PlayerGestures.swipeSwitchesToNext(totalDragPx = 100f))
    }

    @Test
    fun swipeThresholdIsHonouredAtTheBoundary() {
        val threshold = PlayerGestures.SWITCH_MIN_DRAG_PX
        assertNull(PlayerGestures.swipeSwitchesToNext(-threshold + 1f, threshold))
        assertEquals(true, PlayerGestures.swipeSwitchesToNext(-threshold, threshold))
    }

    @Test
    fun longPressBoostNeedsBothDelayAndStillness() {
        val down = 1_000L
        assertTrue(PlayerGestures.shouldBoost(pressedMillis = down + 400, downUptimeMillis = down, movedPx = 2f))
        assertFalse(PlayerGestures.shouldBoost(pressedMillis = down + 399, downUptimeMillis = down, movedPx = 2f))
        assertFalse(PlayerGestures.shouldBoost(pressedMillis = down + 900, downUptimeMillis = down, movedPx = 41f))
    }

    @Test
    fun slopBoundaryIsStillALongPress() {
        assertTrue(
            PlayerGestures.shouldBoost(
                pressedMillis = 2_000L,
                downUptimeMillis = 1_000L,
                movedPx = PlayerGestures.MOVE_SLOP_PX,
            ),
        )
    }
}
