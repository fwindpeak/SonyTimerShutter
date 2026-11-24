package com.sonytimer.shutter.controller

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
    data class CountingDown(val remainingSeconds: Int, val totalSeconds: Int) : ShutterPhase
    data object Triggering : ShutterPhase
    data object ShotSuccess : ShutterPhase
}

data class ShutterUiState(
    val connectionState: BleConnectionState = BleConnectionState.Disconnected,
    val phase: ShutterPhase = ShutterPhase.Idle,
    val configuredSeconds: Int = 5,
    val showCameraPicker: Boolean = false,
    val showTimerSheet: Boolean = false,
    val discoveredCameras: List<DiscoveredCamera> = emptyList(),
    val isScanning: Boolean = false,
    val statusHint: String? = null
)

class ShutterTimerViewModel(
    private val scope: CoroutineScope,
    private val bleController: CameraBleController,
    private val feedbackPlayer: FeedbackPlayer,
    private val preferences: TimerPreferences
) {
    private val phaseFlow = MutableStateFlow<ShutterPhase>(ShutterPhase.Idle)
    private val configuredSecondsFlow = MutableStateFlow(
        preferences.getTimerSeconds(defaultValue = 5).coerceIn(1, 60)
    )
    private val showCameraPickerFlow = MutableStateFlow(false)
    private val showTimerSheetFlow = MutableStateFlow(false)
    private val statusHintFlow = MutableStateFlow<String?>(null)

    private var countdownJob: Job? = null
    private var hintJob: Job? = null

    val uiState: StateFlow<ShutterUiState> = combine(
        bleController.connectionState,
        phaseFlow,
        configuredSecondsFlow,
        showCameraPickerFlow,
        showTimerSheetFlow,
        bleController.discoveredCameras,
        bleController.isScanning,
        statusHintFlow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        ShutterUiState(
            connectionState = values[0] as BleConnectionState,
            phase = values[1] as ShutterPhase,
            configuredSeconds = values[2] as Int,
            showCameraPicker = values[3] as Boolean,
            showTimerSheet = values[4] as Boolean,
            discoveredCameras = values[5] as List<DiscoveredCamera>,
            isScanning = values[6] as Boolean,
            statusHint = values[7] as String?
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ShutterUiState(configuredSeconds = configuredSecondsFlow.value)
    )

    fun onMainButtonClick() {
        val currentPhase = phaseFlow.value
        when (currentPhase) {
            is ShutterPhase.CountingDown -> {
                cancelCountdown()
                return
            }
            is ShutterPhase.Triggering -> return
            else -> Unit
        }

        if (bleController.connectionState.value !is BleConnectionState.Connected) {
            feedbackPlayer.playMechanicalPress()
            openCameraPicker()
            return
        }

        startCountdown()
    }

    fun onMainButtonLongPress() {
        if (phaseFlow.value is ShutterPhase.CountingDown || phaseFlow.value is ShutterPhase.Triggering) {
            return
        }
        feedbackPlayer.playLongPressDetent()
        showTimerSheetFlow.value = true
    }

    fun updateTimerSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(1, 60)
        configuredSecondsFlow.value = clamped
        preferences.setTimerSeconds(clamped)
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

    private fun startCountdown() {
        val total = configuredSecondsFlow.value
        countdownJob?.cancel()
        feedbackPlayer.playMechanicalPress()

        countdownJob = scope.launch {
            for (remaining in total downTo 1) {
                phaseFlow.value = ShutterPhase.CountingDown(
                    remainingSeconds = remaining,
                    totalSeconds = total
                )
                feedbackPlayer.playCountdownTick(finalTick = remaining == 1)
                delay(1_000L)
            }

            // 倒计时结束，此时才通过 BLE 通知索尼相机按下快门
            phaseFlow.value = ShutterPhase.Triggering
            feedbackPlayer.playShutterTrigger()

            val result = bleController.triggerShutter()
            if (result.isSuccess) {
                phaseFlow.value = ShutterPhase.ShotSuccess
                showTemporaryHint("快门已触发")
                delay(650L)
            } else {
                showTemporaryHint(result.exceptionOrNull()?.message ?: "快门发送失败，请检查相机蓝牙遥控开关")
            }
            phaseFlow.value = ShutterPhase.Idle
        }
    }

    private fun cancelCountdown(silent: Boolean = false) {
        countdownJob?.cancel()
        countdownJob = null
        phaseFlow.value = ShutterPhase.Idle
        if (!silent) {
            feedbackPlayer.playCancel()
            showTemporaryHint("已取消倒计时")
        }
    }

    private fun showTemporaryHint(message: String) {
        hintJob?.cancel()
        statusHintFlow.value = message
        hintJob = scope.launch {
            delay(2_200L)
            statusHintFlow.value = null
        }
    }

    fun onCleared() {
        countdownJob?.cancel()
        hintJob?.cancel()
        bleController.release()
        feedbackPlayer.release()
    }
}
