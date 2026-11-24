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
import android.util.Log
import com.sonytimer.shutter.android.SonyBleWritePolicy.GattWriteOutcome
import com.sonytimer.shutter.controller.BleConnectionState
import com.sonytimer.shutter.controller.CameraBleController
import com.sonytimer.shutter.controller.DiscoveredCamera
import com.sonytimer.shutter.protocol.SonyBleProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Android 端索尼相机 BLE GATT 遥控控制器（支持 A7C II / ILCE-7CM2 等索尼 Alpha 系列，
 * 针对 HarmonyOS / 卓易通及 Android 13+ 新蓝牙栈做了串行化与写模式自适应）。
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectionJob: Job? = null

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
    private var lastConnectedDevice: BluetoothDevice? = null

    private val writeMutex = Mutex()
    @Volatile
    private var pendingDescriptorResult: CompletableDeferred<Int>? = null
    @Volatile
    private var pendingWriteResult: CompletableDeferred<Int>? = null
    @Volatile
    private var focusAcquiredSignal: CompletableDeferred<Boolean>? = null
    @Volatile
    private var bondCompleteSignal: CompletableDeferred<Boolean>? = null

    @Volatile
    private var preferredWriteType: Int? = null
    @Volatile
    private var cccdWriteTimedOut: Boolean = false
    @Volatile
    private var skipCccdOnNextConnect: Boolean = false

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

            val isTarget = device.address == pendingBondDevice?.address ||
                device.address == activeGatt?.device?.address
            if (!isTarget) return

            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            Log.d(TAG, "Bond state changed for ${device.address}: state=$state")
            when (state) {
                BluetoothDevice.BOND_BONDED -> {
                    bondCompleteSignal?.complete(true)
                    bondCompleteSignal = null
                    if (device.address == pendingBondDevice?.address) {
                        pendingBondDevice = null
                        connectionJob?.cancel()
                        connectionJob = scope.launch {
                            // 鸿蒙/部分机型刚完成配对时加密密钥尚未就绪，稍作等待再发起 GATT 连接
                            delay(350L)
                            connectGattInternal(device)
                        }
                    }
                }
                BluetoothDevice.BOND_NONE -> {
                    bondCompleteSignal?.complete(false)
                    bondCompleteSignal = null
                    if (device.address == pendingBondDevice?.address) {
                        pendingBondDevice = null
                        _connectionState.value = BleConnectionState.Error("配对未完成，请在相机屏幕确认")
                    }
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
            if (gatt !== activeGatt) {
                Log.w(TAG, "Ignoring stale onConnectionStateChange status=$status newState=$newState")
                runCatching {
                    gatt.disconnect()
                    gatt.close()
                }
                return
            }

            val camName = gatt.device.name ?: "Sony Camera"
            Log.d(TAG, "onConnectionStateChange: status=$status newState=$newState bondState=${gatt.device.bondState}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGattInternal()
                _connectionState.value = BleConnectionState.Error("连接断开 (status=$status)")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = BleConnectionState.Connecting(camName)
                    connectionJob?.cancel()
                    connectionJob = scope.launch {
                        if (gatt.device.bondState == BluetoothDevice.BOND_BONDING) {
                            Log.d(TAG, "Waiting for ongoing bonding before discoverServices")
                            waitForBonding(10_000L)
                        }
                        // 给鸿蒙/卓易通蓝牙栈与相机预留链路稳定窗口再发现服务
                        delay(300L)
                        if (gatt !== activeGatt) return@launch
                        var started = runCatching { gatt.discoverServices() }.getOrDefault(false)
                        if (!started) {
                            delay(300L)
                            if (gatt !== activeGatt) return@launch
                            started = runCatching { gatt.discoverServices() }.getOrDefault(false)
                        }
                        if (!started && gatt === activeGatt) {
                            closeGattInternal()
                            _connectionState.value = BleConnectionState.Error("启动服务发现失败")
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGattInternal()
                    _connectionState.value = BleConnectionState.Disconnected
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== activeGatt) return
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGattInternal()
                _connectionState.value = BleConnectionState.Error("服务发现失败 (status=$status)")
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
            lastConnectedDevice = gatt.device
            Log.d(TAG, "RemoteCommand char discovered: props=0x${cmdChar.properties.toString(16)}, notifyProps=${notifyChar?.properties}")

            val camName = gatt.device.name ?: "Sony Camera"
            connectionJob?.cancel()
            connectionJob = scope.launch {
                // 等待服务发现后的内部状态机安定，避免立刻写 CCCD 触发 201 BUSY
                delay(150L)
                if (gatt !== activeGatt) return@launch

                if (notifyChar != null && !skipCccdOnNextConnect) {
                    val outcome = enableNotifications(gatt, notifyChar)
                    if (outcome is GattWriteOutcome.Failed) {
                        if (outcome.stage == "callback" && outcome.code != null &&
                            SonyBleWritePolicy.isAuthErrorStatus(outcome.code)
                        ) {
                            Log.w(TAG, "CCCD write auth failure status=${outcome.code}, attempting bond recovery")
                            val recovered = recoverAuthentication(gatt.device)
                            val retryOutcome = if (recovered && gatt === activeGatt) {
                                enableNotifications(gatt, notifyChar)
                            } else {
                                outcome
                            }
                            if (retryOutcome is GattWriteOutcome.Failed &&
                                retryOutcome.stage == "callback" &&
                                retryOutcome.code != null &&
                                SonyBleWritePolicy.isAuthErrorStatus(retryOutcome.code)
                            ) {
                                closeGattInternal()
                                _connectionState.value = BleConnectionState.Error(
                                    "蓝牙未完成配对加密，请在系统蓝牙取消配对后重新配对"
                                )
                                return@launch
                            }
                        } else if (outcome.stage == "timeout") {
                            cccdWriteTimedOut = true
                            Log.w(TAG, "CCCD write timed out; continuing to Connected (notify optional)")
                        } else {
                            Log.w(TAG, "CCCD write non-fatal failure: $outcome")
                        }
                    }
                }

                if (gatt !== activeGatt) return@launch
                _connectionState.value = BleConnectionState.Connected(
                    cameraName = camName,
                    address = gatt.device.address
                )
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (gatt !== activeGatt) return
            Log.d(TAG, "onDescriptorWrite: uuid=${descriptor.uuid} status=$status")
            pendingDescriptorResult?.complete(status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (gatt !== activeGatt || characteristic.uuid != commandUuid) return
            Log.d(TAG, "onCharacteristicWrite: status=$status")
            pendingWriteResult?.complete(status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt !== activeGatt) return
            if (characteristic.uuid == notifyUuid) {
                handleNotify(value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (gatt !== activeGatt) return
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
        skipCccdOnNextConnect = false
        preferredWriteType = null
        cccdWriteTimedOut = false

        val device = runCatching { adapter.getRemoteDevice(camera.address) }.getOrNull() ?: run {
            _connectionState.value = BleConnectionState.Error("无效的相机地址")
            return
        }

        ensureBondReceiverRegistered()
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                connectGattInternal(device)
            }
            BluetoothDevice.BOND_BONDING -> {
                pendingBondDevice = device
                _connectionState.value = BleConnectionState.Pairing(camera.name)
            }
            else -> {
                pendingBondDevice = device
                _connectionState.value = BleConnectionState.Pairing(camera.name)
                val started = runCatching { device.createBond() }.getOrDefault(false)
                if (!started) {
                    pendingBondDevice = null
                    _connectionState.value = BleConnectionState.Error("发起配对失败，请确认相机处于配对界面")
                }
            }
        }
    }

    override fun disconnect() {
        pendingBondDevice = null
        skipCccdOnNextConnect = false
        closeGattInternal()
        _connectionState.value = BleConnectionState.Disconnected
    }

    private enum class ShutterStep(val label: String) {
        HALF_DOWN("半按对焦指令写入"),
        FULL_DOWN("快门触发指令写入")
    }

    private sealed interface ShutterAttemptResult {
        data object Success : ShutterAttemptResult
        data class Failure(val step: ShutterStep, val outcome: GattWriteOutcome.Failed) : ShutterAttemptResult
    }

    override suspend fun triggerShutter(): Result<Unit> {
        if (_connectionState.value !is BleConnectionState.Connected) {
            return Result.failure(IllegalStateException("未连接到索尼相机"))
        }
        if (activeGatt == null || commandCharacteristic == null) {
            return Result.failure(IllegalStateException("蓝牙通道未就绪"))
        }

        val firstAttempt = executeShutterSequenceLocked()
        if (firstAttempt is ShutterAttemptResult.Success) {
            return Result.success(Unit)
        }

        val failed = firstAttempt as ShutterAttemptResult.Failure
        val shouldAutoRecover = failed.step == ShutterStep.HALF_DOWN &&
            cccdWriteTimedOut &&
            failed.outcome.stage == "init" &&
            (failed.outcome.code == SonyBleWritePolicy.STATUS_WRITE_REQUEST_BUSY || failed.outcome.code == null) &&
            !skipCccdOnNextConnect

        if (shouldAutoRecover) {
            val device = lastConnectedDevice ?: activeGatt?.device
            if (device != null && reconnectWithoutCccd(device)) {
                val retryAttempt = executeShutterSequenceLocked()
                if (retryAttempt is ShutterAttemptResult.Success) {
                    return Result.success(Unit)
                }
                val retryFailed = retryAttempt as ShutterAttemptResult.Failure
                return Result.failure(
                    IllegalStateException(
                        SonyBleWritePolicy.formatWriteFailureMessage(retryFailed.step.label, retryFailed.outcome)
                    )
                )
            }
        }

        return Result.failure(
            IllegalStateException(
                SonyBleWritePolicy.formatWriteFailureMessage(failed.step.label, failed.outcome)
            )
        )
    }

    private suspend fun executeShutterSequenceLocked(): ShutterAttemptResult {
        return writeMutex.withLock {
            val focusWaiter = CompletableDeferred<Boolean>()
            focusAcquiredSignal = focusWaiter
            var halfPressed = false
            var fullPressed = false

            try {
                // 1. 半按对焦 (0x01 0x07)
                val halfOutcome = writePacketLocked(SonyBleProtocol.CMD_SHUTTER_HALF_DOWN)
                if (halfOutcome is GattWriteOutcome.Failed) {
                    Log.w(TAG, "Half-press write failed: $halfOutcome")
                    return@withLock ShutterAttemptResult.Failure(ShutterStep.HALF_DOWN, halfOutcome)
                }
                halfPressed = true

                // 等待相机 0xFF02 返回对焦成功，若处于 MF 或超时 450ms 则直接继续按下快门
                withTimeoutOrNull(450L) { focusWaiter.await() }

                // 2. 全按快门 (0x01 0x09)
                val fullOutcome = writePacketLocked(SonyBleProtocol.CMD_SHUTTER_FULL_DOWN)
                if (fullOutcome is GattWriteOutcome.Failed) {
                    Log.w(TAG, "Full-press write failed: $fullOutcome")
                    return@withLock ShutterAttemptResult.Failure(ShutterStep.FULL_DOWN, fullOutcome)
                }
                fullPressed = true
                delay(120L)
                ShutterAttemptResult.Success
            } finally {
                focusAcquiredSignal = null
                // 3 & 4. 仅在已成功下发对应按下指令时成对释放，避免失败后追加超时写入
                withContext(NonCancellable) {
                    if (fullPressed) {
                        runCatching { writePacketLocked(SonyBleProtocol.CMD_SHUTTER_FULL_UP) }
                    }
                    if (fullPressed && halfPressed) {
                        delay(60L)
                    }
                    if (halfPressed) {
                        runCatching { writePacketLocked(SonyBleProtocol.CMD_SHUTTER_HALF_UP) }
                    }
                }
            }
        }
    }

    private suspend fun reconnectWithoutCccd(device: BluetoothDevice): Boolean {
        Log.w(TAG, "Reconnecting without CCCD subscription to recover from stuck GATT queue")
        closeGattInternal()
        skipCccdOnNextConnect = true
        connectGattInternal(device)
        val terminalState = withTimeoutOrNull(6_000L) {
            _connectionState.first { state ->
                state is BleConnectionState.Connected ||
                    state is BleConnectionState.Error ||
                    state is BleConnectionState.Disconnected
            }
        }
        return terminalState is BleConnectionState.Connected
    }

    private suspend fun enableNotifications(
        gatt: BluetoothGatt,
        notifyChar: BluetoothGattCharacteristic
    ): GattWriteOutcome {
        val localNotifyOk = runCatching {
            gatt.setCharacteristicNotification(notifyChar, true)
        }.getOrDefault(false)
        if (!localNotifyOk) {
            Log.w(TAG, "setCharacteristicNotification(true) returned false")
        }

        val cccd = notifyChar.getDescriptor(cccdUuid) ?: return GattWriteOutcome.Success
        val enableValue = when {
            (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> {
                Log.d(TAG, "Notify characteristic has neither NOTIFY nor INDICATE property: 0x${notifyChar.properties.toString(16)}")
                return GattWriteOutcome.Success
            }
        }

        return writeDescriptorWithRetry(gatt, cccd, enableValue)
    }

    private suspend fun writeDescriptorWithRetry(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): GattWriteOutcome {
        val retryDelays = longArrayOf(120L, 250L)
        for (attempt in 0..retryDelays.size) {
            if (gatt !== activeGatt) return GattWriteOutcome.Failed("disconnected")
            val outcome = writeDescriptorOnce(gatt, descriptor, value)
            if (outcome is GattWriteOutcome.Success) return outcome

            val failed = outcome as GattWriteOutcome.Failed
            val isBusy = failed.stage == "init" && SonyBleWritePolicy.isBusyInitFailure(
                isApi33Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                initCode = failed.code
            )
            if (isBusy && attempt < retryDelays.size) {
                Log.w(TAG, "writeDescriptor busy (attempt=${attempt + 1}), retrying in ${retryDelays[attempt]}ms")
                delay(retryDelays[attempt])
                continue
            }
            return failed
        }
        return GattWriteOutcome.Failed("init", SonyBleWritePolicy.STATUS_WRITE_REQUEST_BUSY)
    }

    private suspend fun writeDescriptorOnce(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): GattWriteOutcome {
        val deferred = CompletableDeferred<Int>()
        pendingDescriptorResult = deferred

        val initOutcome = initiateDescriptorWrite(gatt, descriptor, value)
        if (initOutcome != null) {
            pendingDescriptorResult = null
            return initOutcome
        }

        val status = withTimeoutOrNull(2_000L) { deferred.await() }
        pendingDescriptorResult = null
        return when {
            status == null -> GattWriteOutcome.Failed("timeout")
            status == BluetoothGatt.GATT_SUCCESS -> GattWriteOutcome.Success
            else -> GattWriteOutcome.Failed("callback", status)
        }
    }

    private fun initiateDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): GattWriteOutcome.Failed? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val code = try {
                gatt.writeDescriptor(descriptor, value)
            } catch (e: SecurityException) {
                Log.e(TAG, "writeDescriptor SecurityException", e)
                return GattWriteOutcome.Failed("permission", detail = e.message.orEmpty())
            } catch (t: Throwable) {
                Log.e(TAG, "writeDescriptor threw exception", t)
                null
            }

            if (code == BluetoothStatusCodes.SUCCESS) return null
            if (code == SonyBleWritePolicy.STATUS_MISSING_PERMISSION) {
                return GattWriteOutcome.Failed("permission", code)
            }
            if (code == null || SonyBleWritePolicy.shouldTryLegacyApiFallback(code)) {
                Log.w(TAG, "writeDescriptor(desc, val) returned code=$code, trying legacy writeDescriptor(desc)")
                val legacyOk = runLegacyDescriptorWrite(gatt, descriptor, value)
                if (legacyOk) return null
            }
            return GattWriteOutcome.Failed("init", code)
        } else {
            val legacyOk = runLegacyDescriptorWrite(gatt, descriptor, value)
            return if (legacyOk) null else GattWriteOutcome.Failed("init", null)
        }
    }

    @Suppress("DEPRECATION")
    private fun runLegacyDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return try {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        } catch (t: Throwable) {
            Log.e(TAG, "legacy writeDescriptor failed", t)
            false
        }
    }

    private suspend fun writePacketLocked(payload: ByteArray): GattWriteOutcome {
        val gatt = activeGatt ?: return GattWriteOutcome.Failed("disconnected")
        val char = gatt.getService(serviceUuid)?.getCharacteristic(commandUuid)
            ?: commandCharacteristic
            ?: return GattWriteOutcome.Failed("disconnected")
        commandCharacteristic = char

        val candidates = SonyBleWritePolicy.resolveCandidateWriteTypes(
            properties = char.properties,
            preferredWriteType = preferredWriteType
        )
        Log.d(TAG, "writePacketLocked: charProps=0x${char.properties.toString(16)}, candidates=$candidates")

        var lastFailure: GattWriteOutcome.Failed = GattWriteOutcome.Failed("unknown")
        for (writeType in candidates) {
            var outcome = writeWithBusyRetry(gatt, char, payload, writeType)
            if (outcome is GattWriteOutcome.Failed &&
                outcome.stage == "callback" &&
                outcome.code != null &&
                SonyBleWritePolicy.isAuthErrorStatus(outcome.code)
            ) {
                Log.w(TAG, "Characteristic write auth error status=${outcome.code}, attempting bond recovery")
                if (recoverAuthentication(gatt.device)) {
                    outcome = writeWithBusyRetry(gatt, char, payload, writeType)
                }
            }

            if (outcome is GattWriteOutcome.Success) {
                preferredWriteType = writeType
                return outcome
            }

            lastFailure = outcome as GattWriteOutcome.Failed
            if (!SonyBleWritePolicy.shouldTryNextWriteType(lastFailure)) {
                break
            }
            Log.w(TAG, "WriteType $writeType failed ($lastFailure), trying next candidate if available")
        }
        return lastFailure
    }

    private suspend fun writeWithBusyRetry(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int
    ): GattWriteOutcome {
        val retryDelays = longArrayOf(120L, 250L, 400L)
        for (attempt in 0..retryDelays.size) {
            if (gatt !== activeGatt) return GattWriteOutcome.Failed("disconnected", writeType = writeType)

            val outcome = writeOnceWithType(gatt, char, payload, writeType)
            if (outcome is GattWriteOutcome.Success) return outcome

            val failed = outcome as GattWriteOutcome.Failed
            val isBusy = failed.stage == "init" && SonyBleWritePolicy.isBusyInitFailure(
                isApi33Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                initCode = failed.code
            )
            if (isBusy && attempt < retryDelays.size) {
                Log.w(TAG, "writeCharacteristic busy (type=$writeType, attempt=${attempt + 1}), retrying in ${retryDelays[attempt]}ms")
                delay(retryDelays[attempt])
                continue
            }
            return failed
        }
        return GattWriteOutcome.Failed("init", SonyBleWritePolicy.STATUS_WRITE_REQUEST_BUSY, writeType)
    }

    private suspend fun writeOnceWithType(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int
    ): GattWriteOutcome {
        val deferred = CompletableDeferred<Int>()
        pendingWriteResult = deferred

        val initFailure = initiateCharacteristicWrite(gatt, char, payload, writeType)
        if (initFailure != null) {
            pendingWriteResult = null
            return initFailure
        }

        val status = withTimeoutOrNull(2_500L) { deferred.await() }
        pendingWriteResult = null
        return when {
            status == null -> GattWriteOutcome.Failed("timeout", writeType = writeType)
            status == BluetoothGatt.GATT_SUCCESS -> GattWriteOutcome.Success
            else -> GattWriteOutcome.Failed("callback", status, writeType)
        }
    }

    private fun initiateCharacteristicWrite(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int
    ): GattWriteOutcome.Failed? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val code = try {
                gatt.writeCharacteristic(char, payload, writeType)
            } catch (e: SecurityException) {
                Log.e(TAG, "writeCharacteristic SecurityException", e)
                return GattWriteOutcome.Failed("permission", writeType = writeType, detail = e.message.orEmpty())
            } catch (t: Throwable) {
                Log.e(TAG, "writeCharacteristic(3-arg) threw exception", t)
                null
            }

            if (code == BluetoothStatusCodes.SUCCESS) return null
            if (code == SonyBleWritePolicy.STATUS_MISSING_PERMISSION) {
                return GattWriteOutcome.Failed("permission", code, writeType)
            }
            if (code == null || SonyBleWritePolicy.shouldTryLegacyApiFallback(code)) {
                Log.w(TAG, "writeCharacteristic(3-arg, type=$writeType) returned code=$code, trying legacy 1-arg API")
                val legacyResult = runLegacyCharacteristicWrite(gatt, char, payload, writeType)
                if (legacyResult == null) return null
            }
            return GattWriteOutcome.Failed("init", code, writeType)
        } else {
            return runLegacyCharacteristicWrite(gatt, char, payload, writeType)
        }
    }

    @Suppress("DEPRECATION")
    private fun runLegacyCharacteristicWrite(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        payload: ByteArray,
        writeType: Int
    ): GattWriteOutcome.Failed? {
        return try {
            char.value = payload
            char.writeType = writeType
            if (gatt.writeCharacteristic(char)) {
                null
            } else {
                GattWriteOutcome.Failed("init", null, writeType)
            }
        } catch (e: SecurityException) {
            GattWriteOutcome.Failed("permission", writeType = writeType, detail = e.message.orEmpty())
        } catch (t: Throwable) {
            GattWriteOutcome.Failed("init", writeType = writeType, detail = t.message.orEmpty())
        }
    }

    private suspend fun waitForBonding(timeoutMs: Long): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        bondCompleteSignal = waiter
        val result = withTimeoutOrNull(timeoutMs) { waiter.await() } ?: false
        bondCompleteSignal = null
        return result
    }

    private suspend fun recoverAuthentication(device: BluetoothDevice): Boolean {
        return when (device.bondState) {
            BluetoothDevice.BOND_BONDING -> waitForBonding(10_000L)
            BluetoothDevice.BOND_NONE -> {
                ensureBondReceiverRegistered()
                val waiter = CompletableDeferred<Boolean>()
                bondCompleteSignal = waiter
                val started = runCatching { device.createBond() }.getOrDefault(false)
                if (!started) {
                    bondCompleteSignal = null
                    false
                } else {
                    val bonded = withTimeoutOrNull(10_000L) { waiter.await() } ?: false
                    bondCompleteSignal = null
                    if (bonded) delay(300L)
                    bonded
                }
            }
            else -> false
        }
    }

    private fun connectGattInternal(device: BluetoothDevice) {
        ensureBondReceiverRegistered()
        lastConnectedDevice = device
        _connectionState.value = BleConnectionState.Connecting(device.name ?: "Sony Camera")
        activeGatt = device.connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private fun closeGattInternal() {
        connectionJob?.cancel()
        connectionJob = null
        pendingDescriptorResult?.complete(SonyBleWritePolicy.GATT_ERROR)
        pendingDescriptorResult = null
        pendingWriteResult?.complete(SonyBleWritePolicy.GATT_ERROR)
        pendingWriteResult = null
        focusAcquiredSignal?.complete(false)
        focusAcquiredSignal = null
        bondCompleteSignal?.complete(false)
        bondCompleteSignal = null
        commandCharacteristic = null
        preferredWriteType = null
        cccdWriteTimedOut = false
        val gattToClose = activeGatt
        activeGatt = null
        runCatching {
            gattToClose?.disconnect()
            gattToClose?.close()
        }
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
        skipCccdOnNextConnect = false
        closeGattInternal()
        scope.cancel()
        if (bondReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(bondReceiver) }
            bondReceiverRegistered = false
        }
    }

    private companion object {
        const val TAG = "SonyBleController"
    }
}
