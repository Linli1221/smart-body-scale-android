package com.example.dianzicheng.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 历史数据状态持有者（历史抽屉 BodyHistoryDrawer 的数据源）。
 *
 * - [history]：全部测量记录，时间倒序；
 * - [members]：全部家庭成员（抽屉按成员过滤、记录卡成员归属展示用）。
 */
class HistoryViewModel(
    private val repository: ScaleRepository,
) : ViewModel() {

    // WhileSubscribed(5000)：冷启动不再立即全表扫描 + 全量映射 measurements（启动性能优化），
    // 由首个订阅者（首页/历史抽屉）触发；短暂重组间隙 5s 内保持订阅，避免反复启停查询。
    val history: StateFlow<List<BodyMeasurement>> = repository.getHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val members: StateFlow<List<FamilyMember>> = repository.getMembers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
