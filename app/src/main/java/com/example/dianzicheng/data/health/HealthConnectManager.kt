package com.example.dianzicheng.data.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.domain.BodyMeasurement
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 单调递增版本保证同一客户端 ID 的新测量可覆盖旧值，即使系统时间回拨。 */
internal fun nextHealthRecordVersion(previous: Long, now: Long): Long = maxOf(previous + 1L, now)

/** 日志 Tag：统一使用类名常量（经 AppLogger 输出，运行日志查看器可见） */
private const val TAG = "HealthConnectManager"

class HealthConnectManager(private val context: Context) {

    private val healthConnectClient: HealthConnectClient?
        get() = if (isAvailable()) HealthConnectClient.getOrCreate(context) else null

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError = _syncError.asStateFlow()
    fun reportMissingPermissions() { _syncError.value = "系统健康写入权限不足，请重新授权" }
    fun reportSyncFailure() { _syncError.value = "同步失败，请检查系统健康状态后重试" }

    private val writeMutex = Mutex()
    private val recordVersion = AtomicLong(0L)
    private val writtenVersions = mutableMapOf<String, Long>() // 在 writeMutex 内访问
    private fun nextVersion(): Long = recordVersion.updateAndGet { old -> nextHealthRecordVersion(old, System.currentTimeMillis()) }
    fun captureSyncVersion(): Long = recordVersion.get()

    val permissions = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
    )

    fun isAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun hasAllPermissions(): Boolean {
        val client = healthConnectClient ?: return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(permissions)
    }

    suspend fun writeMeasurement(measurement: BodyMeasurement, skipIfWrittenAfter: Long? = null): Boolean = writeMutex.withLock {
        if (skipIfWrittenAfter != null &&
            (writtenVersions[measurement.id] ?: 0L) > skipIfWrittenAfter
        ) return@withLock true
        val client = healthConnectClient ?: run {
            AppLogger.w(TAG, "Health Connect Client is unavailable")
            _syncError.value = "系统健康不可用，请检查安装和设置"
            return@withLock false
        }

        try {
            val instant = Instant.ofEpochMilli(measurement.measuredAtEpochMs)
            val zoneOffset = ZoneOffset.systemDefault().rules.getOffset(instant)

            val version = nextVersion()
            val weightMetadata = if (measurement.id.isNotBlank()) {
                Metadata(clientRecordId = "weight_${measurement.id}", clientRecordVersion = version)
            } else {
                Metadata()
            }

            val weightRecord = WeightRecord(
                weight = Mass.kilograms(measurement.weightKg),
                time = instant,
                zoneOffset = zoneOffset,
                metadata = weightMetadata
            )

            val records = mutableListOf<androidx.health.connect.client.records.Record>(weightRecord)

            if (measurement.bodyFatPct > 0.0) {
                val fatMetadata = if (measurement.id.isNotBlank()) {
                    Metadata(clientRecordId = "fat_${measurement.id}", clientRecordVersion = version)
                } else {
                    Metadata()
                }
                val fatRecord = BodyFatRecord(
                    percentage = Percentage(measurement.bodyFatPct),
                    time = instant,
                    zoneOffset = zoneOffset,
                    metadata = fatMetadata
                )
                records.add(fatRecord)
            }

            client.insertRecords(records)
            if (measurement.id.isNotBlank()) writtenVersions[measurement.id] = version
            _syncError.value = null
            AppLogger.d(TAG, "Successfully written measurement to Health Connect")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write measurement to Health Connect", e)
            _syncError.value = "同步失败，请检查系统健康权限后重试"
            false
        }
    }

    /** 合并实时记录时仅按客户端 ID 清除重复项，不按时间删除其他测量。 */
    suspend fun deleteMeasurementByClientId(id: String) = writeMutex.withLock {
        if (id.isBlank()) return@withLock
        val client = healthConnectClient ?: return@withLock
        try {
            client.deleteRecords(recordType = WeightRecord::class, recordIdsList = emptyList(), clientRecordIdsList = listOf("weight_$id"))
            client.deleteRecords(recordType = BodyFatRecord::class, recordIdsList = emptyList(), clientRecordIdsList = listOf("fat_$id"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "清理 Health Connect 重复记录失败: ${e.message}")
        }
    }

    /** 逐条同步历史，单条失败不会导致其他记录被判定失败。 */
    suspend fun batchWriteMeasurements(measurements: List<BodyMeasurement>, snapshotVersion: Long): Pair<Int, Int> {
        if (!isAvailable() || !hasAllPermissions()) return 0 to measurements.size
        var success = 0
        for (measurement in measurements) {
            if (writeMeasurement(measurement, skipIfWrittenAfter = snapshotVersion)) success++
        }
        return success to (measurements.size - success)
    }

    /**
     * 打开 Health Connect 设置页（让用户管理已授权 App）
     */
    fun openHealthConnectSettings() {
        try {
            val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Cannot open ACTION_HEALTH_CONNECT_SETTINGS, trying fallback", e)
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                try {
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))
                    webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(webIntent)
                } catch (e3: Exception) {
                    AppLogger.e(TAG, "Cannot open Health Connect settings or play store", e3)
                }
            }
        }
    }
}

