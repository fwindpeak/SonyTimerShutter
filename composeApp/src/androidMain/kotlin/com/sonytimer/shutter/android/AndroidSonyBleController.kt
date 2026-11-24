package com.sonytimer.shutter.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.sonytimer.shutter.controller.BleConnectionState
import com.sonytimer.shutter.controller.CameraBleController
import com.sonytimer.shutter.controller.DiscoveredCamera
import com.sonytimer.shutter.protocol.SonyBleProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Android 端索尼相机 BLE GATT 遥控控制器（支持 A7C II / ILCE-7CM2 等索尼 Alpha 系列）。
 */
@SuppressLint("MissingPermission")
class AndroidSonyBleController(
    context: Context,
    private val hasBlePermissions: () -> Boolean,
    private val onPermissionsMissing: () -> Unit
) : CameraBleController {

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _discoveredCameras = MutableStateFlow<List<DiscoveredCamera>>(emptyList())
    override val discoveredCameras: StateFlow<List<DiscoveredCamera>> = _discoveredCameras.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val cameraCache = LinkedHashMap<String, DiscoveredCamera>()

    private var activeGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingBondDevice: BluetoothDevice? = null

    private val writeMutex = Mutex()
    @Volatile
    private var pendingWriteResult: CompletableDeferred<Boolean>? = null
    @Volatile
    private var focusAcquiredSignal: CompletableDeferred<Boolean>? = null

    private val serviceUuid = UUID.fromString(SonyBleProtocol.SERVICE_REMOTE_CONTROL_UUID)
    private val commandUuid = UUID.fromString(SonyBleProtocol.CHAR_REMOTE_COMMAND_UUID)
    private val notifyUuid = UUID.fromString(SonyBleProtocol.CHAR_REMOTE_NOTIFY_UUID)
    private val cccdUuid = UUID.fromString(SonyBleProtocol.CCCD_UUID)

    private var bondReceiverRegistered = false

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            if (device.address != pendingBondDevice?.address) return

            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            when (state) {
                BluetoothDevice.BOND_BONDED -> {
                    pendingBondDevice = null
                    connectGattInternal(device)
                }
                BluetoothDevice.BOND_NONE -> {
                    pendingBondDevice = null
                    _connectionState.value = BleConnectionState.Error("配对未完成，请在相机屏幕确认")
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val sonyData = record.getManufacturerSpecificData(SonyBleProtocol.SONY_MANUFACTURER_ID)
            val advInfo = SonyBleProtocol.parseManufacturerData(sonyData)
            if (advInfo == null || !advInfo.isCamera) return

            val device = result.device ?: return
            val name = device.name?.takeIf { it.isNotBlank() }
                ?: record.deviceName?.takeIf { it.isNotBlank() }
                ?: "Sony Camera"

            val entry = DiscoveredCamera(
                name = name,
                address = device.address,
                rssi = result.rssi,
                isBonded = device.bondState == BluetoothDevice.BOND_BONDED,
                isPairingMode = advInfo.isPairingMode
            )
            cameraCache[device.address] = entry
            publishCameraList()
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            _connectionState.value = BleConnectionState.Error("蓝牙扫描失败 (code=$errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val camName = gatt.device.name ?: "Sony Camera"
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGattInternal()
                _connectionState.value = BleConnectionState.Error("连接断开 (status=$status)")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectionState.Connecting(camName)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGattInternal()
                    _connectionState.value = BleConnectionState.Disconnected
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGattInternal()
                _connectionState.value = BleConnectionState.Error("服务发现失败")
                return
            }

            val remoteService = gatt.getService(serviceUuid)
            val cmdChar = remoteService?.getCharacteristic(commandUuid)
            val notifyChar = remoteService?.getCharacteristic(notifyUuid)

            if (remoteService == null || cmdChar == null) {
                closeGattInternal()
                _connectionState.value = BleConnectionState.Error("未找到索尼遥控服务，请开启相机「蓝牙遥控」")
                return
            }

            commandCharacteristic = cmdChar

            // 订阅 0xFF02 相机状态通知（对焦/快门状态反馈）
            if (notifyChar != null) {
                gatt.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(cccdUuid)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                }
            }

            val camName = gatt.device.name ?: "Sony Camera"
            _connectionState.value = BleConnectionState.Connected(
                cameraName = camName,
                address = gatt.device.address
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            pendingWriteResult?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == notifyUuid) {
                handleNotify(value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == notifyUuid) {
                characteristic.value?.let(::handleNotify)
            }
        }
    }

    private fun handleNotify(payload: ByteArray) {
        when (SonyBleProtocol.parseNotify(payload)) {
            SonyBleProtocol.CameraNotifyEvent.FocusAcquired -> {
                focusAcquiredSignal?.complete(true)
            }
            else -> Unit
        }
    }

    override fun startScan() {
        if (!hasBlePermissions()) {
            onPermissionsMissing()
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = BleConnectionState.Error("请先打开手机蓝牙")
            return
        }

        cameraCache.clear()
        // 先载入系统已配对的索尼相机，方便直接一键回连
        adapter.bondedDevices.orEmpty().forEach { device ->
            val name = device.name.orEmpty()
            if (isLikelySonyCameraName(name)) {
                cameraCache[device.address] = DiscoveredCamera(
                    name = name,
                    address = device.address,
                    rssi = 0,
                    isBonded = true,
                    isPairingMode = false
                )
            }
        }
        publishCameraList()

        val scanner = adapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setManufacturerData(SonyBleProtocol.SONY_MANUFACTURER_ID, byteArrayOf())
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _isScanning.value = true
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    override fun stopScan() {
        if (!_isScanning.value) return
        _isScanning.value = false
        if (!hasBlePermissions()) return
        runCatching {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    override fun connect(camera: DiscoveredCamera) {
        if (!hasBlePermissions()) {
            onPermissionsMissing()
            return
        }
        val adapter = bluetoothAdapter ?: run {
            _connectionState.value = BleConnectionState.Error("设备不支持蓝牙")
            return
        }

        stopScan()
        closeGattInternal()

        val device = runCatching { adapter.getRemoteDevice(camera.address) }.getOrNull() ?: run {
            _connectionState.value = BleConnectionState.Error("无效的相机地址")
            return
        }

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            connectGattInternal(device)
        } else {
            ensureBondReceiverRegistered()
            pendingBondDevice = device
            _connectionState.value = BleConnectionState.Pairing(camera.name)
            val started = device.createBond()
            if (!started) {
                pendingBondDevice = null
                _connectionState.value = BleConnectionState.Error("发起配对失败，请确认相机处于配对界面")
            }
        }
    }

    override fun disconnect() {
        pendingBondDevice = null
        closeGattInternal()
        _connectionState.value = BleConnectionState.Disconnected
    }

    override suspend fun triggerShutter(): Result<Unit> {
        if (_connectionState.value !is BleConnectionState.Connected) {
            return Result.failure(IllegalStateException("未连接到索尼相机"))
        }
        if (activeGatt == null || commandCharacteristic == null) {
            return Result.failure(IllegalStateException("蓝牙通道未就绪"))
        }

        return writeMutex.withLock {
            val focusWaiter = CompletableDeferred<Boolean>()
            focusAcquiredSignal = focusWaiter

            try {
                // 1. 半按对焦 (0x01 0x07)
                if (!writePacketLocked(SonyBleProtocol.CMD_SHUTTER_HALF_DOWN)) {
                    return@withLock Result.failure(IllegalStateException("半按对焦指令写入失败"))
                }

                // 等待相机 0xFF02 返回对焦成功，若处于 MF 或超时 450ms 则直接继续按下快门
                withTimeoutOrNull(450L) { focusWaiter.await() }

                // 2. 全按快门 (0x01 0x09)
                if (!writePacketLocked(SonyBleProtocol.CMD_SHUTTER_FULL_DOWN)) {
                    return@withLock Result.failure(IllegalStateException("快门触发指令写入失败"))
                }
                delay(120L)
                Result.success(Unit)
            } finally {
                focusAcquiredSignal = null
                // 3 & 4. 必须成对释放全按与半按，防止索尼相机锁死在半按状态
                runCatching { writePacketLocked(SonyBleProtocol.CMD_SHUTTER_FULL_UP) }
                delay(60L)
                runCatching { writePacketLocked(SonyBleProtocol.CMD_SHUTTER_HALF_UP) }
            }
        }
    }

    private suspend fun writePacketLocked(payload: ByteArray): Boolean {
        val gatt = activeGatt ?: return false
        val char = commandCharacteristic ?: return false

        val deferred = CompletableDeferred<Boolean>()
        pendingWriteResult = deferred

        val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                char,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = payload
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }

        if (!initiated) {
            pendingWriteResult = null
            return false
        }

        val ok = withTimeoutOrNull(2_000L) { deferred.await() } ?: false
        pendingWriteResult = null
        return ok
    }

    private fun connectGattInternal(device: BluetoothDevice) {
        _connectionState.value = BleConnectionState.Connecting(device.name ?: "Sony Camera")
        activeGatt = device.connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private fun closeGattInternal() {
        pendingWriteResult?.complete(false)
        pendingWriteResult = null
        focusAcquiredSignal?.complete(false)
        focusAcquiredSignal = null
        commandCharacteristic = null
        runCatching {
            activeGatt?.disconnect()
            activeGatt?.close()
        }
        activeGatt = null
    }

    private fun ensureBondReceiverRegistered() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            appContext.registerReceiver(bondReceiver, filter)
        }
        bondReceiverRegistered = true
    }

    private fun publishCameraList() {
        _discoveredCameras.value = cameraCache.values
            .sortedWith(
                compareByDescending<DiscoveredCamera> { it.isPairingMode }
                    .thenByDescending { it.isBonded }
                    .thenByDescending { it.rssi }
            )
    }

    private fun isLikelySonyCameraName(name: String): Boolean {
        val upper = name.uppercase()
        return upper.contains("ILCE") ||
            upper.contains("SONY") ||
            upper.contains("A7C") ||
            upper.contains("ZV-") ||
            upper.contains("DSC-") ||
            upper.contains("FX")
    }

    override fun release() {
        stopScan()
        closeGattInternal()
        if (bondReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(bondReceiver) }
            bondReceiverRegistered = false
        }
    }
}
