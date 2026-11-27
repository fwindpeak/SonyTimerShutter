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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 底部极简拍摄参数胶囊（与顶部蓝牙状态条对称，点击呼出拍摄参数设定面板）
 */
@Composable
fun TimerConfigBar(
    configuredSeconds: Int,
    burstEnabled: Boolean,
    burstCount: Int,
    burstIntervalSeconds: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = if (burstEnabled) {
        "连拍 · ${configuredSeconds}s · ${burstCount}张/${burstIntervalSeconds}s"
    } else {
        "单拍 · ${configuredSeconds}s"
    }
    val iconColor = (if (burstEnabled) Color(0xFFFCA5A5) else Color(0xFF9CA3AF))
        .copy(alpha = if (enabled) 1f else 0.45f)
    val borderColor = if (burstEnabled && enabled) {
        Color(0xFFF87171).copy(alpha = 0.28f)
    } else {
        Color.White.copy(alpha = 0.08f)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF1C1F24).copy(alpha = if (enabled) 0.85f else 0.55f))
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 矢量双轨滑块图标（零外部图标库依赖，与顶部蓝牙图标风格统一）
        Canvas(modifier = Modifier.size(16.dp)) {
            val w = size.width
            val h = size.height
            val strokeWidth = 1.5.dp.toPx()
            val knobRadius = 2.1.dp.toPx()
            val topY = h * 0.30f
            val bottomY = h * 0.70f

            // 上轨 + 左侧滑块
            val topKnobX = w * 0.34f
            drawLine(
                color = iconColor,
                start = Offset(w * 0.08f, topY),
                end = Offset(topKnobX - knobRadius, topY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = iconColor,
                start = Offset(topKnobX + knobRadius, topY),
                end = Offset(w * 0.92f, topY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = iconColor,
                radius = knobRadius,
                center = Offset(topKnobX, topY)
            )

            // 下轨 + 右侧滑块
            val bottomKnobX = w * 0.66f
            drawLine(
                color = iconColor,
                start = Offset(w * 0.08f, bottomY),
                end = Offset(bottomKnobX - knobRadius, bottomY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = iconColor,
                start = Offset(bottomKnobX + knobRadius, bottomY),
                end = Offset(w * 0.92f, bottomY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = iconColor,
                radius = knobRadius,
                center = Offset(bottomKnobX, bottomY)
            )
        }

        Text(
            text = label,
            color = Color(0xFFE5E7EB).copy(alpha = if (enabled) 1f else 0.45f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )

        Text(
            text = "›",
            color = Color(0xFF9CA3AF).copy(alpha = if (enabled) 0.85f else 0.35f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 底部独立对焦小按钮：仅触发半按对焦（不释放快门），用于拍照前预对焦
 */
@Composable
fun FocusActionPill(
    isFocusing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = Color(0xFF34D399)
    val iconColor = (if (isFocusing) activeColor else Color(0xFFD1D5DB))
        .copy(alpha = if (enabled || isFocusing) 1f else 0.45f)
    val borderColor = if (isFocusing) {
        activeColor.copy(alpha = 0.55f)
    } else {
        Color.White.copy(alpha = 0.10f)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (isFocusing) Color(0xFF132620).copy(alpha = 0.9f)
                else Color(0xFF1C1F24).copy(alpha = if (enabled) 0.85f else 0.55f)
            )
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled && !isFocusing, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        // 矢量相机四角对焦框 + 中心合焦点图标
        Canvas(modifier = Modifier.size(15.dp)) {
            val w = size.width
            val h = size.height
            val sw = 1.5.dp.toPx()
            val leg = w * 0.28f
            val pad = w * 0.08f

            // 左上角
            drawLine(iconColor, Offset(pad, pad), Offset(pad + leg, pad), sw, StrokeCap.Round)
            drawLine(iconColor, Offset(pad, pad), Offset(pad, pad + leg), sw, StrokeCap.Round)
            // 右上角
            drawLine(iconColor, Offset(w - pad, pad), Offset(w - pad - leg, pad), sw, StrokeCap.Round)
            drawLine(iconColor, Offset(w - pad, pad), Offset(w - pad, pad + leg), sw, StrokeCap.Round)
            // 左下角
            drawLine(iconColor, Offset(pad, h - pad), Offset(pad + leg, h - pad), sw, StrokeCap.Round)
            drawLine(iconColor, Offset(pad, h - pad), Offset(pad, h - pad - leg), sw, StrokeCap.Round)
            // 右下角
            drawLine(iconColor, Offset(w - pad, h - pad), Offset(w - pad - leg, h - pad), sw, StrokeCap.Round)
            drawLine(iconColor, Offset(w - pad, h - pad), Offset(w - pad, h - pad - leg), sw, StrokeCap.Round)
            // 中心对焦点
            drawCircle(
                color = iconColor,
                radius = 1.8.dp.toPx(),
                center = Offset(w * 0.5f, h * 0.5f)
            )
        }

        Text(
            text = if (isFocusing) "对焦中" else "对焦",
            color = (if (isFocusing) Color(0xFF6EE7B7) else Color(0xFFE5E7EB))
                .copy(alpha = if (enabled || isFocusing) 1f else 0.45f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}
