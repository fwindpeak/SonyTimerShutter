package com.sonytimer.shutter.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

@Composable
actual fun EnforceDarkSystemBars() {
    val view = LocalView.current
    if (view.isInEditMode) return

    SideEffect {
        view.findWindow()?.let(::applyDarkSystemBars)
    }

    DisposableEffect(view) {
        val window = view.findWindow()
        if (window != null) {
            applyDarkSystemBars(window)
            view.post { applyDarkSystemBars(window) }
        }
        onDispose {}
    }
}

@Composable
actual fun KeepScreenOnEffect(enabled: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose {
            if (enabled) {
                view.keepScreenOn = false
            }
        }
    }
}

private fun applyDarkSystemBars(window: Window) {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun View.findWindow(): Window? {
    var current: ViewParent? = parent
    while (current != null) {
        if (current is DialogWindowProvider) {
            return current.window
        }
        current = current.parent
    }
    return context.findActivity()?.window
}
