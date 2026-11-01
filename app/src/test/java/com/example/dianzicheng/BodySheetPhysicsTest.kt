package com.example.dianzicheng

import com.example.dianzicheng.ui.body.rubberBandOffset
import com.example.dianzicheng.ui.body.settleAnchorIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodySheetPhysicsTest {
    private val anchors = listOf(0f, 300f, 800f)

    @Test
    fun rubberBandIsContinuousResistedAndBoundedAtBothEdges() {
        assertEquals(0f, rubberBandOffset(0f, 0f, 800f, 80f), 0.001f)
        assertEquals(400f, rubberBandOffset(400f, 0f, 800f, 80f), 0.001f)
        val top = rubberBandOffset(-80f, 0f, 800f, 80f)
        val bottom = rubberBandOffset(880f, 0f, 800f, 80f)
        assertEquals(-40f, top, 0.001f)
        assertEquals(840f, bottom, 0.001f)
        assertTrue(rubberBandOffset(-10000f, 0f, 800f, 80f) > -80f)
        assertTrue(rubberBandOffset(10800f, 0f, 800f, 80f) < 880f)
    }

    @Test
    fun slowReleaseUsesActualUnequalAnchorDistances() {
        assertEquals(0, settleAnchorIndex(140f, 0f, anchors, 400f))
        assertEquals(1, settleAnchorIndex(200f, 0f, anchors, 400f))
        assertEquals(1, settleAnchorIndex(500f, 0f, anchors, 400f))
        assertEquals(2, settleAnchorIndex(600f, 0f, anchors, 400f))
    }

    @Test
    fun releaseVelocityAffectsDirectionAndCanCrossMultipleAnchors() {
        assertEquals(1, settleAnchorIndex(100f, 450f, anchors, 400f))
        assertEquals(0, settleAnchorIndex(200f, -450f, anchors, 400f))
        assertEquals(2, settleAnchorIndex(100f, 4000f, anchors, 400f))
        assertEquals(0, settleAnchorIndex(700f, -4000f, anchors, 400f))
    }

    @Test
    fun outwardReleaseBeyondBoundsReturnsToBoundary() {
        assertEquals(0, settleAnchorIndex(-40f, -1000f, anchors, 400f))
        assertEquals(2, settleAnchorIndex(840f, 1000f, anchors, 400f))
    }
}
