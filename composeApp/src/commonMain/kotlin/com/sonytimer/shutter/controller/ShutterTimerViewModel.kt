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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

data class GalleryDownloadProgress(
    val photoKey: String,
    val bytesRead: Long,
    val totalBytes: Long
) {
    val fraction: Float
        get() = if (totalBytes > 0L) {
            (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

data class GalleryUiState(
    val isOpen: Boolean = false,
    val activeTransport: PtpTransportMode = PtpTransportMode.WIFI_PTP_IP,
    val usbState: UsbConnectionState = UsbConnectionState.Disconnected,
    val photos: List<CameraPhotoItem> = emptyList(),
    val thumbnails: Map<String, ImageBitmap> = emptyMap(),
    val largePreviews: Map<String, ImageBitmap> = emptyMap(),
    val loadingLargePreviewKey: String? = null,
    val loadingThumbKeys: Set<String> = emptySet(),
    val failedThumbKeys: Set<String> = emptySet(),
    val isLoadingList: Boolean = false,
    val errorMessage: String? = null,
    val noticeMessage: String? = null,
    val selectedPhotoKey: String? = null,
    val downloadProgress: GalleryDownloadProgress? = null,
    val downloadedPhotos: Map<String, SavedPhotoResult> = emptyMap(),
    val newPhotoBadgeCount: Int = 0
)

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
    val remoteTriggerEnabled: Boolean = true,
    val gallery: GalleryUiState = GalleryUiState(),
    val isFocusing: Boolean = false
) {
    val isBleConnected: Boolean
        get() = connectionState is BleConnectionState.Connected

    val isWifiConnected: Boolean
        get() = liveViewState is LiveViewConnectionState.Connected

    val isUsbConnected: Boolean
        get() = gallery.usbState is UsbConnectionState.Connected

    val isGalleryAvailable: Boolean
        get() = isWifiConnected || isUsbConnected

    val isAnyCameraConnected: Boolean
        get() = isBleConnected || isWifiConnected
}

class ShutterTimerViewModel(
    private val scope: CoroutineScope,
    private val bleController: CameraBleController,
    private val liveViewController: CameraLiveViewController,
    private val galleryController: CameraGalleryController,
    private val feedbackPlayer: FeedbackPlayer,
    private val preferences: TimerPreferences
) {
    private val phaseFlow = MutableStateFlow<ShutterPhase>(ShutterPhase.Idle)
    private val isFocusingFlow = MutableStateFlow(false)
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
    private val galleryFlow = MutableStateFlow(GalleryUiState())

    private var countdownJob: Job? = null
    private var focusJob: Job? = null
    private var hintJob: Job? = null
    private var galleryListJob: Job? = null
    private var galleryDownloadJob: Job? = null
    private val thumbnailSemaphore = Semaphore(1)
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

        scope.launch {
            var wasUsbConnected = false
            galleryController.usbState.collect { usb ->
                galleryFlow.update { it.copy(usbState = usb) }
                val isNowUsb = usb is UsbConnectionState.Connected
                if (usb is UsbConnectionState.Connected && !wasUsbConnected) {
                    showTemporaryHint("USB 已连接 ${usb.productName}，可高速查看图库", durationMs = 2_800L)
                    if (galleryFlow.value.isOpen &&
                        galleryFlow.value.activeTransport == PtpTransportMode.USB_MTP
                    ) {
                        refreshGallery()
                    }
                } else if (usb is UsbConnectionState.Error) {
                    if (galleryFlow.value.isOpen &&
                        galleryFlow.value.activeTransport == PtpTransportMode.USB_MTP
                    ) {
                        galleryFlow.update { it.copy(errorMessage = usb.message, isLoadingList = false) }
                    }
                }
                wasUsbConnected = isNowUsb
            }
        }

        scope.launch {
            galleryController.events.collect { event ->
                handlePtpEvent(event)
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
        liveViewSnapshotFlow,
        galleryFlow,
        isFocusingFlow
    ) { values ->
        val ble = values[0] as BleSnapshot
        val timer = values[1] as TimerConfigSnapshot
        val phase = values[2] as ShutterPhase
        val showTimer = values[3] as Boolean
        val hint = values[4] as String?
        val lv = values[5] as LiveViewSnapshot
        val gallery = values[6] as GalleryUiState
        val focusing = values[7] as Boolean
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
            remoteTriggerEnabled = timer.remoteTriggerEnabled,
            gallery = gallery,
            isFocusing = focusing
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
            remoteTriggerEnabled = remoteTriggerEnabledFlow.value,
            gallery = galleryFlow.value
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
     * 独立半按对焦按钮：仅触发对焦，不释放快门，用于拍照前调整对焦。
     */
    fun onFocusClick() {
        val currentPhase = phaseFlow.value
        if (currentPhase is ShutterPhase.CountingDown || currentPhase is ShutterPhase.Triggering) {
            return
        }
        if (isFocusingFlow.value) return

        val wifiReady = liveViewController.connectionState.value is LiveViewConnectionState.Connected
        val bleReady = bleController.connectionState.value is BleConnectionState.Connected
        if (!wifiReady && !bleReady) {
            feedbackPlayer.playMechanicalPress()
            showTemporaryHint("请先连接索尼相机后再对焦")
            openCameraPicker()
            return
        }

        feedbackPlayer.playMechanicalPress()
        focusJob?.cancel()
        focusJob = scope.launch {
            isFocusingFlow.value = true
            try {
                val result = when {
                    liveViewController.connectionState.value is LiveViewConnectionState.Connected ->
                        liveViewController.triggerFocus()
                    bleController.connectionState.value is BleConnectionState.Connected ->
                        bleController.triggerFocus()
                    else -> Result.failure(IllegalStateException("相机连接已断开"))
                }
                if (result.isSuccess) {
                    showTemporaryHint("对焦完成", durationMs = 1_600L)
                } else {
                    val reason = result.exceptionOrNull()?.message ?: "对焦失败，请检查相机遥控设置"
                    showTemporaryHint(reason, durationMs = 3_000L)
                }
            } finally {
                isFocusingFlow.value = false
            }
        }
    }

    /**
     * 响应小米手环（蓝牙拍照 HID / 音乐控制）或蓝牙自拍器、音量键的远程快门触发。
     */
    fun onRemoteShutterTrigger(sourceLabel: String = "手环/遥控") {
        if (!remoteTriggerEnabledFlow.value) return
        if (showCameraPickerFlow.value ||
            showTimerSheetFlow.value ||
            showWifiSheetFlow.value ||
            galleryFlow.value.isOpen
        ) {
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

    fun openGallery(preferredTransport: PtpTransportMode? = null) {
        val usbReady = galleryFlow.value.usbState is UsbConnectionState.Connected
        val wifiReady = liveViewController.connectionState.value is LiveViewConnectionState.Connected
        val targetMode = when {
            preferredTransport == PtpTransportMode.USB_MTP -> PtpTransportMode.USB_MTP
            preferredTransport == PtpTransportMode.WIFI_PTP_IP -> PtpTransportMode.WIFI_PTP_IP
            usbReady -> PtpTransportMode.USB_MTP
            wifiReady -> PtpTransportMode.WIFI_PTP_IP
            else -> PtpTransportMode.USB_MTP
        }

        showWifiSheetFlow.value = false
        galleryController.setGalleryActive(true)
        galleryFlow.update {
            it.copy(
                isOpen = true,
                activeTransport = targetMode,
                errorMessage = null,
                selectedPhotoKey = null,
                newPhotoBadgeCount = 0
            )
        }
        if (targetMode == PtpTransportMode.USB_MTP && !usbReady) {
            galleryController.scanAndConnectUsb()
        } else {
            refreshGallery()
        }
    }

    fun dismissGallery() {
        galleryListJob?.cancel()
        galleryListJob = null
        galleryController.setGalleryActive(false)
        galleryFlow.update {
            it.copy(
                isOpen = false,
                selectedPhotoKey = null
            )
        }
    }

    fun switchGalleryTransport(mode: PtpTransportMode) {
        if (galleryFlow.value.activeTransport == mode && galleryFlow.value.photos.isNotEmpty()) {
            return
        }
        galleryListJob?.cancel()
        galleryFlow.update {
            it.copy(
                activeTransport = mode,
                photos = emptyList(),
                errorMessage = null,
                selectedPhotoKey = null,
                isLoadingList = false
            )
        }
        val isReady = when (mode) {
            PtpTransportMode.WIFI_PTP_IP ->
                liveViewController.connectionState.value is LiveViewConnectionState.Connected
            PtpTransportMode.USB_MTP ->
                galleryFlow.value.usbState is UsbConnectionState.Connected
        }
        if (mode == PtpTransportMode.USB_MTP && !isReady) {
            galleryController.scanAndConnectUsb()
        } else if (isReady) {
            refreshGallery()
        } else {
            galleryFlow.update {
                it.copy(errorMessage = "Wi-Fi 实时预览未连接，请先连接相机 Wi-Fi 或切换至 USB 有线")
            }
        }
    }

    fun scanAndConnectUsb() {
        galleryController.scanAndConnectUsb()
    }

    fun disconnectUsb() {
        galleryController.disconnectUsb()
    }

    fun refreshGallery() {
        val mode = galleryFlow.value.activeTransport
        val isReady = when (mode) {
            PtpTransportMode.WIFI_PTP_IP ->
                liveViewController.connectionState.value is LiveViewConnectionState.Connected
            PtpTransportMode.USB_MTP ->
                galleryFlow.value.usbState is UsbConnectionState.Connected
        }
        if (!isReady) {
            galleryFlow.update {
                it.copy(
                    isLoadingList = false,
                    errorMessage = if (mode == PtpTransportMode.USB_MTP) {
                        "未检测到 USB 相机，请插入 Type-C 数据线并将相机 USB 模式设为「MTP」或「电脑遥控」"
                    } else {
                        "Wi-Fi 实时预览未连接，请先连接相机 Wi-Fi"
                    }
                )
            }
            return
        }

        galleryListJob?.cancel()
        galleryFlow.update { it.copy(isLoadingList = true, errorMessage = null, noticeMessage = null) }
        galleryListJob = scope.launch {
            val result = galleryController.listPhotos(mode)
            result.fold(
                onSuccess = { listResult ->
                    galleryFlow.update { state ->
                        if (state.activeTransport != mode) return@update state
                        state.copy(
                            photos = listResult.photos,
                            isLoadingList = false,
                            errorMessage = null,
                            noticeMessage = listResult.notice
                        )
                    }
                },
                onFailure = { error ->
                    galleryFlow.update { state ->
                        if (state.activeTransport != mode) return@update state
                        state.copy(
                            isLoadingList = false,
                            errorMessage = error.message ?: "读取相机图库失败"
                        )
                    }
                }
            )
        }
    }

    fun requestThumbnail(item: CameraPhotoItem) {
        val key = item.stableKey
        val snap = galleryFlow.value
        if (!item.isSdram && (key in snap.thumbnails || key in snap.loadingThumbKeys || key in snap.failedThumbKeys)) {
            return
        }
        if (item.isSdram && key in snap.loadingThumbKeys) {
            return
        }

        galleryFlow.update { it.copy(loadingThumbKeys = it.loadingThumbKeys + key) }
        scope.launch {
            thumbnailSemaphore.withPermit {
                if (!galleryFlow.value.isOpen || galleryFlow.value.activeTransport != item.transport) {
                    galleryFlow.update { it.copy(loadingThumbKeys = it.loadingThumbKeys - key) }
                    return@withPermit
                }
                val result = galleryController.loadThumbnail(item)
                result.fold(
                    onSuccess = { loaded ->
                        galleryFlow.update { state ->
                            if (loaded.shouldRemove) {
                                return@update state.copy(
                                    photos = state.photos.filterNot { it.stableKey == key },
                                    loadingThumbKeys = state.loadingThumbKeys - key,
                                    selectedPhotoKey = if (state.selectedPhotoKey == key) null else state.selectedPhotoKey
                                )
                            }
                            val updatedList = CameraPhotoAggregator.mergePairedRawAndJpg(
                                state.photos.map { existing ->
                                    if (existing.stableKey == key) loaded.updatedItem else existing
                                }
                            )
                            val updatedThumbs = if (loaded.thumbnail != null) {
                                state.thumbnails + (key to loaded.thumbnail)
                            } else {
                                state.thumbnails
                            }
                            val updatedFailed = if (loaded.thumbnail == null) {
                                state.failedThumbKeys + key
                            } else {
                                state.failedThumbKeys - key
                            }
                            state.copy(
                                photos = updatedList,
                                thumbnails = updatedThumbs,
                                loadingThumbKeys = state.loadingThumbKeys - key,
                                failedThumbKeys = updatedFailed
                            )
                        }
                    },
                    onFailure = {
                        galleryFlow.update { state ->
                            state.copy(
                                loadingThumbKeys = state.loadingThumbKeys - key,
                                failedThumbKeys = state.failedThumbKeys + key
                            )
                        }
                    }
                )
            }
        }
    }

    fun selectGalleryPhoto(item: CameraPhotoItem?) {
        galleryFlow.update {
            it.copy(
                selectedPhotoKey = item?.stableKey,
                loadingLargePreviewKey = if (item == null) null else it.loadingLargePreviewKey
            )
        }
        if (item != null) {
            if (!item.metadataLoaded || item.stableKey !in galleryFlow.value.thumbnails) {
                requestThumbnail(item)
            }
            requestLargePreview(item)
        }
    }

    private fun requestLargePreview(item: CameraPhotoItem) {
        val key = item.stableKey
        val snap = galleryFlow.value
        if (!item.isSdram && key in snap.largePreviews) return
        if (snap.loadingLargePreviewKey == key) return

        galleryFlow.update { it.copy(loadingLargePreviewKey = key) }
        scope.launch {
            thumbnailSemaphore.withPermit {
                val cur = galleryFlow.value
                if (!cur.isOpen || cur.selectedPhotoKey != key) {
                    galleryFlow.update {
                        if (it.loadingLargePreviewKey == key) it.copy(loadingLargePreviewKey = null) else it
                    }
                    return@withPermit
                }
                val result = galleryController.loadLargePreview(item)
                result.fold(
                    onSuccess = { bitmap ->
                        galleryFlow.update { state ->
                            state.copy(
                                largePreviews = state.largePreviews + (key to bitmap),
                                thumbnails = if (key !in state.thumbnails) {
                                    state.thumbnails + (key to bitmap)
                                } else {
                                    state.thumbnails
                                },
                                loadingLargePreviewKey = if (state.loadingLargePreviewKey == key) {
                                    null
                                } else {
                                    state.loadingLargePreviewKey
                                }
                            )
                        }
                    },
                    onFailure = {
                        galleryFlow.update { state ->
                            state.copy(
                                loadingLargePreviewKey = if (state.loadingLargePreviewKey == key) {
                                    null
                                } else {
                                    state.loadingLargePreviewKey
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    fun downloadGalleryPhoto(item: CameraPhotoItem, mode: PhotoDownloadMode) {
        if (galleryFlow.value.downloadProgress != null) return
        val key = item.stableKey
        galleryDownloadJob?.cancel()
        val initialTotal = when (mode) {
            PhotoDownloadMode.JPG_ONLY -> if (item.isRaw) 0L else item.sizeBytes
            PhotoDownloadMode.RAW_ONLY -> item.pairedRawSizeBytes.takeIf { it > 0L } ?: item.sizeBytes
            PhotoDownloadMode.RAW_AND_JPG -> item.sizeBytes + item.pairedRawSizeBytes
        }
        galleryFlow.update {
            it.copy(
                downloadProgress = GalleryDownloadProgress(
                    photoKey = key,
                    bytesRead = 0L,
                    totalBytes = initialTotal
                )
            )
        }
        galleryDownloadJob = scope.launch {
            val result = galleryController.downloadPhoto(item, mode) { read, total ->
                galleryFlow.update { state ->
                    state.copy(
                        downloadProgress = GalleryDownloadProgress(
                            photoKey = key,
                            bytesRead = read,
                            totalBytes = if (total > 0L) total else initialTotal
                        )
                    )
                }
            }
            result.fold(
                onSuccess = { saved ->
                    galleryFlow.update { state ->
                        val updatedLarge = if (saved.previewBitmap != null) {
                            state.largePreviews + (key to saved.previewBitmap)
                        } else {
                            state.largePreviews
                        }
                        val updatedThumbs = if (saved.previewBitmap != null && key !in state.thumbnails) {
                            state.thumbnails + (key to saved.previewBitmap)
                        } else {
                            state.thumbnails
                        }
                        state.copy(
                            downloadProgress = null,
                            largePreviews = updatedLarge,
                            thumbnails = updatedThumbs,
                            downloadedPhotos = state.downloadedPhotos + (key to saved)
                        )
                    }
                    showTemporaryHint("${saved.summaryLabel}：${saved.filename}", durationMs = 3_400L)
                },
                onFailure = { err ->
                    galleryFlow.update { it.copy(downloadProgress = null) }
                    showTemporaryHint(
                        "下载失败：${err.message ?: "请检查连接状态"}",
                        durationMs = 4_000L
                    )
                }
            )
        }
    }

    private fun handlePtpEvent(event: PtpEvent) {
        when (event) {
            is PtpEvent.ObjectAdded -> {
                val state = galleryFlow.value
                if (state.isOpen && state.activeTransport == event.transport) {
                    val newItem = CameraPhotoItem(
                        handle = event.handle,
                        transport = event.transport,
                        isSdram = event.isSdram,
                        metadataLoaded = false
                    )
                    galleryFlow.update { cur ->
                        val deduped = cur.photos.filterNot { it.stableKey == newItem.stableKey }
                        cur.copy(
                            photos = listOf(newItem) + deduped,
                            failedThumbKeys = cur.failedThumbKeys - newItem.stableKey
                        )
                    }
                    requestThumbnail(newItem)
                } else {
                    galleryFlow.update { it.copy(newPhotoBadgeCount = it.newPhotoBadgeCount + 1) }
                    showTemporaryHint("新拍照片已就绪，点击「图库」即可查看", durationMs = 2_400L)
                }
            }
            is PtpEvent.ObjectRemoved -> {
                galleryFlow.update { cur ->
                    val remaining = cur.photos.filterNot {
                        it.transport == event.transport && it.handle == event.handle
                    }
                    cur.copy(photos = remaining)
                }
            }
            is PtpEvent.StorageChanged -> {
                val state = galleryFlow.value
                if (state.isOpen && state.activeTransport == event.transport) {
                    refreshGallery()
                }
            }
            is PtpEvent.TransportDisconnected -> {
                val state = galleryFlow.value
                if (state.isOpen && state.activeTransport == event.transport) {
                    galleryFlow.update {
                        it.copy(
                            isLoadingList = false,
                            errorMessage = event.reason ?: "${event.transport.label} 连接已断开"
                        )
                    }
                }
            }
        }
    }

    fun onCleared() {
        countdownJob?.cancel()
        focusJob?.cancel()
        hintJob?.cancel()
        galleryListJob?.cancel()
        galleryDownloadJob?.cancel()
        bleController.release()
        liveViewController.release()
        galleryController.release()
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
