package com.sonytimer.shutter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonytimer.shutter.controller.BleConnectionState

/**
 * 极简克制的蓝牙状态图标 + 文字提醒（点击可连接/切换索尼相机）
 */
@Composable
fun BluetoothStatusBar(
    connectionState: BleConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (dotColor, iconColor, label) = when (connectionState) {
        is BleConnectionState.Connected -> Triple(
            Color(0xFF34D399),
            Color(0xFF6EE7B7),
            "已连接 · ${connectionState.cameraName}"
        )
        is BleConnectionState.Connecting -> Triple(
            Color(0xFFF59E0B),
            Color(0xFFFCD34D),
            "连接中 · ${connectionState.cameraName}"
        )
        is BleConnectionState.Pairing -> Triple(
            Color(0xFFF59E0B),
            Color(0xFFFCD34D),
            "请在相机屏幕确认配对..."
        )
        is BleConnectionState.Error -> Triple(
            Color(0xFFEF4444),
            Color(0xFFFCA5A5),
            connectionState.message
        )
        is BleConnectionState.Disconnected -> Triple(
            Color(0xFF6B7280),
            Color(0xFF9CA3AF),
            "未连接索尼相机 · 点击配对"
        )
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF1C1F24).copy(alpha = 0.85f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 状态灯圆点
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = dotColor)
        }

        // 矢量蓝牙图标（不依赖额外图标库，跨平台零依赖）
        Canvas(modifier = Modifier.size(16.dp)) {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f
            val path = Path().apply {
                moveTo(cx - w * 0.24f, h * 0.28f)
                lineTo(cx + w * 0.26f, h * 0.72f)
                lineTo(cx, h * 0.92f)
                lineTo(cx, h * 0.08f)
                lineTo(cx + w * 0.26f, h * 0.28f)
                lineTo(cx - w * 0.24f, h * 0.72f)
            }
            drawPath(
                path = path,
                color = iconColor,
                style = Stroke(
                    width = 1.6.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }

        Text(
            text = label,
            color = Color(0xFFE5E7EB),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}
