package com.sonytimer.shutter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.sonytimer.shutter.android.AndroidFeedbackPlayer
import com.sonytimer.shutter.android.AndroidRemoteShutterTrigger
import com.sonytimer.shutter.android.AndroidSonyBleController
import com.sonytimer.shutter.android.AndroidSonyLiveViewController
import com.sonytimer.shutter.android.AndroidTimerPreferences
import com.sonytimer.shutter.controller.ShutterPhase
import com.sonytimer.shutter.controller.ShutterTimerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var viewModel: ShutterTimerViewModel
    private lateinit var remoteShutterTrigger: AndroidRemoteShutterTrigger

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.refreshScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        val bleController = AndroidSonyBleController(
            context = this,
            hasBlePermissions = ::hasRequiredBlePermissions,
            onPermissionsMissing = ::requestBlePermissions
        )
        val liveViewController = AndroidSonyLiveViewController(context = this)
        val feedbackPlayer = AndroidFeedbackPlayer(this)
        val preferences = AndroidTimerPreferences(this)

        viewModel = ShutterTimerViewModel(
            scope = appScope,
            bleController = bleController,
            liveViewController = liveViewController,
            feedbackPlayer = feedbackPlayer,
            preferences = preferences
        )

        remoteShutterTrigger = AndroidRemoteShutterTrigger(
            context = this,
            onTrigger = { source -> viewModel.onRemoteShutterTrigger(source) }
        )

        appScope.launch {
            viewModel.uiState.collect { state ->
                val hasOpenSheet = state.showTimerSheet || state.showCameraPicker || state.showWifiSheet
                remoteShutterTrigger.setEnabled(state.remoteTriggerEnabled && !hasOpenSheet)

                val (title, subtitle) = when (val phase = state.phase) {
                    is ShutterPhase.CountingDown ->
                        "倒计时 ${phase.remainingSeconds}s" to "点击播放/音量取消"
                    is ShutterPhase.Triggering ->
                        "正在触发快门..." to "索尼定时快门"
                    is ShutterPhase.ShotSuccess ->
                        "拍摄完成" to "索尼定时快门"
                    ShutterPhase.Idle -> {
                        if (state.isAnyCameraConnected) {
                            "索尼定时快门 · ${state.configuredSeconds}s" to "点播放/切歌/音量开始倒计时"
                        } else {
                            "索尼定时快门" to "请先在手机连接索尼相机"
                        }
                    }
                }
                remoteShutterTrigger.updateSessionDisplay(title = title, subtitle = subtitle)
            }
        }

        if (!hasRequiredBlePermissions()) {
            requestBlePermissions()
        }

        setContent {
            SonyTimerShutterApp(viewModel = viewModel)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::remoteShutterTrigger.isInitialized) {
            remoteShutterTrigger.setForeground(true)
        }
    }

    override fun onPause() {
        if (::remoteShutterTrigger.isInitialized) {
            remoteShutterTrigger.setForeground(false)
        }
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::remoteShutterTrigger.isInitialized && remoteShutterTrigger.handleKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (::remoteShutterTrigger.isInitialized) {
            remoteShutterTrigger.release()
        }
        viewModel.onCleared()
        appScope.cancel()
        super.onDestroy()
    }

    private fun requiredBlePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasRequiredBlePermissions(): Boolean {
        return requiredBlePermissions().all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBlePermissions() {
        permissionLauncher.launch(requiredBlePermissions())
    }
}
