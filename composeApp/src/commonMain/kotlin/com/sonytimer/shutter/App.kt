package com.sonytimer.shutter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonytimer.shutter.controller.ShutterTimerViewModel
import com.sonytimer.shutter.ui.BluetoothStatusBar
import com.sonytimer.shutter.ui.CameraPickerSheet
import com.sonytimer.shutter.ui.IndustrialMechanicalButton
import com.sonytimer.shutter.ui.TimerSettingsSheet

@Composable
fun SonyTimerShutterApp(
    viewModel: ShutterTimerViewModel
) {
    val uiState by viewModel.uiState.collectAsState()

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
            // 顶部：克制的蓝牙状态图标 + 文字提醒
            BluetoothStatusBar(
                connectionState = uiState.connectionState,
                onClick = viewModel::openCameraPicker,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
            )

            // 屏幕正中：唯一明显的视觉主体 —— 工业感复古机械大按钮
            IndustrialMechanicalButton(
                connectionState = uiState.connectionState,
                phase = uiState.phase,
                configuredSeconds = uiState.configuredSeconds,
                onClick = viewModel::onMainButtonClick,
                onLongPress = viewModel::onMainButtonLongPress,
                modifier = Modifier.align(Alignment.Center)
            )

            // 底部临时反馈（仅在触发/取消/出错时短暂淡入，平时完全隐藏）
            AnimatedVisibility(
                visible = uiState.statusHint != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
            ) {
                Text(
                    text = uiState.statusHint.orEmpty(),
                    color = Color(0xFFD1D5DB),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // 隐藏式秒数修改弹窗（长按主按钮呼出）
            if (uiState.showTimerSheet) {
                TimerSettingsSheet(
                    currentSeconds = uiState.configuredSeconds,
                    onSelectSeconds = viewModel::updateTimerSeconds,
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
        }
    }
}
