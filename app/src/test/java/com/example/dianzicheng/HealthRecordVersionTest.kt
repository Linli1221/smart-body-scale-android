package com.example.dianzicheng

import com.example.dianzicheng.data.health.nextHealthRecordVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthRecordVersionTest {
    @Test
    fun versionIncreasesForRepeatedWritesAndClockRollback() {
        assertEquals(1000L, nextHealthRecordVersion(0L, 1000L))
        assertEquals(1001L, nextHealthRecordVersion(1000L, 1000L))
        assertEquals(1002L, nextHealthRecordVersion(1001L, 900L))
        assertEquals(2000L, nextHealthRecordVersion(1002L, 2000L))
    }
}
