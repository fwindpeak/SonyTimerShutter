package com.sonytimer.shutter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.sonytimer.shutter.android.AndroidFeedbackPlayer
import com.sonytimer.shutter.android.AndroidSonyBleController
import com.sonytimer.shutter.android.AndroidTimerPreferences
import com.sonytimer.shutter.controller.ShutterTimerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var viewModel: ShutterTimerViewModel

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
        val feedbackPlayer = AndroidFeedbackPlayer(this)
        val preferences = AndroidTimerPreferences(this)

        viewModel = ShutterTimerViewModel(
            scope = appScope,
            bleController = bleController,
            feedbackPlayer = feedbackPlayer,
            preferences = preferences
        )

        if (!hasRequiredBlePermissions()) {
            requestBlePermissions()
        }

        setContent {
            SonyTimerShutterApp(viewModel = viewModel)
        }
    }

    override fun onDestroy() {
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
