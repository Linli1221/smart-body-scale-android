package com.example.dianzicheng.data.local

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

import androidx.datastore.preferences.core.stringPreferencesKey

import com.example.dianzicheng.domain.BiaModel
import com.example.dianzicheng.domain.RememberedScale
import com.example.dianzicheng.domain.ScaleMemory
import com.example.dianzicheng.domain.ScaleMemoryLogic
import com.example.dianzicheng.domain.buildScaleMemory
import com.example.dianzicheng.domain.normalizeMacAddress
import com.example.dianzicheng.domain.sameMacAddress

// 通过 Kotlin 委托属性为 Context 扩展 DataStore 实例，文件名 "settings"
private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 应用偏好设置管理器，基于 Jetpack DataStore（Preferences）实现持久化存储。
 *
 * 存储以下配置项：
 * - 蓝牙体脂秤配对状态与「已记住的多台体脂秤」列表（含当前使用中的一台）
 * - 用户手动选择的体脂秤型号偏好
 * - 用户选择的电阻抗（BIA）体成分估算模型
 * - 用户自定义的身体指标展示顺序
 * - Health Connect 同步开关
 * - WebDAV 云备份的服务器地址、账号、密码
 * - 上次备份时间
 *
 * 所有读取操作返回 [Flow]，可响应式订阅；写入操作为挂起函数，需在协程中调用。
 */
class PreferenceManager(private val context: Context) {

    // ── DataStore Key 定义 ──────────────────────────────────────────────────

    /** 是否已完成设备配对流程 */
    private val PAIRING_COMPLETE = booleanPreferencesKey("pairing_complete")

    /**
     * 旧版（v1.4.1 及以前）单设备字段：已配对的蓝牙设备 MAC 地址（格式如 "AA:BB:CC:DD:EE:FF"）。
     *
     * 仅用于升级迁移读取，一旦写入新版多设备列表即会被清除，避免残留数据反复「复活」已移除的设备。
     */
    private val PAIRED_MAC = stringPreferencesKey("paired_mac")

    /** 旧版（v1.4.1 及以前）单设备字段：已配对的蓝牙设备名称（如 "小米体脂秤 2"）。 */
    private val PAIRED_DEVICE_NAME = stringPreferencesKey("paired_device_name")

    /**
     * 新版多设备列表（行文本编码，见 [com.example.dianzicheng.domain.RememberedScaleCodec]）。
     * 键名带版本后缀，便于未来格式演进时无损升级。
     */
    private val REMEMBERED_SCALES = stringPreferencesKey("remembered_scales_v1")

    /** 新版：当前使用中的体脂秤 MAC 地址（自动连接、界面展示均以它为准） */
    private val ACTIVE_SCALE_MAC = stringPreferencesKey("active_scale_mac")

    /** Health Connect 自动同步开关状态 */
    private val HEALTH_CONNECT_ENABLED = booleanPreferencesKey("health_connect_enabled")

    /** WebDAV 服务器地址（如 https://dav.jianguoyun.com/dav/） */
    private val WEBDAV_URL = stringPreferencesKey("webdav_url")

    /** WebDAV 登录账号 */
    private val WEBDAV_USERNAME = stringPreferencesKey("webdav_username")

    /** WebDAV 登录密码或应用授权码 */
    private val WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")

    /** 上次成功备份到 WebDAV 的时间（Unix 毫秒时间戳） */
    private val LAST_BACKUP_TIME =
        androidx.datastore.preferences.core.longPreferencesKey("last_backup_time")

    /** 用户手动选择的体脂秤型号 ID（如 "BOOHEE_YOLANDA", "XIAOMI_SCALE_2", 默认为 "AUTO"） */
    private val SELECTED_SCALE_MODEL = stringPreferencesKey("selected_scale_model")

    /**
     * 用户选择的电阻抗估算模型 ID（如 "DEFAULT", "WU_2015", "JEBB_2000"）。
     * 未设置时回落到 [com.example.dianzicheng.domain.BiaModel.DEFAULT]。
     */
    private val SELECTED_BIA_MODEL = stringPreferencesKey("selected_bia_model")

    // ── 读取操作（Flow） ─────────────────────────────────────────────────────

    /** 是否已完成设备配对，默认 false */
    val isPairingComplete: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[PAIRING_COMPLETE] ?: false }

    /** 用户手动选择的体脂秤型号 ID，默认为 "AUTO" */
    val selectedScaleModel: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[SELECTED_SCALE_MODEL] ?: "AUTO" }

    /**
     * 用户选择的电阻抗估算模型 ID 字符串，默认为 "DEFAULT"。
     *
     * 需要枚举值时请使用 [selectedBiaModel]。
     */
    val selectedBiaModelId: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[SELECTED_BIA_MODEL] ?: BiaModel.DEFAULT.id }

    /** 用户选择的电阻抗估算模型（来自 DataStore，默认内置通用模型） */
    val selectedBiaModel: Flow<BiaModel> = selectedBiaModelId
        .map { BiaModel.fromId(it) }
        .distinctUntilChanged()

    /**
     * 全部已记住的体脂秤（最近使用优先）。
     *
     * 新版列表为空时会自动回填旧版单设备记录，保证升级用户的原有体脂秤不丢失。
     */
    /**
     * 解码后的设备记忆（内部共享流，运行期性能优化）：
     * scaleMemory() 的解码每次偏好变化只做一遍，rememberedScales / activeScaleMac / activeScale
     * 等公开 Flow 全部从它派生，避免每个订阅者（MainActivity / ScaleViewModel / ProfileViewModel）
     * 各自重复解码整份设备记忆。公开 API 签名与语义保持不变。
     */
    private val scaleMemoryFlow: Flow<ScaleMemory> = context.dataStore.data
        .map { preferences -> preferences.scaleMemory() }
        .distinctUntilChanged()

    val rememberedScales: Flow<List<RememberedScale>> = scaleMemoryFlow
        .map { it.scales }
        .distinctUntilChanged()

    /** 当前使用中的体脂秤 MAC 地址；未记住任何设备时为 null */
    val activeScaleMac: Flow<String?> = scaleMemoryFlow
        .map { it.activeMac }
        .distinctUntilChanged()

    /** 当前使用中的体脂秤完整信息；未记住任何设备时为 null */
    val activeScale: Flow<RememberedScale?> = scaleMemoryFlow
        .map { it.active }
        .distinctUntilChanged()

    /**
     * 当前使用中的体脂秤 MAC 地址，未记住任何设备时为 null。
     *
     * 保留旧名称以兼容既有调用方，语义已从「唯一记住的设备」扩展为「多台中的当前使用设备」。
     */
    val pairedMac: Flow<String?> = activeScaleMac

    /** Health Connect 自动同步是否已开启，默认 false */
    val healthConnectEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[HEALTH_CONNECT_ENABLED] ?: false }

    /** WebDAV 服务器地址，未配置时为空字符串 */
    val webdavUrl: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[WEBDAV_URL] ?: "" }

    /** WebDAV 账号，未配置时为空字符串 */
    val webdavUsername: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[WEBDAV_USERNAME] ?: "" }

    /** WebDAV 密码，未配置时为空字符串（存储明文，建议仅限内部使用） */
    val webdavPassword: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[WEBDAV_PASSWORD] ?: "" }

    /** 上次成功备份时间（Unix 毫秒），从未备份时为 0L */
    val lastBackupTime: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[LAST_BACKUP_TIME] ?: 0L }

    // ── 写入操作（挂起函数） ─────────────────────────────────────────────────

    /** 更新配对完成状态 */
    suspend fun setPairingComplete(complete: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PAIRING_COMPLETE] = complete
        }
    }

    /**
     * 记住（新增或更新）一台体脂秤并标记配对已完成。
     *
     * 已存在同 MAC 的设备时按字段合并更新，不会产生重复条目；
     * [makeActive] 为 true（默认）时同时把该设备设为当前使用中的体脂秤。
     *
     * @param scale      要记住的设备（型号/协议信息用于界面展示，可留空）
     * @param makeActive 是否同时切换为当前使用设备；false 表示仅登记不切换
     */
    suspend fun rememberScale(scale: RememberedScale, makeActive: Boolean = true) {
        val mac = normalizeMacAddress(scale.mac)
        if (mac.isEmpty()) return
        context.dataStore.edit { preferences ->
            val memory = preferences.scaleMemory()
            val now = System.currentTimeMillis()
            val item = RememberedScale(
                mac = mac,
                name = scale.name,
                modelId = scale.modelId,
                protocolName = scale.protocolName,
                lastUsedAtMs = if (scale.lastUsedAtMs > 0L) scale.lastUsedAtMs else now
            )
            val updated = ScaleMemoryLogic.upsert(memory.scales, item)
            val active = if (makeActive) mac else ScaleMemoryLogic.resolveActive(updated, memory.activeMac)
            persistScaleMemory(preferences, updated, active)
            preferences[PAIRING_COMPLETE] = true
        }
    }

    /**
     * 幂等登记一个已发现的设备 MAC：已存在时**不覆盖**其名称与型号。
     *
     * 用于底层 BLE 客户端回调（仅携带 MAC）时兜底补登记，
     * 避免它把用户绑定时保存的友好名称冲掉。
     */
    suspend fun ensureRememberedMac(mac: String) {
        val normalized = normalizeMacAddress(mac)
        if (normalized.isEmpty()) return
        context.dataStore.edit { preferences ->
            val memory = preferences.scaleMemory()
            val updated = ScaleMemoryLogic.ensure(
                memory.scales,
                RememberedScale(mac = normalized, name = "体脂秤", lastUsedAtMs = System.currentTimeMillis())
            )
            if (updated != memory.scales) {
                persistScaleMemory(preferences, updated, memory.activeMac ?: normalized)
                preferences[PAIRING_COMPLETE] = true
            }
        }
    }

    /** 切换当前使用中的体脂秤（设备不在已记住列表中时不产生任何写入）。 */
    suspend fun setActiveScale(mac: String) {
        val normalized = normalizeMacAddress(mac)
        if (normalized.isEmpty()) return
        context.dataStore.edit { preferences ->
            val memory = preferences.scaleMemory()
            // 设备不存在时必须直接返回：否则 resolveActive 会回退到列表第一台，
            // 把「切换失败」悄悄变成「切到了另一台设备」
            if (memory.scales.none { sameMacAddress(it.mac, normalized) }) return@edit
            val updated = ScaleMemoryLogic.markUsed(memory.scales, normalized, System.currentTimeMillis())
            persistScaleMemory(preferences, updated, ScaleMemoryLogic.resolveActive(updated, normalized))
        }
    }

    /**
     * 移除一台已记住的体脂秤。
     *
     * - 若移除的是当前使用设备，则自动把列表中的第一台设为当前使用设备；
     * - 若移除后已不剩任何设备，则把配对状态重置为 false（回到配对引导页），与旧版解除绑定行为一致。
     */
    suspend fun removeRememberedScale(mac: String) {
        val target = normalizeMacAddress(mac)
        if (target.isEmpty()) return
        context.dataStore.edit { preferences ->
            val memory = preferences.scaleMemory()
            val updated = ScaleMemoryLogic.remove(memory.scales, target)
            val keepActive = if (sameMacAddress(memory.activeMac, target)) null else memory.activeMac
            persistScaleMemory(preferences, updated, ScaleMemoryLogic.resolveActive(updated, keepActive))
            if (updated.isEmpty()) {
                preferences[PAIRING_COMPLETE] = false
            }
        }
    }

    /**
     * 把旧版单设备字段（PAIRED_MAC / PAIRED_DEVICE_NAME）一次性迁移为多设备列表。
     *
     * 应用启动时调用即可；即使从不调用，[rememberedScales] 等读取入口也会即时回填，
     * 此处只是让迁移结果尽快落盘，避免旧字段长期残留。
     */
    suspend fun migrateLegacyDeviceIfNeeded() {
        context.dataStore.edit { preferences ->
            if (!preferences[REMEMBERED_SCALES].isNullOrBlank()) return@edit
            val legacyMac = preferences[PAIRED_MAC]
            if (legacyMac.isNullOrBlank()) {
                preferences.remove(PAIRED_MAC)
                preferences.remove(PAIRED_DEVICE_NAME)
                return@edit
            }
            val migrated = ScaleMemoryLogic.migrateLegacy(legacyMac, preferences[PAIRED_DEVICE_NAME])
            if (migrated.isEmpty()) return@edit
            persistScaleMemory(preferences, migrated, migrated.first().mac)
        }
    }

    /** 从当前 DataStore 快照解析出完整的多设备记忆（含旧版单设备迁移回填）。 */
    private fun Preferences.scaleMemory(): ScaleMemory = buildScaleMemory(
        rawList = this[REMEMBERED_SCALES],
        rawActiveMac = this[ACTIVE_SCALE_MAC],
        legacyMac = this[PAIRED_MAC],
        legacyName = this[PAIRED_DEVICE_NAME]
    )

    /**
     * 把设备列表与当前使用设备写回 DataStore，并清理旧版单设备字段。
     *
     * 列表为空时移除键而不是写入空串，保持存储整洁（解码时二者等价）。
     */
    private fun persistScaleMemory(
        preferences: MutablePreferences,
        scales: List<RememberedScale>,
        activeMac: String?
    ) {
        val encoded = com.example.dianzicheng.domain.RememberedScaleCodec.encode(scales)
        if (encoded.isEmpty()) {
            preferences.remove(REMEMBERED_SCALES)
        } else {
            preferences[REMEMBERED_SCALES] = encoded
        }
        val active = normalizeMacAddress(activeMac)
        if (active.isEmpty()) {
            preferences.remove(ACTIVE_SCALE_MAC)
        } else {
            preferences[ACTIVE_SCALE_MAC] = active
        }
        // 迁移/更新完成后清除旧字段，避免旧数据再次参与回填
        preferences.remove(PAIRED_MAC)
        preferences.remove(PAIRED_DEVICE_NAME)
    }

    /** 保存用户手动选择的体脂秤型号 ID */
    suspend fun saveSelectedScaleModel(modelId: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_SCALE_MODEL] = modelId
        }
    }

    /** 保存用户选择的电阻抗估算模型 */
    suspend fun saveSelectedBiaModel(model: BiaModel) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_BIA_MODEL] = model.id
        }
    }

    /** 更新 Health Connect 同步开关状态 */
    suspend fun setHealthConnectEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HEALTH_CONNECT_ENABLED] = enabled
        }
    }

    /** 保存 WebDAV 服务器配置（地址、账号、密码） */
    suspend fun saveWebdavConfig(url: String, user: String, pass: String) {
        context.dataStore.edit { preferences ->
            preferences[WEBDAV_URL] = url
            preferences[WEBDAV_USERNAME] = user
            preferences[WEBDAV_PASSWORD] = pass
        }
    }

    /** 更新上次成功备份的时间戳 */
    suspend fun saveLastBackupTime(timeMs: Long) {
        context.dataStore.edit { preferences ->
            preferences[LAST_BACKUP_TIME] = timeMs
        }
    }
}
