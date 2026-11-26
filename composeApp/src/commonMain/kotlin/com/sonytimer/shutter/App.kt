package com.sonytimer.shutter

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonytimer.shutter.controller.PtpTransportMode
import com.sonytimer.shutter.controller.ShutterPhase
import com.sonytimer.shutter.controller.ShutterTimerViewModel
import com.sonytimer.shutter.controller.UsbConnectionState
import com.sonytimer.shutter.ui.BluetoothStatusBar
import com.sonytimer.shutter.ui.CameraGalleryOverlay
import com.sonytimer.shutter.ui.CameraPickerSheet
import com.sonytimer.shutter.ui.GalleryStatusPill
import com.sonytimer.shutter.ui.IndustrialMechanicalButton
import com.sonytimer.shutter.ui.KeepScreenOnEffect
import com.sonytimer.shutter.ui.LiveViewPreviewCard
import com.sonytimer.shutter.ui.LiveViewStatusPill
import com.sonytimer.shutter.ui.TimerConfigBar
import com.sonytimer.shutter.ui.TimerSettingsSheet
import com.sonytimer.shutter.ui.WifiPreviewSheet

@Composable
fun SonyTimerShutterApp(
    viewModel: ShutterTimerViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

    KeepScreenOnEffect(
        enabled = uiState.isWifiConnected ||
            uiState.gallery.isOpen ||
            uiState.phase is ShutterPhase.CountingDown ||
            uiState.phase is ShutterPhase.Triggering
    )

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF121417),
            surface = Color(0xFF1A1D21)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF1F2329), Color(0xFF101215))
                    )
                )
                .safeDrawingPadding()
        ) {
            if (uiState.isWifiConnected) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TopStatusRow(
                        uiState = uiState,
                        onBleClick = viewModel::openCameraPicker,
                        onWifiClick = viewModel::openWifiSheet,
                        onGalleryClick = { viewModel.openGallery() },
                        modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
                    )

                    LiveViewPreviewCard(
                        frame = uiState.liveViewFrame,
                        fps = uiState.liveViewFps,
                        isMirrored = uiState.isPreviewMirrored,
                        phase = uiState.phase,
                        onToggleMirror = viewModel::togglePreviewMirror,
                        onOpenGallery = { viewModel.openGallery(PtpTransportMode.WIFI_PTP_IP) },
                        newPhotoBadgeCount = uiState.gallery.newPhotoBadgeCount,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )

                    IndustrialMechanicalButton(
                        connectionState = uiState.connectionState,
                        isWifiConnected = true,
                        phase = uiState.phase,
                        configuredSeconds = uiState.configuredSeconds,
                        onClick = viewModel::onMainButtonClick,
                        buttonSize = 184.dp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    BottomControls(
                        uiState = uiState,
                        onOpenTimerSheet = viewModel::openTimerSheet,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )
                }
            } else {
                TopStatusRow(
                    uiState = uiState,
                    onBleClick = viewModel::openCameraPicker,
                    onWifiClick = viewModel::openWifiSheet,
                    onGalleryClick = { viewModel.openGallery(PtpTransportMode.USB_MTP) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp, start = 16.dp, end = 16.dp)
                )

                // 屏幕正中：工业感复古机械大按钮（纯快门按键）
                IndustrialMechanicalButton(
                    connectionState = uiState.connectionState,
                    isWifiConnected = false,
                    phase = uiState.phase,
                    configuredSeconds = uiState.configuredSeconds,
                    onClick = viewModel::onMainButtonClick,
                    modifier = Modifier.align(Alignment.Center)
                )

                BottomControls(
                    uiState = uiState,
                    onOpenTimerSheet = viewModel::openTimerSheet,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp)
                )
            }

            // 拍摄参数设定弹窗（点击底部参数胶囊呼出）
            if (uiState.showTimerSheet) {
                TimerSettingsSheet(
                    currentSeconds = uiState.configuredSeconds,
                    burstEnabled = uiState.burstEnabled,
                    burstCount = uiState.burstCount,
                    burstIntervalSeconds = uiState.burstIntervalSeconds,
                    remoteTriggerEnabled = uiState.remoteTriggerEnabled,
                    onSelectSeconds = viewModel::updateTimerSeconds,
                    onToggleBurst = viewModel::updateBurstEnabled,
                    onSelectBurstCount = viewModel::updateBurstCount,
                    onSelectBurstInterval = viewModel::updateBurstIntervalSeconds,
                    onToggleRemoteTrigger = viewModel::updateRemoteTriggerEnabled,
                    onDismiss = viewModel::dismissTimerSheet
                )
            }

            // 索尼相机蓝牙扫描与连接弹窗
            if (uiState.showCameraPicker) {
                CameraPickerSheet(
                    connectionState = uiState.connectionState,
                    cameras = uiState.discoveredCameras,
                    isScanning = uiState.isScanning,
                    onRefresh = viewModel::refreshScan,
                    onSelectCamera = viewModel::onSelectCamera,
                    onDisconnect = viewModel::onDisconnectCamera,
                    onDismiss = viewModel::dismissCameraPicker
                )
            }

            // 索尼相机 Wi-Fi 实时预览配置弹窗
            if (uiState.showWifiSheet) {
                WifiPreviewSheet(
                    liveViewState = uiState.liveViewState,
                    bleConnectionState = uiState.connectionState,
                    initialConfig = uiState.wifiConfig,
                    detectedGatewayIp = uiState.detectedGatewayIp,
                    onAutoConnect = viewModel::autoConnectWifi,
                    onConnectManual = viewModel::connectWifi,
                    onDisconnect = viewModel::disconnectWifi,
                    onOpenWifiGallery = { viewModel.openGallery(PtpTransportMode.WIFI_PTP_IP) },
                    onOpenUsbGallery = { viewModel.openGallery(PtpTransportMode.USB_MTP) },
                    onDismiss = viewModel::dismissWifiSheet
                )
            }

            // 相机图库全屏浮层（支持 Wi-Fi PTP/IP 与 USB MTP 有线缩略图预览及原图下载）
            if (uiState.gallery.isOpen) {
                CameraGalleryOverlay(
                    galleryState = uiState.gallery,
                    isWifiConnected = uiState.isWifiConnected,
                    onSwitchTransport = viewModel::switchGalleryTransport,
                    onScanUsb = viewModel::scanAndConnectUsb,
                    onRefresh = viewModel::refreshGallery,
                    onRequestThumbnail = viewModel::requestThumbnail,
                    onSelectPhoto = viewModel::selectGalleryPhoto,
                    onDownloadOriginal = viewModel::downloadOriginalPhoto,
                    onDismiss = viewModel::dismissGallery
                )
            }
        }
    }
}

@Composable
private fun TopStatusRow(
    uiState: com.sonytimer.shutter.controller.ShutterUiState,
    onBleClick: () -> Unit,
    onWifiClick: () -> Unit,
    onGalleryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showUsbPill = uiState.isUsbConnected ||
        uiState.gallery.usbState is UsbConnectionState.PermissionRequired
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BluetoothStatusBar(
            connectionState = uiState.connectionState,
            onClick = onBleClick,
            compact = true,
            modifier = Modifier.weight(1f, fill = false)
        )
        LiveViewStatusPill(
            liveViewState = uiState.liveViewState,
            onClick = onWifiClick
        )
        if (showUsbPill) {
            GalleryStatusPill(
                isUsbConnected = uiState.isUsbConnected,
                newPhotoBadgeCount = uiState.gallery.newPhotoBadgeCount,
                onClick = onGalleryClick
            )
        }
    }
}

@Composable
private fun BottomControls(
    uiState: com.sonytimer.shutter.controller.ShutterUiState,
    onOpenTimerSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = 22.dp)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = uiState.statusHint != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Text(
                    text = uiState.statusHint.orEmpty(),
                    color = Color(0xFFD1D5DB),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        TimerConfigBar(
            configuredSeconds = uiState.configuredSeconds,
            burstEnabled = uiState.burstEnabled,
            burstCount = uiState.burstCount,
            burstIntervalSeconds = uiState.burstIntervalSeconds,
            enabled = uiState.phase !is ShutterPhase.CountingDown &&
                uiState.phase !is ShutterPhase.Triggering,
            onClick = onOpenTimerSheet
        )
    }
}
