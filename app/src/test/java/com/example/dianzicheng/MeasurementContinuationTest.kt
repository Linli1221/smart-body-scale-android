package com.example.dianzicheng

import com.example.dianzicheng.ui.state.isMeasurementContinuation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementContinuationTest {
    private fun continues(previous: Boolean, interrupted: Boolean, elapsed: Long, difference: Double) =
        isMeasurementContinuation(previous, interrupted, elapsed, difference, 2500L, 2.0)

    @Test
    fun continuesOnlyExistingSessionForBriefSimilarRelock() {
        assertTrue(continues(true, true, 2500L, 2.0))
        assertTrue(continues(true, false, 10000L, 10.0))
        assertFalse(continues(false, true, 100L, 0.0))
        assertFalse(continues(true, true, 2501L, 0.0))
        assertFalse(continues(true, true, 100L, 2.01))
        assertFalse(continues(true, true, -1L, 0.0))
    }
}
