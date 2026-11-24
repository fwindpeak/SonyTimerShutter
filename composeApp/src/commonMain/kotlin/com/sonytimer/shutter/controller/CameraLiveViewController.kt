package com.sonytimer.shutter.controller

import androidx.compose.ui.graphics.ImageBitmap
import com.sonytimer.shutter.protocol.SonyPtpIpProtocol
import kotlinx.coroutines.flow.StateFlow

data class WifiCameraConfig(
    val host: String = SonyPtpIpProtocol.DEFAULT_CAMERA_IP,
    val useSsh: Boolean = true,
    val sshUsername: String = "",
    val sshPassword: String = ""
)

sealed interface LiveViewConnectionState {
    data object Disconnected : LiveViewConnectionState
    data class Connecting(val stage: String) : LiveViewConnectionState
    data class Connected(
        val cameraName: String,
        val host: String,
        val viaSsh: Boolean
    ) : LiveViewConnectionState
    data class Error(val message: String) : LiveViewConnectionState
}

interface CameraLiveViewController {
    val connectionState: StateFlow<LiveViewConnectionState>
    val latestFrame: StateFlow<ImageBitmap?>
    val fps: StateFlow<Int>

    fun detectWifiGatewayIp(): String?
    fun connect(config: WifiCameraConfig)
    fun disconnect()
    suspend fun triggerShutter(): Result<Unit>
    fun release()
}
