package com.example.dianzicheng.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BodyMeasurement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 测量历史页面的 ViewModel。
 *
 * 职责：
 * 1. 提供测量历史记录列表（按时间倒序），历史页 UI 直接订阅
 * 2. 提供家庭成员列表，用于历史记录中的成员筛选功能
 * 3. 提供删除单条测量记录的操作入口（本地与 Health Connect 同步删除）
 */
class HistoryViewModel(
    private val repository: ScaleRepository,
    private val preferenceManager: PreferenceManager? = null,
    private val healthConnectManager: HealthConnectManager? = null
) : ViewModel() {

    /**
     * 所有测量历史记录列表（Flow，按时间倒序，数据库变更时自动推送）。
     * 使用 [SharingStarted.Eagerly] 确保页面切换回来时数据已就绪，无白屏等待。
     */
    val history: StateFlow<List<BodyMeasurement>> = repository.getHistory()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 所有家庭成员列表（Flow），用于历史记录页的成员过滤筛选控件。
     * 使用 [SharingStarted.Eagerly] 保证切换到历史页时筛选器立即可用。
     */
    val members: StateFlow<List<com.example.dianzicheng.domain.FamilyMember>> = repository.getMembers()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 用户自定义的身体指标展示顺序（体重 / BMI / 体脂率 / 肌肉量 / 蛋白质 / 骨量）。
     *
     * 历史页的趋势卡片按此顺序渲染；未自定义时由
     * [com.example.dianzicheng.domain.BodyMetric.defaultOrder] 提供默认顺序。
     */
    val bodyMetricOrder: StateFlow<List<com.example.dianzicheng.domain.BodyMetric>> =
        preferenceManager?.bodyMetricOrder
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, com.example.dianzicheng.domain.BodyMetric.defaultOrder)
            ?: MutableStateFlow(com.example.dianzicheng.domain.BodyMetric.defaultOrder)

    /**
     * 保存用户调整后的指标顺序。
     *
     * 立即写入 DataStore，[bodyMetricOrder] 随后自动推送新顺序刷新界面。
     * 未注入 [PreferenceManager] 时为无操作（如 Preview 场景）。
     */
    fun saveBodyMetricOrder(order: List<com.example.dianzicheng.domain.BodyMetric>) {
        val manager = preferenceManager ?: return
        viewModelScope.launch {
            manager.saveBodyMetricOrder(order)
        }
    }

    /**
     * 删除单条测量记录。
     * 先删除本地数据库记录（[history] Flow 自动推送更新，UI 无需手动刷新），
     * 若已开启 Health Connect 同步，再删除远端对应记录，保持两端数据一致。
     *
     * @param measurement 要删除的测量记录
     */
    fun deleteMeasurement(measurement: BodyMeasurement) {
        viewModelScope.launch {
            repository.deleteMeasurement(measurement)

            val prefs = preferenceManager ?: return@launch
            val hc = healthConnectManager ?: return@launch
            try {
                val enabled = prefs.healthConnectEnabled.first()
                if (enabled && hc.hasAllPermissions()) {
                    hc.deleteMeasurement(measurement)
                }
            } catch (e: Exception) {
                android.util.Log.e("HistoryViewModel", "Health Connect delete sync failed", e)
            }
        }
    }
}
