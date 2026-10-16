package com.example.dianzicheng

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import com.example.dianzicheng.data.ble.BleScaleClient
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.dianzicheng.data.local.AppDatabase
import com.example.dianzicheng.data.local.PreferenceManager
import com.example.dianzicheng.data.repository.ProfileRepository
import com.example.dianzicheng.data.repository.ScaleRepository
import com.example.dianzicheng.domain.normalizeMacAddress
import com.example.dianzicheng.ui.HistoryViewModel
import com.example.dianzicheng.ui.MainScreen
import com.example.dianzicheng.ui.ProfileViewModel
import com.example.dianzicheng.ui.ScaleViewModel
import com.example.dianzicheng.ui.theme.电子秤Theme

import com.example.dianzicheng.data.backup.WebDavManager
import com.example.dianzicheng.data.health.HealthConnectManager

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
                val state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, android.bluetooth.BluetoothAdapter.ERROR)
                if (state == android.bluetooth.BluetoothAdapter.STATE_ON) {
                    // 蓝牙打开后，若已记住任意一台设备且当前处于 IDLE，自动恢复扫描
                    if (bleClient.hasRememberedDevice && bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE) {
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
            android.widget.Toast.makeText(
                this,
                "蓝牙权限被拒绝，请前往「设置 → 应用 → 权限」手动开启蓝牙权限，否则无法搜索体脂秤",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkPermissions()
        
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java, "scale-db"
        ).build()
        
        bleClient = BleScaleClient(applicationContext)
        scaleRepository = ScaleRepository(database.scaleDao())
        profileRepository = ProfileRepository(database.scaleDao())
        preferenceManager = PreferenceManager(applicationContext)
        healthConnectManager = HealthConnectManager(applicationContext)
        webDavManager = WebDavManager(database.scaleDao())

        // 确保系统中存在默认用户（"自己"），保证首次称重时 BMI 与身体成分正常计算
        lifecycleScope.launch {
            profileRepository.ensureDefaultMemberExists()
        }

        // 旧版单设备配置一次性迁移为多设备列表：保证老用户升级后原有体脂秤不丢失
        lifecycleScope.launch {
            preferenceManager.migrateLegacyDeviceIfNeeded()
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
            }
        }

        lifecycleScope.launch {
            preferenceManager.pairedMac.collect { mac ->
                bleClient.lastPairedMac = mac  // null clears memory, preventing stale reconnect
                if (mac.isNullOrEmpty()) {
                    bleClient.disconnectAndReset()
                } else {
                    bleClient.isPairingMode = false
                    if (bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE && bleClient.isBluetoothEnabled()) {
                        bleClient.startScan()
                    }
                }
            }
        }

        val filter = android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, filter)

        enableEdgeToEdge()
        setContent {
            val isPairingComplete by preferenceManager.isPairingComplete.collectAsState(initial = null)
            
            电子秤Theme {
                val scaleViewModel: ScaleViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ScaleViewModel(
                                bleClient,
                                scaleRepository,
                                preferenceManager,
                                healthConnectManager
                            ) as T
                        }
                    }
                )
                val historyViewModel: HistoryViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return HistoryViewModel(scaleRepository, preferenceManager, healthConnectManager) as T
                        }
                    }
                )
                val profileViewModel: ProfileViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            return ProfileViewModel(
                                profileRepository,
                                preferenceManager,
                                webDavManager,
                                healthConnectManager,
                                scaleRepository
                            ) as T
                        }
                    }
                )

                MainScreen(
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

    override fun onStart() {
        super.onStart()
        // 回到前台：如果已记住任意一台设备且当前为空闲状态，自动恢复扫描以保持踏秤即连
        if (bleClient.hasRememberedDevice) {
            bleClient.isPairingMode = false
            if (bleClient.connectionState.value == BleScaleClient.ConnectionState.IDLE) {
                bleClient.startScan()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 进入后台：暂停 BLE 低延迟扫描，节约电量并符合 Android 后台规范
        bleClient.stopScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {}
        bleClient.disconnectAndReset()
    }
}
