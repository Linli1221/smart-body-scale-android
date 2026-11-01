package com.example.dianzicheng.ui.state

import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.domain.BiaModel
import com.example.dianzicheng.domain.BodyMeasurement
import com.example.dianzicheng.domain.FamilyMember
import com.example.dianzicheng.domain.RememberedScale
import com.example.dianzicheng.domain.ScaleModel

/**
 * 测量主页的 UI 状态快照（不可变）。
 *
 * 由 [ScaleViewModel] 维护、经 `StateFlow<ScaleUiState>` 推送；
 * UI 只订阅此单一对象，不直接拼装多路 Flow。
 *
 * 字段语义与旧实现保持一致（全功能复刻的行为契约）：
 * - [liveWeightKg] 仅为显示精度（0.01 kg 取整），入库使用 BLE 原始值；
 * - [impedanceOhm] 为 0.0 表示本次未测出阻抗（未赤脚称重）；
 * - [currentMeasurement] 在用户离秤后保持可见，直到下一次称量会话开始。
 */
data class ScaleUiState(
    val connection: BleScaleClient.ConnectionState = BleScaleClient.ConnectionState.IDLE,
    val liveWeightKg: Double = 0.0,
    val impedanceOhm: Double = 0.0,
    val isStable: Boolean = false,
    val currentMeasurement: BodyMeasurement? = null,
    val selectedMember: FamilyMember? = null,
    val availableMembers: List<FamilyMember> = emptyList(),
    val showNewMemberAlert: Boolean = false,
    val rememberedScales: List<RememberedScale> = emptyList(),
    val activeScaleMac: String? = null,
    val pairedDeviceMac: String? = null,
    val pairedDeviceName: String? = null,
    val isDeviceRemembered: Boolean = false,
    val discoveredScales: List<BleScaleClient.DiscoveredScaleDevice> = emptyList(),
    val isBluetoothEnabled: Boolean = true,
    val isLocationEnabled: Boolean = true,
    val selectedScaleModel: ScaleModel = ScaleModel.AUTO,
    val selectedBiaModel: BiaModel = BiaModel.DEFAULT
)
