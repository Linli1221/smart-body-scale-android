package com.example.dianzicheng

import com.example.dianzicheng.ui.state.isMeasurementContinuation
import com.example.dianzicheng.ui.state.canMergeSplitMeasurement
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
    @Test
    fun splitMeasurementRequiresSameDeviceMemberWeightAndTimeWindow() {
        fun match(ms: Long = 60000L, weight: Double = 70.04, device: String? = "aa:bb", member: String? = "m1") =
            canMergeSplitMeasurement(ms, 70.0, weight, "AA:BB", device, "m1", member)
        assertTrue(match())
        assertFalse(match(ms = 60001L))
        assertFalse(match(ms = -1L))
        assertFalse(match(weight = 70.06))
        assertFalse(match(device = "cc:dd"))
        assertFalse(match(device = null))
        assertFalse(match(member = "m2"))
        assertFalse(match(member = null))
    }
}
