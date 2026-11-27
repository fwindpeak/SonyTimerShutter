package com.sonytimer.shutter.controller

import kotlinx.coroutines.flow.StateFlow

data class DiscoveredCamera(
    val name: String,
    val address: String,
    val rssi: Int,
    val isBonded: Boolean,
    val isPairingMode: Boolean
)

sealed interface BleConnectionState {
    data object Disconnected : BleConnectionState
    data class Connecting(val cameraName: String) : BleConnectionState
    data class Pairing(val cameraName: String) : BleConnectionState
    data class Connected(val cameraName: String, val address: String) : BleConnectionState
    data class Error(val message: String) : BleConnectionState
}

interface CameraBleController {
    val connectionState: StateFlow<BleConnectionState>
    val discoveredCameras: StateFlow<List<DiscoveredCamera>>
    val isScanning: StateFlow<Boolean>

    fun startScan()
    fun stopScan()
    fun connect(camera: DiscoveredCamera)
    fun disconnect()

    /**
     * 向已连接的索尼相机发送完整快门释放序列：
     * Half Down (0x0107) -> Full Down (0x0109) -> Full Up (0x0108) -> Half Up (0x0106)
     */
    suspend fun triggerShutter(): Result<Unit>

    /**
     * 仅触发一次半按对焦（不释放快门）：
     * Half Down (0x0107) -> 等待对焦 -> Half Up (0x0106)
     */
    suspend fun triggerFocus(): Result<Unit>

    fun release()
}
