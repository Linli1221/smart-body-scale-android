package com.example.dianzicheng.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.BodyMetric
import com.example.dianzicheng.domain.FamilyMember
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 历史数据状态持有者（历史弹层 / 历史趋势的数据源）。
 *
 * - [history]：全部测量记录，时间倒序；
 * - [members]：全部家庭成员（趋势图按成员过滤、记录卡成员归属展示用）；
 * - [bodyMetricOrder]：指标展示顺序（可在界面里调整并持久化）。
 */
class HistoryViewModel(
    private val repository: ScaleRepository,
    private val preferenceManager: PreferenceManager? = null,
    private val healthConnectManager: HealthConnectManager? = null
) : ViewModel() {

    val history: StateFlow<List<BodyMeasurement>> = repository.getHistory()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val members: StateFlow<List<FamilyMember>> = repository.getMembers()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val bodyMetricOrder: StateFlow<List<BodyMetric>> =
        preferenceManager?.bodyMetricOrder
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, BodyMetric.defaultOrder)
            ?: kotlinx.coroutines.flow.MutableStateFlow(BodyMetric.defaultOrder)

    /** 持久化指标展示顺序（空列表等价于恢复默认顺序）。 */
    fun saveBodyMetricOrder(order: List<BodyMetric>) {
        val pm = preferenceManager ?: return
        viewModelScope.launch { pm.saveBodyMetricOrder(order) }
    }

    /** 删除单条记录：先删本地（history Flow 自动刷新），已授权 Health Connect 时同步删除远端副本。 */
    fun deleteMeasurement(measurement: BodyMeasurement) {
        viewModelScope.launch {
            repository.deleteMeasurement(measurement)
            val hc = healthConnectManager ?: return@launch
            val enabled = preferenceManager?.healthConnectEnabled?.firstOrNull() ?: false
            if (enabled && hc.hasAllPermissions()) {
                runCatching { hc.deleteMeasurement(measurement) }
            }
        }
    }
}
