package com.sonytimer.shutter.controller

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ShutterPhase {
    data object Idle : ShutterPhase
    data class CountingDown(
        val remainingSeconds: Int,
        val totalSeconds: Int,
        val currentShot: Int = 1,
        val totalShots: Int = 1,
        val isInterval: Boolean = false
    ) : ShutterPhase
    data class Triggering(
        val currentShot: Int = 1,
        val totalShots: Int = 1
    ) : ShutterPhase
    data class ShotSuccess(
        val totalShots: Int = 1
    ) : ShutterPhase
}

private data class TimerConfigSnapshot(
    val configuredSeconds: Int,
    val burstEnabled: Boolean,
    val burstCount: Int,
    val burstIntervalSeconds: Int,
    val remoteTriggerEnabled: Boolean
)

private data class BleSnapshot(
    val connectionState: BleConnectionState,
    val discoveredCameras: List<DiscoveredCamera>,
    val isScanning: Boolean,
    val showCameraPicker: Boolean
)

private data class LiveViewSnapshot(
    val liveViewState: LiveViewConnectionState,
    val liveViewFrame: ImageBitmap?,
    val liveViewFps: Int,
    val isPreviewMirrored: Boolean,
    val showWifiSheet: Boolean,
    val wifiConfig: WifiCameraConfig,
    val detectedGatewayIp: String?
)

data class ShutterUiState(
    val connectionState: BleConnectionState = BleConnectionState.Disconnected,
    val phase: ShutterPhase = ShutterPhase.Idle,
    val configuredSeconds: Int = 5,
    val burstEnabled: Boolean = false,
    val burstCount: Int = 3,
    val burstIntervalSeconds: Int = 2,
    val showCameraPicker: Boolean = false,
    val showTimerSheet: Boolean = false,
    val discoveredCameras: List<DiscoveredCamera> = emptyList(),
    val isScanning: Boolean = false,
    val statusHint: String? = null,
    val liveViewState: LiveViewConnectionState = LiveViewConnectionState.Disconnected,
    val liveViewFrame: ImageBitmap? = null,
    val liveViewFps: Int = 0,
    val isPreviewMirrored: Boolean = false,
    val showWifiSheet: Boolean = false,
    val wifiConfig: WifiCameraConfig = WifiCameraConfig(),
    val detectedGatewayIp: String? = null,
    val remoteTriggerEnabled: Boolean = true
) {
    val isBleConnected: Boolean
        get() = connectionState is BleConnectionState.Connected

    val isWifiConnected: Boolean
        get() = liveViewState is LiveViewConnectionState.Connected

    val isAnyCameraConnected: Boolean
        get() = isBleConnected || isWifiConnected
}

class ShutterTimerViewModel(
    private val scope: CoroutineScope,
    private val bleController: CameraBleController,
    private val liveViewController: CameraLiveViewController,
    private val feedbackPlayer: FeedbackPlayer,
    private val preferences: TimerPreferences
) {
    private val phaseFlow = MutableStateFlow<ShutterPhase>(ShutterPhase.Idle)
    private val configuredSecondsFlow = MutableStateFlow(
        preferences.getTimerSeconds(defaultValue = 5).coerceIn(MIN_TIMER_SECONDS, MAX_TIMER_SECONDS)
    )
    private val burstEnabledFlow = MutableStateFlow(
        preferences.isBurstEnabled(defaultValue = false)
    )
    private val burstCountFlow = MutableStateFlow(
        preferences.getBurstCount(defaultValue = 3).coerceIn(MIN_BURST_COUNT, MAX_BURST_COUNT)
    )
    private val burstIntervalSecondsFlow = MutableStateFlow(
        preferences.getBurstIntervalSeconds(defaultValue = 2)
            .coerceIn(MIN_BURST_INTERVAL_SECONDS, MAX_BURST_INTERVAL_SECONDS)
    )
    private val showCameraPickerFlow = MutableStateFlow(false)
    private val showTimerSheetFlow = MutableStateFlow(false)
    private val statusHintFlow = MutableStateFlow<String?>(null)

    private val isPreviewMirroredFlow = MutableStateFlow(
        preferences.isPreviewMirrored(defaultValue = false)
    )
    private val remoteTriggerEnabledFlow = MutableStateFlow(
        preferences.isRemoteTriggerEnabled(defaultValue = true)
    )
    private val showWifiSheetFlow = MutableStateFlow(false)
    private val wifiConfigFlow = MutableStateFlow(
        WifiCameraConfig(
            host = preferences.getWifiHost(),
            useSsh = false,
            sshUsername = preferences.getWifiSshUsername(),
            sshPassword = preferences.getWifiSshPassword()
        )
    )
    private val detectedGatewayIpFlow = MutableStateFlow<String?>(null)

    private var countdownJob: Job? = null
    private var hintJob: Job? = null
    private var cancelBurstAfterCurrentShot = false

    init {
        scope.launch {
            var wasConnected = false
            liveViewController.connectionState.collect { state ->
                val isNowConnected = state is LiveViewConnectionState.Connected
                if (state is LiveViewConnectionState.Connected && !wasConnected) {
                    if (state.host.isNotBlank() && state.host != wifiConfigFlow.value.host) {
                        wifiConfigFlow.value = wifiConfigFlow.value.copy(host = state.host)
                        preferences.setWifiHost(state.host)
                    }
                    if (showWifiSheetFlow.value) {
                        showWifiSheetFlow.value = false
                    }
                    showTemporaryHint("已连接 ${state.cameraName} 实时预览", durationMs = 2_400L)
                }
                wasConnected = isNowConnected
            }
        }
    }

    private val timerConfigFlow = combine(
        configuredSecondsFlow,
        burstEnabledFlow,
        burstCountFlow,
        burstIntervalSecondsFlow,
        remoteTriggerEnabledFlow
    ) { seconds, burstEnabled, burstCount, burstInterval, remoteTriggerEnabled ->
        TimerConfigSnapshot(
            configuredSeconds = seconds,
            burstEnabled = burstEnabled,
            burstCount = burstCount,
            burstIntervalSeconds = burstInterval,
            remoteTriggerEnabled = remoteTriggerEnabled
        )
    }

    private val bleSnapshotFlow = combine(
        bleController.connectionState,
        bleController.discoveredCameras,
        bleController.isScanning,
        showCameraPickerFlow
    ) { conn, cameras, scanning, showPicker ->
        BleSnapshot(
            connectionState = conn,
            discoveredCameras = cameras,
            isScanning = scanning,
            showCameraPicker = showPicker
        )
    }

    private val liveViewMetaFlow = combine(
        isPreviewMirroredFlow,
        showWifiSheetFlow,
        wifiConfigFlow,
        detectedGatewayIpFlow
    ) { mirrored, showSheet, wifiConfig, gatewayIp ->
        Triple(mirrored, showSheet, wifiConfig to gatewayIp)
    }

    private val liveViewSnapshotFlow = combine(
        liveViewController.connectionState,
        liveViewController.latestFrame,
        liveViewController.fps,
        liveViewMetaFlow
    ) { lvState, frame, fps, meta ->
        LiveViewSnapshot(
            liveViewState = lvState,
            liveViewFrame = frame,
            liveViewFps = fps,
            isPreviewMirrored = meta.first,
            showWifiSheet = meta.second,
            wifiConfig = meta.third.first,
            detectedGatewayIp = meta.third.second
        )
    }

    val uiState: StateFlow<ShutterUiState> = combine(
        bleSnapshotFlow,
        timerConfigFlow,
        phaseFlow,
        showTimerSheetFlow,
        statusHintFlow,
        liveViewSnapshotFlow
    ) { values ->
        val ble = values[0] as BleSnapshot
        val timer = values[1] as TimerConfigSnapshot
        val phase = values[2] as ShutterPhase
        val showTimer = values[3] as Boolean
        val hint = values[4] as String?
        val lv = values[5] as LiveViewSnapshot
        ShutterUiState(
            connectionState = ble.connectionState,
            phase = phase,
            configuredSeconds = timer.configuredSeconds,
            burstEnabled = timer.burstEnabled,
            burstCount = timer.burstCount,
            burstIntervalSeconds = timer.burstIntervalSeconds,
            showCameraPicker = ble.showCameraPicker,
            showTimerSheet = showTimer,
            discoveredCameras = ble.discoveredCameras,
            isScanning = ble.isScanning,
            statusHint = hint,
            liveViewState = lv.liveViewState,
            liveViewFrame = lv.liveViewFrame,
            liveViewFps = lv.liveViewFps,
            isPreviewMirrored = lv.isPreviewMirrored,
            showWifiSheet = lv.showWifiSheet,
            wifiConfig = lv.wifiConfig,
            detectedGatewayIp = lv.detectedGatewayIp,
            remoteTriggerEnabled = timer.remoteTriggerEnabled
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ShutterUiState(
            configuredSeconds = configuredSecondsFlow.value,
            burstEnabled = burstEnabledFlow.value,
            burstCount = burstCountFlow.value,
            burstIntervalSeconds = burstIntervalSecondsFlow.value,
            isPreviewMirrored = isPreviewMirroredFlow.value,
            wifiConfig = wifiConfigFlow.value,
            remoteTriggerEnabled = remoteTriggerEnabledFlow.value
        )
    )

    fun onMainButtonClick() {
        val currentPhase = phaseFlow.value
        when (currentPhase) {
            is ShutterPhase.CountingDown -> {
                cancelCountdown()
                return
            }
            is ShutterPhase.Triggering -> {
                if (currentPhase.totalShots > 1 && currentPhase.currentShot < currentPhase.totalShots) {
                    cancelBurstAfterCurrentShot = true
                    showTemporaryHint("正在停止连拍...")
                }
                return
            }
            else -> Unit
        }

        val wifiReady = liveViewController.connectionState.value is LiveViewConnectionState.Connected
        val bleReady = bleController.connectionState.value is BleConnectionState.Connected
        if (!wifiReady && !bleReady) {
            feedbackPlayer.playMechanicalPress()
            openCameraPicker()
            return
        }

        startCountdown()
    }

    /**
     * 响应小米手环（蓝牙拍照 HID / 音乐控制）或蓝牙自拍器、音量键的远程快门触发。
     */
    fun onRemoteShutterTrigger(sourceLabel: String = "手环/遥控") {
        if (!remoteTriggerEnabledFlow.value) return
        if (showCameraPickerFlow.value || showTimerSheetFlow.value || showWifiSheetFlow.value) {
            return
        }

        when (val currentPhase = phaseFlow.value) {
            is ShutterPhase.CountingDown -> {
                cancelCountdown()
            }
            is ShutterPhase.Triggering -> {
                if (currentPhase.totalShots > 1 && currentPhase.currentShot < currentPhase.totalShots) {
                    cancelBurstAfterCurrentShot = true
                    showTemporaryHint("正在停止连拍...")
                }
            }
            is ShutterPhase.ShotSuccess -> Unit
            ShutterPhase.Idle -> {
                val wifiReady = liveViewController.connectionState.value is LiveViewConnectionState.Connected
                val bleReady = bleController.connectionState.value is BleConnectionState.Connected
                if (!wifiReady && !bleReady) {
                    feedbackPlayer.playMechanicalPress()
                    showTemporaryHint("请先连接索尼相机后再用${sourceLabel}拍照", durationMs = 3_000L)
                    return
                }
                startCountdown()
                showTemporaryHint("${sourceLabel}已触发倒计时", durationMs = 1_600L)
            }
        }
    }

    fun openTimerSheet() {
        if (phaseFlow.value is ShutterPhase.CountingDown || phaseFlow.value is ShutterPhase.Triggering) {
            return
        }
        showTimerSheetFlow.value = true
    }

    fun updateTimerSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(MIN_TIMER_SECONDS, MAX_TIMER_SECONDS)
        configuredSecondsFlow.value = clamped
        preferences.setTimerSeconds(clamped)
    }

    fun updateBurstEnabled(enabled: Boolean) {
        burstEnabledFlow.value = enabled
        preferences.setBurstEnabled(enabled)
    }

    fun updateBurstCount(count: Int) {
        val clamped = count.coerceIn(MIN_BURST_COUNT, MAX_BURST_COUNT)
        burstCountFlow.value = clamped
        preferences.setBurstCount(clamped)
    }

    fun updateBurstIntervalSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(MIN_BURST_INTERVAL_SECONDS, MAX_BURST_INTERVAL_SECONDS)
        burstIntervalSecondsFlow.value = clamped
        preferences.setBurstIntervalSeconds(clamped)
    }

    fun updateRemoteTriggerEnabled(enabled: Boolean) {
        remoteTriggerEnabledFlow.value = enabled
        preferences.setRemoteTriggerEnabled(enabled)
    }

    fun dismissTimerSheet() {
        showTimerSheetFlow.value = false
    }

    fun openCameraPicker() {
        showCameraPickerFlow.value = true
        bleController.startScan()
    }

    fun dismissCameraPicker() {
        showCameraPickerFlow.value = false
        bleController.stopScan()
    }

    fun onSelectCamera(camera: DiscoveredCamera) {
        bleController.stopScan()
        showCameraPickerFlow.value = false
        bleController.connect(camera)
    }

    fun onDisconnectCamera() {
        cancelCountdown(silent = true)
        bleController.disconnect()
        showCameraPickerFlow.value = false
    }

    fun refreshScan() {
        bleController.stopScan()
        bleController.startScan()
    }

    fun openWifiSheet() {
        detectedGatewayIpFlow.value = liveViewController.detectWifiGatewayIp()
        showWifiSheetFlow.value = true
        val currentState = liveViewController.connectionState.value
        if (currentState is LiveViewConnectionState.Disconnected ||
            currentState is LiveViewConnectionState.Error
        ) {
            autoConnectWifi()
        }
    }

    fun dismissWifiSheet() {
        showWifiSheetFlow.value = false
    }

    fun autoConnectWifi() {
        detectedGatewayIpFlow.value = liveViewController.detectWifiGatewayIp()
        liveViewController.autoConnect(preferredHost = wifiConfigFlow.value.host)
    }

    fun connectWifi(config: WifiCameraConfig) {
        val sanitized = config.copy(
            host = config.host.trim(),
            sshUsername = config.sshUsername.trim()
        )
        wifiConfigFlow.value = sanitized
        preferences.setWifiHost(sanitized.host)
        preferences.setWifiUseSsh(sanitized.useSsh)
        preferences.setWifiSshUsername(sanitized.sshUsername)
        preferences.setWifiSshPassword(sanitized.sshPassword)
        liveViewController.connect(sanitized)
    }

    fun disconnectWifi() {
        if (bleController.connectionState.value !is BleConnectionState.Connected) {
            cancelCountdown(silent = true)
        }
        liveViewController.disconnect()
        showWifiSheetFlow.value = false
    }

    fun togglePreviewMirror() {
        val next = !isPreviewMirroredFlow.value
        isPreviewMirroredFlow.value = next
        preferences.setPreviewMirrored(next)
    }

    private fun startCountdown() {
        val initialDelay = configuredSecondsFlow.value
        val totalShots = if (burstEnabledFlow.value) burstCountFlow.value else 1
        val intervalSeconds = burstIntervalSecondsFlow.value
        cancelBurstAfterCurrentShot = false

        countdownJob?.cancel()
        feedbackPlayer.playMechanicalPress()

        countdownJob = scope.launch {
            for (remaining in initialDelay downTo 1) {
                phaseFlow.value = ShutterPhase.CountingDown(
                    remainingSeconds = remaining,
                    totalSeconds = initialDelay,
                    currentShot = 1,
                    totalShots = totalShots,
                    isInterval = false
                )
                feedbackPlayer.playCountdownTick(finalTick = remaining == 1)
                delay(1_000L)
            }

            for (shot in 1..totalShots) {
                if (shot > 1) {
                    for (remaining in intervalSeconds downTo 1) {
                        phaseFlow.value = ShutterPhase.CountingDown(
                            remainingSeconds = remaining,
                            totalSeconds = intervalSeconds,
                            currentShot = shot,
                            totalShots = totalShots,
                            isInterval = true
                        )
                        feedbackPlayer.playCountdownTick(finalTick = remaining == 1)
                        delay(1_000L)
                    }
                }

                // 倒计时结束，优先走 Wi-Fi PTP/IP 快门（若已连接），否则走 BLE 快门
                phaseFlow.value = ShutterPhase.Triggering(
                    currentShot = shot,
                    totalShots = totalShots
                )
                feedbackPlayer.playShutterTrigger()

                val result = triggerConnectedShutter()
                if (result.isFailure) {
                    val reason = result.exceptionOrNull()?.message ?: "快门发送失败，请检查相机遥控设置"
                    showTemporaryHint(
                        message = if (totalShots > 1) "第 $shot/$totalShots 张失败：$reason" else reason,
                        durationMs = 4_500L
                    )
                    phaseFlow.value = ShutterPhase.Idle
                    return@launch
                }

                if (cancelBurstAfterCurrentShot && shot < totalShots) {
                    feedbackPlayer.playCancel()
                    showTemporaryHint("已停止连拍 ($shot/$totalShots)")
                    phaseFlow.value = ShutterPhase.Idle
                    return@launch
                }
            }

            phaseFlow.value = ShutterPhase.ShotSuccess(totalShots = totalShots)
            showTemporaryHint(
                if (totalShots > 1) "连拍完成 (${totalShots}张)" else "快门已触发"
            )
            delay(650L)
            phaseFlow.value = ShutterPhase.Idle
        }
    }

    private suspend fun triggerConnectedShutter(): Result<Unit> {
        return when {
            liveViewController.connectionState.value is LiveViewConnectionState.Connected ->
                liveViewController.triggerShutter()
            bleController.connectionState.value is BleConnectionState.Connected ->
                bleController.triggerShutter()
            else -> Result.failure(IllegalStateException("相机连接已断开"))
        }
    }

    private fun cancelCountdown(silent: Boolean = false) {
        val prevPhase = phaseFlow.value
        cancelBurstAfterCurrentShot = false
        countdownJob?.cancel()
        countdownJob = null
        phaseFlow.value = ShutterPhase.Idle
        if (!silent) {
            feedbackPlayer.playCancel()
            val message = if (prevPhase is ShutterPhase.CountingDown && prevPhase.isInterval) {
                val completed = (prevPhase.currentShot - 1).coerceAtLeast(0)
                "已停止连拍 ($completed/${prevPhase.totalShots})"
            } else {
                "已取消倒计时"
            }
            showTemporaryHint(message)
        }
    }

    private fun showTemporaryHint(message: String, durationMs: Long = 2_200L) {
        hintJob?.cancel()
        statusHintFlow.value = message
        hintJob = scope.launch {
            delay(durationMs)
            statusHintFlow.value = null
        }
    }

    fun onCleared() {
        countdownJob?.cancel()
        hintJob?.cancel()
        bleController.release()
        liveViewController.release()
        feedbackPlayer.release()
    }

    companion object {
        const val MIN_TIMER_SECONDS = 1
        const val MAX_TIMER_SECONDS = 60
        const val MIN_BURST_COUNT = 2
        const val MAX_BURST_COUNT = 99
        const val MIN_BURST_INTERVAL_SECONDS = 1
        const val MAX_BURST_INTERVAL_SECONDS = 60
    }
}
