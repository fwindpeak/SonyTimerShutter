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

    private var countdownJob: Job? = null
    private var hintJob: Job? = null
    private var cancelBurstAfterCurrentShot = false

    val uiState: StateFlow<ShutterUiState> = combine(
        bleController.connectionState,
        phaseFlow,
        configuredSecondsFlow,
        burstEnabledFlow,
        burstCountFlow,
        burstIntervalSecondsFlow,
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
            burstEnabled = values[3] as Boolean,
            burstCount = values[4] as Int,
            burstIntervalSeconds = values[5] as Int,
            showCameraPicker = values[6] as Boolean,
            showTimerSheet = values[7] as Boolean,
            discoveredCameras = values[8] as List<DiscoveredCamera>,
            isScanning = values[9] as Boolean,
            statusHint = values[10] as String?
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = ShutterUiState(
            configuredSeconds = configuredSecondsFlow.value,
            burstEnabled = burstEnabledFlow.value,
            burstCount = burstCountFlow.value,
            burstIntervalSeconds = burstIntervalSecondsFlow.value
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

        if (bleController.connectionState.value !is BleConnectionState.Connected) {
            feedbackPlayer.playMechanicalPress()
            openCameraPicker()
            return
        }

        startCountdown()
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

                // 倒计时结束，此时才通过 BLE 通知索尼相机按下快门
                phaseFlow.value = ShutterPhase.Triggering(
                    currentShot = shot,
                    totalShots = totalShots
                )
                feedbackPlayer.playShutterTrigger()

                val result = bleController.triggerShutter()
                if (result.isFailure) {
                    val reason = result.exceptionOrNull()?.message ?: "快门发送失败，请检查相机蓝牙遥控开关"
                    showTemporaryHint(
                        if (totalShots > 1) "第 $shot/$totalShots 张失败：$reason" else reason
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

    companion object {
        const val MIN_TIMER_SECONDS = 1
        const val MAX_TIMER_SECONDS = 60
        const val MIN_BURST_COUNT = 2
        const val MAX_BURST_COUNT = 99
        const val MIN_BURST_INTERVAL_SECONDS = 1
        const val MAX_BURST_INTERVAL_SECONDS = 60
    }
}
