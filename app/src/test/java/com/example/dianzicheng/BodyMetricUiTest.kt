package com.example.dianzicheng

import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.Sex
import com.example.dianzicheng.ui.body.HistoryMode
import com.example.dianzicheng.ui.body.MetricKind
import com.example.dianzicheng.ui.body.MetricTone
import com.example.dianzicheng.ui.body.buildBodyReport
import com.example.dianzicheng.ui.body.buildHistorySnapshots
import com.example.dianzicheng.ui.body.estimateVisceralFatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 身体报告纯逻辑回归：阈值、无 BIA 降级、差值守卫、历史聚合与时间轴顺序。
 *
 * 这些函数不依赖 Android / Compose，可在 JVM 单测中直接跑，避免 UI 层改阈值后
 * 卡片颜色、图表与历史快照口径漂移。
 */
class BodyMetricUiTest {

    private val male = FamilyMember(
        id = "m1",
        name = "测试男",
        sex = Sex.MALE,
        heightCm = 175.0,
        birthDateEpochMs = Instant.parse("1995-01-01T00:00:00Z").toEpochMilli(),
        referenceWeightKg = 70.0,
    )

    private fun measurement(
        id: String = "r1",
        at: Long = Instant.parse("2026-03-10T10:00:00Z").toEpochMilli(),
        weight: Double = 70.0,
        impedance: Double = 500.0,
        bmi: Double = 22.9,
        fat: Double = 18.0,
        muscle: Double = 52.0,
        water: Double = 58.0,
        protein: Double = 18.0,
        bone: Double = 2.9,
        memberId: String? = "m1",
    ) = BodyMeasurement(
        id = id,
        measuredAtEpochMs = at,
        weightKg = weight,
        impedanceOhm = impedance,
        bmi = bmi,
        bodyFatPct = fat,
        muscleKg = muscle,
        waterPct = water,
        proteinPct = protein,
        boneMassKg = bone,
        memberId = memberId,
        memberNameSnapshot = if (memberId == null) null else "测试男",
    )

    @Test
    fun bmiZeroFallsBackToDashInsteadOfUnderweight() {
        val report = buildBodyReport(
            measurement = measurement(impedance = 0.0, bmi = 0.0, fat = 0.0, muscle = 0.0, water = 0.0, protein = 0.0, bone = 0.0),
            member = male,
            previous = null,
            isFirstRecord = true,
        )
        val bmi = report.readings.getValue(MetricKind.BMI)
        assertEquals("--", bmi.valueText)
        assertNull(bmi.numericValue)
        assertNull(bmi.status)
        assertFalse(report.hasBia)
    }

    @Test
    fun bmiThresholdsFollowDeliverySpec() {
        fun status(bmi: Double) = buildBodyReport(
            measurement = measurement(bmi = bmi),
            member = male,
            previous = null,
            isFirstRecord = false,
        ).readings.getValue(MetricKind.BMI).status!!
        assertEquals("偏瘦", status(18.4).label)
        assertEquals("标准", status(18.5).label)
        assertEquals("标准", status(23.9).label)
        assertEquals("偏胖", status(24.0).label)
        assertEquals("肥胖", status(28.0).label)
    }

    @Test
    fun maleBodyFatThresholdsMatchDeliverySpec() {
        fun status(fat: Double) = buildBodyReport(
            measurement = measurement(fat = fat),
            member = male,
            previous = null,
            isFirstRecord = false,
        ).readings.getValue(MetricKind.BODY_FAT).status!!
        assertEquals("偏低", status(9.9).label)
        assertEquals("标准", status(10.0).label)
        assertEquals("标准", status(20.0).label)
        assertEquals("偏高", status(21.0).label)
        assertEquals("肥胖", status(26.0).label)
    }

    @Test
    fun noBiaBlanksSixCompositionMetricsButKeepsWeightAndBmi() {
        val report = buildBodyReport(
            measurement = measurement(impedance = 0.0, fat = 0.0, muscle = 0.0, water = 0.0, protein = 0.0, bone = 0.0),
            member = male,
            previous = null,
            isFirstRecord = false,
        )
        assertFalse(report.hasBia)
        assertNotNull(report.readings.getValue(MetricKind.WEIGHT).numericValue)
        assertNotNull(report.readings.getValue(MetricKind.BMI).numericValue)
        listOf(
            MetricKind.BODY_FAT,
            MetricKind.MUSCLE,
            MetricKind.WATER,
            MetricKind.VISCERAL_FAT,
            MetricKind.PROTEIN,
            MetricKind.BONE,
        ).forEach { kind ->
            assertEquals("--", report.readings.getValue(kind).valueText)
            assertNull(report.readings.getValue(kind).numericValue)
        }
    }

    @Test
    fun firstRecordHasNoDeltaAndKeepsFirstBadge() {
        val report = buildBodyReport(measurement(), male, previous = null, isFirstRecord = true)
        assertTrue(report.isFirstRecord)
        assertNull(report.weightDeltaKg)
    }

    @Test
    fun zeroPreviousWeightDoesNotProducePhantomDelta() {
        val previous = measurement(id = "old", weight = 0.0)
        val report = buildBodyReport(measurement(), male, previous, isFirstRecord = false)
        assertNull(report.weightDeltaKg)
    }

    @Test
    fun visceralEstimateIsInOneToTwentyAndNoBiaIsOne() {
        val noBia = measurement(impedance = 0.0, fat = 0.0)
        val noBiaLevel = estimateVisceralFatLevel(noBia, male)
        assertTrue(noBiaLevel == 1)
        val high = measurement(weight = 110.0, bmi = 36.0, fat = 40.0)
        val level = estimateVisceralFatLevel(high, male)
        assertTrue(level in 1..20)
    }

    @Test
    fun singleHistoryIsNewestFirst() {
        val old = measurement(id = "old", at = Instant.parse("2026-03-01T10:00:00Z").toEpochMilli())
        val new = measurement(id = "new", at = Instant.parse("2026-03-10T10:00:00Z").toEpochMilli())
        val snapshots = buildHistorySnapshots(listOf(old, new), memberId = "m1", mode = HistoryMode.SINGLE)
        assertEquals("new", snapshots.first().measurement.id)
        assertEquals(1, snapshots.first().sampleCount)
    }

    @Test
    fun weeklySnapshotsAverageAndCountSamples() {
        val day = 86_400_000L
        val base = Instant.parse("2026-03-10T10:00:00Z").toEpochMilli()
        val history = listOf(
            measurement(id = "a", at = base, weight = 70.0, fat = 18.0),
            measurement(id = "b", at = base + day, weight = 72.0, fat = 20.0),
            measurement(id = "c", at = base + 2 * day, weight = 74.0, fat = 22.0),
        )
        val snapshot = buildHistorySnapshots(history, "m1", HistoryMode.WEEK).single()
        assertEquals(3, snapshot.sampleCount)
        assertEquals("周均", snapshot.sublabel)
        assertEquals(72.0, snapshot.measurement.weightKg, 0.001)
        assertEquals(20.0, snapshot.measurement.bodyFatPct, 0.001)
    }

    @Test
    fun monthlySnapshotUsesStableIdAndLabel() {
        val at = Instant.parse("2026-03-15T10:00:00Z").toEpochMilli()
        val snapshot = buildHistorySnapshots(listOf(measurement(at = at)), "m1", HistoryMode.MONTH).single()
        assertEquals("month-2026-03", snapshot.measurement.id)
        assertEquals("3月", snapshot.label)
        assertEquals("月均", snapshot.sublabel)
    }

    @Test
    fun bmiZeroStillHasNoFakeStatusInReadingModel() {
        val reading = buildBodyReport(
            measurement = measurement(bmi = 0.0),
            member = null,
            previous = null,
            isFirstRecord = false,
        ).readings.getValue(MetricKind.BMI)
        assertEquals("--", reading.valueText)
        assertEquals(null, reading.status)
    }

    @Test
    fun metricToneUsesGoodForStandardBmi() {
        val status = buildBodyReport(
            measurement = measurement(bmi = 21.0),
            member = male,
            previous = null,
            isFirstRecord = false,
        ).readings.getValue(MetricKind.BMI).status!!
        assertEquals(MetricTone.GOOD, status.tone)
    }
}
