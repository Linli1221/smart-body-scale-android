package com.example.dianzicheng

import com.example.dianzicheng.domain.BiaModel
import com.example.dianzicheng.domain.BodyAlgorithm
import com.example.dianzicheng.domain.Sex
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class BodyAlgorithmTest {

    @Test
    fun testZeroWeightProducesZeroBmiAndMetrics() {
        val result = BodyAlgorithm.calculate(
            weightKg = 0.0,
            impedanceOhm = 500.0,
            sex = Sex.MALE,
            heightCm = 175.0,
            birthDateEpochMs = 946656000000L
        )

        assertEquals("0.0kg 体重时 BMI 必须为 0.0，不能被限制为 5.0", 0.0, result.bmi, 0.0001)
        assertEquals(0.0, result.bodyFatPct, 0.0001)
        assertEquals(0.0, result.muscleKg, 0.0001)
        assertEquals(0.0, result.waterPct, 0.0001)
        assertEquals(0.0, result.boneMassKg, 0.0001)
    }

    @Test
    fun testLightWeightBelow3KgSuppressesImpedanceMetrics() {
        val result = BodyAlgorithm.calculate(
            weightKg = 2.5,
            impedanceOhm = 500.0,
            sex = Sex.MALE,
            heightCm = 175.0,
            birthDateEpochMs = 946656000000L
        )

        // 体重低于 3.0kg（非人体正常体重）：不应计算体脂与肌肉
        assertEquals(0.0, result.bodyFatPct, 0.0001)
        assertEquals(0.0, result.muscleKg, 0.0001)
        assertEquals(0.0, result.waterPct, 0.0001)
        assertTrue(result.bmi > 0.0)
    }

    @Test
    fun testFutureBirthDateHandledSafely() {
        val futureCal = Calendar.getInstance().apply {
            add(Calendar.YEAR, 5)
        }
        val result = BodyAlgorithm.calculate(
            weightKg = 70.0,
            impedanceOhm = 500.0,
            sex = Sex.MALE,
            heightCm = 175.0,
            birthDateEpochMs = futureCal.timeInMillis
        )

        assertNotNull(result)
        assertTrue("体脂率应处于合理范围", result.bodyFatPct in 5.0..55.0)
        assertTrue("BMI 应处于正常范围", result.bmi in 15.0..30.0)
    }

    @Test
    fun testUnsetBirthDateHandledSafely() {
        val result = BodyAlgorithm.calculate(
            weightKg = 70.0,
            impedanceOhm = 500.0,
            sex = Sex.FEMALE,
            heightCm = 165.0,
            birthDateEpochMs = 0L
        )

        assertNotNull(result)
        assertTrue("体脂率应处于合理范围", result.bodyFatPct in 5.0..55.0)
    }

    @Test
    fun testSafeHeightClampingPreventsDivisionByZero() {
        val result = BodyAlgorithm.calculate(
            weightKg = 70.0,
            impedanceOhm = 500.0,
            sex = Sex.MALE,
            heightCm = 0.0, // 异常身高
            birthDateEpochMs = 946656000000L
        )

        assertFalse("BMI 不能为 NaN", result.bmi.isNaN())
        assertFalse("BMI 不能为无穷大", result.bmi.isInfinite())
        assertTrue("BMI 应该限制在合理最大值", result.bmi <= 100.0)
    }

    @Test
    fun testNormalAdultMeasurementCalculation() {
        val resultMale = BodyAlgorithm.calculate(
            weightKg = 72.0,
            impedanceOhm = 520.0,
            sex = Sex.MALE,
            heightCm = 178.0,
            birthDateEpochMs = 788918400000L // 1995年
        )

        assertTrue(resultMale.bmi in 20.0..25.0)
        assertTrue(resultMale.bodyFatPct in 10.0..25.0)
        assertTrue(resultMale.muscleKg > 40.0)
        assertTrue(resultMale.waterPct in 50.0..65.0)
        assertTrue(resultMale.boneMassKg in 2.5..4.0)

        val resultFemale = BodyAlgorithm.calculate(
            weightKg = 52.0,
            impedanceOhm = 550.0,
            sex = Sex.FEMALE,
            heightCm = 162.0,
            birthDateEpochMs = 788918400000L
        )

        assertTrue(resultFemale.bmi in 18.0..22.0)
        assertTrue(resultFemale.bodyFatPct in 10.0..30.0)
        assertTrue(resultFemale.waterPct in 45.0..65.0)
    }

    @Test
    fun testSmallWeightBmiCalculation() {
        // 测试小体重（如 5.10kg 测试场景）BMI 正确计算，不被错误 clamp 到 5.0 或 0.0
        val result = BodyAlgorithm.calculate(
            weightKg = 5.10,
            impedanceOhm = 0.0,
            sex = Sex.MALE,
            heightCm = 170.0,
            birthDateEpochMs = 946656000000L
        )

        // 5.10 / (1.7 * 1.7) = 5.10 / 2.89 ≈ 1.76
        assertTrue("BMI 必须大于 0", result.bmi > 0.0)
        assertEquals("BMI 应准确计算为 5.10 / 1.7^2 ≈ 1.76", 5.10 / (1.7 * 1.7), result.bmi, 0.01)
    }

    // ── 电阻抗估算模型（BiaModel）───────────────────────────────────────────

    /** 文档示例：男性 62.2kg / 1.73m / 21 岁 / Z=650Ω，Wu 2015 模型预期体脂率 ≈ 21.0% */
    @Test
    fun testWu2015ModelMatchesPublishedExample() {
        val result = BodyAlgorithm.calculate(
            weightKg = 62.2,
            impedanceOhm = 650.0,
            sex = Sex.MALE,
            heightCm = 173.0,
            birthDateEpochMs = epochMsForAge(21),
            biaModel = BiaModel.WU_2015
        )

        // FFM = 13.055 + 0.204*62.2 + 0.394*(173²/650) - 0.136*21 + 8.125
        //     ≈ 13.055 + 12.689 + 18.179 - 2.856 + 8.125 ≈ 49.19 kg
        val expectedFfm = 13.055 + 0.204 * 62.2 + 0.394 * (173.0 * 173.0 / 650.0) - 0.136 * 21 + 8.125
        val expectedFat = (1.0 - expectedFfm / 62.2) * 100.0
        assertEquals("Wu 2015 体脂率应按 FFM 反推", expectedFat, result.bodyFatPct, 0.05)
        assertTrue("文档示例体脂率约 21%", result.bodyFatPct in 20.0..22.0)
    }

    /** 文档示例：同一受试者，Jebb 2000 男性公式预期体脂率 ≈ 18.0% */
    @Test
    fun testJebb2000ModelMatchesPublishedExample() {
        val result = BodyAlgorithm.calculate(
            weightKg = 62.2,
            impedanceOhm = 650.0,
            sex = Sex.MALE,
            heightCm = 173.0,
            birthDateEpochMs = epochMsForAge(21),
            biaModel = BiaModel.JEBB_2000
        )

        // %FM = -156.1 - 89.1*ln(1.73) + 45.6*ln(62.2) + 0.120*21 + 0.0494*650
        val expected = -156.1 - 89.1 * Math.log(1.73) + 45.6 * Math.log(62.2) +
            0.120 * 21 + 0.0494 * 650.0
        assertEquals("Jebb 2000 男性体脂率应按公式计算", expected, result.bodyFatPct, 0.05)
        assertTrue("文档示例体脂率约 18%", result.bodyFatPct in 17.0..19.0)
    }

    /** Jebb 2000 女性需额外 +19.6×ln(身高m)，结果必须高于同参数男性 */
    @Test
    fun testJebb2000FemaleHigherThanMale() {
        val common = { sex: Sex ->
            BodyAlgorithm.calculate(
                weightKg = 62.2,
                impedanceOhm = 650.0,
                sex = sex,
                heightCm = 173.0,
                birthDateEpochMs = epochMsForAge(21),
                biaModel = BiaModel.JEBB_2000
            )
        }
        val male = common(Sex.MALE)
        val female = common(Sex.FEMALE)

        val expectedDiff = 19.6 * Math.log(1.73)
        assertEquals(
            "女性体脂率应比男性高 19.6×ln(身高m)",
            expectedDiff,
            female.bodyFatPct - male.bodyFatPct,
            0.05
        )
    }

    /** 三种模型的体脂率必须都落在各自模型的合法区间内 */
    @Test
    fun testAllModelsProducePhysiologicallyPlausibleFat() {
        val cases = listOf(
            Triple(45.0, 160.0, 22),
            Triple(62.2, 173.0, 21),
            Triple(85.0, 180.0, 45),
            Triple(52.0, 158.0, 60)
        )
        for ((weight, height, age) in cases) {
            for (model in BiaModel.entries) {
                val result = BodyAlgorithm.calculate(
                    weightKg = weight,
                    impedanceOhm = 650.0,
                    sex = Sex.MALE,
                    heightCm = height,
                    birthDateEpochMs = epochMsForAge(age),
                    biaModel = model
                )
                assertTrue(
                    "${model.id} 在 ${weight}kg/${height}cm/${age}岁 下体脂率应合法",
                    result.bodyFatPct in model.fatMin..model.fatMax
                )
                assertFalse("${model.id} 体脂率不能为 NaN", result.bodyFatPct.isNaN())
            }
        }
    }

    /** 未传模型时必须回落到内置默认模型，保证旧调用方行为不变 */
    @Test
    fun testDefaultModelFallbackKeepsLegacyBehavior() {
        val legacy = BodyAlgorithm.calculate(
            weightKg = 72.0,
            impedanceOhm = 520.0,
            sex = Sex.MALE,
            heightCm = 178.0,
            birthDateEpochMs = epochMsForAge(30)
        )
        val explicit = BodyAlgorithm.calculate(
            weightKg = 72.0,
            impedanceOhm = 520.0,
            sex = Sex.MALE,
            heightCm = 178.0,
            birthDateEpochMs = epochMsForAge(30),
            biaModel = BiaModel.DEFAULT
        )
        assertEquals("不传模型应等价于显式传入 DEFAULT", legacy.bodyFatPct, explicit.bodyFatPct, 0.0001)
        // 与历史内置公式一致：0.18*BMI + 0.012*30 + 0.018*520 - 3.2
        val expected = 0.18 * (72.0 / (1.78 * 1.78)) + 0.012 * 30 + 0.018 * 520.0 - 3.2
        assertEquals("默认模型应沿用内置经验公式", expected, explicit.bodyFatPct, 0.05)
    }

    /** 无阻抗时任何模型都不应输出体脂率 */
    @Test
    fun testNoImpedanceYieldsZeroFatForEveryModel() {
        for (model in BiaModel.entries) {
            val result = BodyAlgorithm.calculate(
                weightKg = 70.0,
                impedanceOhm = 0.0,
                sex = Sex.MALE,
                heightCm = 175.0,
                birthDateEpochMs = epochMsForAge(30),
                biaModel = model
            )
            assertEquals("${model.id} 无阻抗时体脂率必须为 0.0", 0.0, result.bodyFatPct, 0.0001)
            assertEquals("${model.id} 无阻抗时肌肉量必须为 0.0", 0.0, result.muscleKg, 0.0001)
        }
    }

    /**
     * 构造一个「年龄恰好为 [age] 岁」的出生日期时间戳。
     *
     * 先回退 [age] 年、再额外回退 30 天，保证生日一定已经过去，
     * 从而不受闰年（2 月 29 日）与跨年边界影响，年龄恒等于 [age]。
     */
    private fun epochMsForAge(age: Int): Long {
        val cal = Calendar.getInstance().apply {
            add(Calendar.YEAR, -age)
            add(Calendar.DAY_OF_YEAR, -30)
        }
        return cal.timeInMillis
    }
}
