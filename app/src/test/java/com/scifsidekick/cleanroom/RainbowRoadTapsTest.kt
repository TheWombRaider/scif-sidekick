package com.scifsidekick.cleanroom

import com.scifsidekick.cleanroom.ui.RainbowRoadTaps
import com.scifsidekick.cleanroom.ui.RainbowRoadTaps.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class RainbowRoadTapsTest {
    @Test fun `early taps are silent`() {
        (1..3).forEach { assertEquals(Result.Quiet, RainbowRoadTaps.result(it)) }
    }

    @Test fun `last taps count down to the unlock`() {
        assertEquals(Result.Countdown(3), RainbowRoadTaps.result(4))
        assertEquals(Result.Countdown(2), RainbowRoadTaps.result(5))
        assertEquals(Result.Countdown(1), RainbowRoadTaps.result(6))
    }

    @Test fun `the seventh tap unlocks`() {
        assertEquals(Result.Unlock, RainbowRoadTaps.result(RainbowRoadTaps.REQUIRED))
    }
}
