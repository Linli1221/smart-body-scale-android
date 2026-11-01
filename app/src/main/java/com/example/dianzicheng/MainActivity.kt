package com.example.dianzicheng

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import com.example.dianzicheng.data.backup.WebDavManager
import com.example.dianzicheng.data.ble.BleScaleClient
import com.example.dianzicheng.data.health.HealthConnectManager
import com.example.dianzicheng.data.local.AppDatabase
import com.example.dianzicheng.data.local.AppLogger
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ProfileRepository
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.normalizeMacAddress
import com.example.dianzicheng.ui.ScaleApp
import com.example.dianzicheng.ui.state.HistoryViewModel
import com.example.dianzicheng.ui.state.ProfileViewModel
import com.example.dianzicheng.ui.state.ScaleViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 应用入口：装配数据层依赖、处理运行时权限与 BLE 前后台生命周期，
 * 内容区交给单页面 + 弹层架构的 [ScaleApp]。
 */
class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    private lateinit var bleClient: BleScaleClient
    private lateinit var scaleRepository: ScaleRepository
    private lateinit var profileRepository: ProfileRepository
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var webDavManager: WebDavManager

    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(
                    android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                    android.bluetooth.BluetoothAdapter.ERROR
                )
                if (state == android.bluetooth.BluetoothAdapter.STATE_ON) {
                    // 蓝牙打开后，若已记住任意一台设备且当前处于 IDLE，自动恢复扫描
                    if (bleClient.hasRememberedDevice &&
                        bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE
                    ) {
                        bleClient.startScan()
                    }
                }
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            // 有权限被拒绝：提示用户前往系统设置手动开启，否则蓝牙功能无法使用
            Toast.makeText(
                this,
                "蓝牙权限被拒绝，请前往「设置 → 应用 → 权限」手动开启蓝牙权限，否则无法搜索体脂秤",
                Toast.LENGTH_LONG
            ).show()
        } else {
            resumeRememberedScaleScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "scale-db" // 库名保持不变，避免老用户数据丢失
        ).addMigrations(AppDatabase.MIGRATION_1_2).build()

        bleClient = BleScaleClient(applicationContext)
        // 传入 database 而非仅 dao：ScaleRepository 的历史重算需要跨表事务
        scaleRepository = ScaleRepository(database.scaleDao(), database)
        profileRepository = ProfileRepository(database.scaleDao())
        preferenceManager = PreferenceManager(applicationContext)
        healthConnectManager = HealthConnectManager(applicationContext)
        webDavManager = WebDavManager(database.scaleDao())

        // 首帧后统一执行的一次性初始化（启动性能优化）：这些都不必抢在首帧前——
        // 默认成员在首次称重前建好即可，旧配置迁移在读取侧本就有回填兜底。
        runAfterFirstFrame {
            lifecycleScope.launch(Dispatchers.IO) {
                // 确保系统中存在默认用户（"自己"），保证首次称重时 BMI 与身体成分正常计算
                profileRepository.ensureDefaultMemberExists()
                // 旧版单设备配置一次性迁移为多设备列表：保证老用户升级后原有体脂秤不丢失
                preferenceManager.migrateLegacyDeviceIfNeeded()
            }
            // 仅当已有记住的体脂秤（自动连接需要蓝牙权限）时才在启动时请求权限；
            // 首次使用改由配对弹层自带的权限流触发，冷启动不再被系统权限框遮挡首帧。
            lifecycleScope.launch {
                if (preferenceManager.rememberedScales.first().isNotEmpty()) {
                    checkPermissions()
                }
            }
        }

        bleClient.onMacDiscovered = { mac ->
            lifecycleScope.launch {
                // 幂等补登记：仅携带 MAC 的回调不会覆盖绑定时保存的设备名称/型号
                preferenceManager.ensureRememberedMac(mac)
            }
        }

        // 把「已记住的全部体脂秤」同步给 BLE 客户端，作为自动连接白名单（踩上任意一台都能自动连接）
        lifecycleScope.launch {
            preferenceManager.rememberedScales.collect { scales ->
                bleClient.rememberedMacs = scales.map { normalizeMacAddress(it.mac) }.toSet()
                if (scales.isNotEmpty()) resumeRememberedScaleScan()
            }
        }

        lifecycleScope.launch {
            preferenceManager.pairedMac.collect { mac ->
                bleClient.lastPairedMac = mac // null clears memory, preventing stale reconnect
                if (!mac.isNullOrEmpty()) resumeRememberedScaleScan()
            }
        }

        val filter = android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        // 仅接收受保护的系统广播，显式声明非导出（targetSdk 36 推荐做法）
        ContextCompat.registerReceiver(this, bluetoothReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        enableEdgeToEdge()
        setContent {
            val isPairingComplete by preferenceManager.isPairingComplete.collectAsState(initial = null)

            // viewModelFactory DSL：消除三份手写 Factory + UNCHECKED_CAST 样板（技术债 L1）
            val scaleViewModel: ScaleViewModel = viewModel(factory = viewModelFactory {
                initializer {
                    ScaleViewModel(
                        bleClient,
                        scaleRepository,
                        preferenceManager,
                        healthConnectManager
                    )
                }
            })
            val historyViewModel: HistoryViewModel = viewModel(factory = viewModelFactory {
                initializer { HistoryViewModel(scaleRepository) }
            })
            val profileViewModel: ProfileViewModel = viewModel(factory = viewModelFactory {
                initializer {
                    ProfileViewModel(
                        profileRepository,
                        preferenceManager,
                        webDavManager,
                        healthConnectManager,
                        scaleRepository
                    )
                }
            })

            ScaleApp(
                scaleViewModel = scaleViewModel,
                historyViewModel = historyViewModel,
                profileViewModel = profileViewModel,
                isPairingComplete = isPairingComplete,
                onPairingComplete = {
                    lifecycleScope.launch {
                        preferenceManager.setPairingComplete(true)
                    }
                }
            )
        }
    }

    /**
     * 首帧上屏之后执行（启动性能优化）：
     * 先用 Choreographer 接到第一帧，再向主线程消息队列尾部投递，
     * 保证 [block] 运行时首帧已经绘制完成，不与首帧争抢主线程 / binder / IO。
     */
    private fun runAfterFirstFrame(block: () -> Unit) {
        android.view.Choreographer.getInstance().postFrameCallback {
            window.decorView.post(block)
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            // 未声明 neverForLocation 时需要定位权限以捕获厂商广播数据包
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    /** 已记住设备、权限就绪且前台空闲时恢复自动扫描。 */
    private fun resumeRememberedScaleScan() {
        if (!::bleClient.isInitialized) return
        runAfterFirstFrame {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                bleClient.hasRememberedDevice && !bleClient.isPairingMode &&
                bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE &&
                runCatching { bleClient.isBluetoothEnabled() }.getOrDefault(false)
            ) {
                bleClient.startScan()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bleClient.isForeground = true
        resumeRememberedScaleScan()
    }

    override fun onStop() {
        super.onStop()
        // 进入后台：暂停扫描与重试；保留已经建立的 GATT 连接。
        bleClient.isForeground = false
        bleClient.stopScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            // 注销失败不影响退出流程，但不再静默吞掉，留一条运行日志便于排查
            AppLogger.w("MainActivity", "unregisterReceiver 失败: ${e.message}")
        }
        bleClient.disconnectAndReset()
    }
}
