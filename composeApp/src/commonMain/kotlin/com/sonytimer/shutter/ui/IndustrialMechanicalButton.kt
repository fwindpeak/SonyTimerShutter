package com.sonytimer.shutter.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonytimer.shutter.controller.BleConnectionState
import com.sonytimer.shutter.controller.ShutterPhase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 工业感复古机械大按钮：
 * - 拉丝铸铁/阳极氧化铝底座面板 + 四角沉头一字/内六角工业螺丝
 * - 车削滚花钢制防护护圈 + 嵌入式环形状态指示灯
 * - 复古暗红电木/烤漆内凹大键帽 + 真实按压行程、阴影压缩与高光衰减
 * - 纯粹快门按键：短按开始倒计时 / 倒计时或连拍过程中短按取消
 */
@Composable
fun IndustrialMechanicalButton(
    connectionState: BleConnectionState,
    phase: ShutterPhase,
    configuredSeconds: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isWifiConnected: Boolean = false,
    buttonSize: Dp = 308.dp
) {
    var isFingerDown by remember { mutableStateOf(false) }
    val isReadyConnected = connectionState is BleConnectionState.Connected || isWifiConnected
    val scaleRatio = (buttonSize / 308.dp).coerceIn(0.52f, 1.0f)

    val isPressedVisual = isFingerDown || phase is ShutterPhase.Triggering
    val pressProgress by animateFloatAsState(
        targetValue = if (isPressedVisual) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "mechanicalPress"
    )

    val countdownSweep by animateFloatAsState(
        targetValue = when (phase) {
            is ShutterPhase.CountingDown -> phase.remainingSeconds.toFloat() / phase.totalSeconds.coerceAtLeast(1)
            is ShutterPhase.Triggering -> 0f
            is ShutterPhase.ShotSuccess -> 1f
            is ShutterPhase.Idle -> if (isReadyConnected) 1f else 0f
        },
        animationSpec = tween(durationMillis = 260),
        label = "ledSweep"
    )

    val ledColor = when {
        phase is ShutterPhase.ShotSuccess -> Color(0xFF4ADE80)
        phase is ShutterPhase.Triggering -> Color(0xFFFFB020)
        phase is ShutterPhase.CountingDown && phase.isInterval -> Color(0xFF38BDF8)
        phase is ShutterPhase.CountingDown -> Color(0xFFFF5A36)
        isReadyConnected -> Color(0xFF34D399)
        connectionState is BleConnectionState.Connecting || connectionState is BleConnectionState.Pairing -> Color(0xFFF59E0B)
        else -> Color(0xFF4B5563)
    }

    Box(
        modifier = modifier
            .size(buttonSize)
            .pointerInput(phase, connectionState, isWifiConnected) {
                detectTapGestures(
                    onPress = {
                        isFingerDown = true
                        tryAwaitRelease()
                        isFingerDown = false
                    },
                    onTap = { onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(buttonSize)) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2f, h / 2f)
            val u = (minOf(w, h) / 308f)

            // 1. 工业铸铝方型面板外阴影
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.65f),
                topLeft = Offset(10f * u, 16f * u),
                size = Size(w - 20f * u, h - 20f * u),
                cornerRadius = CornerRadius(34f * u)
            )

            // 2. 拉丝枪灰金属面板主体
            val plateInset = 10f * u
            val plateSize = Size(w - plateInset * 2, h - plateInset * 2)
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF383C42),
                        Color(0xFF25282D),
                        Color(0xFF1D2024),
                        Color(0xFF2B2F36)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                ),
                topLeft = Offset(plateInset, plateInset),
                size = plateSize,
                cornerRadius = CornerRadius(32f * u)
            )

            // 面板倒角高光描边
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.22f),
                        Color.White.copy(alpha = 0.04f),
                        Color.Black.copy(alpha = 0.55f)
                    ),
                    start = Offset(plateInset, plateInset),
                    end = Offset(w - plateInset, h - plateInset)
                ),
                topLeft = Offset(plateInset, plateInset),
                size = plateSize,
                cornerRadius = CornerRadius(32f * u),
                style = Stroke(width = 1.6f * u)
            )

            // 四角复古沉头工业螺丝
            val screwOffset = 34f * u
            val screwPositions = listOf(
                Offset(screwOffset, screwOffset),
                Offset(w - screwOffset, screwOffset),
                Offset(screwOffset, h - screwOffset),
                Offset(w - screwOffset, h - screwOffset)
            )
            screwPositions.forEachIndexed { idx, pos ->
                drawIndustrialScrew(
                    center = pos,
                    radius = 9f * u,
                    slotAngleDeg = 35f + idx * 27f,
                    unit = u
                )
            }

            // 3. 车削滚花金属外护圈
            val bezelRadius = 112f * u
            drawCircle(
                color = Color.Black.copy(alpha = 0.72f),
                radius = bezelRadius + 4f * u,
                center = center + Offset(0f, 5f * u)
            )
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF6B7078),
                        Color(0xFF2F3339),
                        Color(0xFF1B1D21),
                        Color(0xFF525760)
                    ),
                    start = Offset(center.x - bezelRadius, center.y - bezelRadius),
                    end = Offset(center.x + bezelRadius, center.y + bezelRadius)
                ),
                radius = bezelRadius,
                center = center
            )

            // 滚花防滑纹理刻线（60 齿）
            val knurlInner = bezelRadius - 7f * u
            val knurlOuter = bezelRadius - 1.5f * u
            for (i in 0 until 60) {
                val rad = (i * 6.0) * PI / 180.0
                val cosA = cos(rad).toFloat()
                val sinA = sin(rad).toFloat()
                drawLine(
                    color = if (i % 2 == 0) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.14f),
                    start = Offset(center.x + knurlInner * cosA, center.y + knurlInner * sinA),
                    end = Offset(center.x + knurlOuter * cosA, center.y + knurlOuter * sinA),
                    strokeWidth = 1.4f * u
                )
            }

            // 4. 按钮沉孔井道（黑色深槽）
            val wellRadius = 96f * u
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF0A0B0D), Color(0xFF181A1E)),
                    center = center,
                    radius = wellRadius
                ),
                radius = wellRadius,
                center = center
            )

            // 5. 嵌入式细环形 LED 指示灯（贴在护圈内沿）
            val ledRadius = 100f * u
            drawCircle(
                color = Color(0xFF101215),
                radius = ledRadius,
                center = center,
                style = Stroke(width = 3f * u)
            )
            if (countdownSweep > 0f) {
                drawArc(
                    color = ledColor.copy(alpha = 0.28f),
                    startAngle = -90f,
                    sweepAngle = 360f * countdownSweep,
                    useCenter = false,
                    topLeft = Offset(center.x - ledRadius, center.y - ledRadius),
                    size = Size(ledRadius * 2, ledRadius * 2),
                    style = Stroke(width = 6.5f * u, cap = StrokeCap.Round)
                )
                drawArc(
                    color = ledColor,
                    startAngle = -90f,
                    sweepAngle = 360f * countdownSweep,
                    useCenter = false,
                    topLeft = Offset(center.x - ledRadius, center.y - ledRadius),
                    size = Size(ledRadius * 2, ledRadius * 2),
                    style = Stroke(width = 2.6f * u, cap = StrokeCap.Round)
                )
            }

            // 6. 复古机械大键帽（随按压下沉、阴影收缩、微缩放）
            val travelY = pressProgress * 6.5f * u
            val capScale = 1f - pressProgress * 0.025f
            val capRadius = 86f * u * capScale
            val capCenter = Offset(center.x, center.y - 3f * u + travelY)

            // 键帽底部立体裙边与投影
            val skirtHeight = (1f - pressProgress) * 9f * u + 2f * u
            drawCircle(
                color = Color.Black.copy(alpha = 0.65f - pressProgress * 0.22f),
                radius = capRadius + 2f * u,
                center = Offset(capCenter.x, capCenter.y + skirtHeight + 3f * u)
            )
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF6A1111), Color(0xFF3E0909)),
                    startY = capCenter.y - capRadius,
                    endY = capCenter.y + capRadius + skirtHeight
                ),
                radius = capRadius,
                center = Offset(capCenter.x, capCenter.y + skirtHeight * 0.65f)
            )

            // 键帽顶面（复古工业暗红烤漆质感）
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFD6302B),
                        Color(0xFFB31F1B),
                        Color(0xFF7D1210)
                    ),
                    center = Offset(capCenter.x - capRadius * 0.25f, capCenter.y - capRadius * 0.28f),
                    radius = capRadius * 1.35f
                ),
                radius = capRadius,
                center = capCenter
            )

            // 键帽内凹指腹槽（模拟真实物理机械按键的微凹手感）
            val dishRadius = capRadius * 0.78f
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF6B0E0C).copy(alpha = 0.75f),
                        Color(0xFFC92A25).copy(alpha = 0.35f),
                        Color(0xFFFF8A80).copy(alpha = 0.20f)
                    ),
                    start = Offset(capCenter.x - dishRadius, capCenter.y - dishRadius),
                    end = Offset(capCenter.x + dishRadius, capCenter.y + dishRadius)
                ),
                radius = dishRadius,
                center = capCenter
            )

            // 顶部边缘高光弧（按下时衰减）
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.32f * (1f - pressProgress * 0.45f)),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.35f)
                    ),
                    start = Offset(capCenter.x - capRadius, capCenter.y - capRadius),
                    end = Offset(capCenter.x + capRadius, capCenter.y + capRadius)
                ),
                radius = capRadius - 1f * u,
                center = capCenter,
                style = Stroke(width = 2f * u)
            )
        }

        // 键帽中心刻字/倒计时数字（跟着按压行程同步位移）
        val textOffsetY = (-3f + pressProgress * 6.5f) * scaleRatio
        val textScale = 1f - pressProgress * 0.025f
        val labelText = when (phase) {
            is ShutterPhase.CountingDown -> phase.remainingSeconds.toString()
            is ShutterPhase.Triggering -> "●"
            is ShutterPhase.ShotSuccess -> "✓"
            is ShutterPhase.Idle -> "${configuredSeconds}S"
        }
        val labelSize = when (phase) {
            is ShutterPhase.CountingDown -> (if (phase.totalShots > 1) 46f else 54f) * scaleRatio
            is ShutterPhase.Triggering, is ShutterPhase.ShotSuccess -> 40f * scaleRatio
            is ShutterPhase.Idle -> 26f * scaleRatio
        }.sp
        val labelColor = when (phase) {
            is ShutterPhase.CountingDown -> Color(0xFFFFF5EB)
            is ShutterPhase.ShotSuccess -> Color(0xFFD1FAE5)
            else -> Color(0xFFFDE8E8).copy(alpha = 0.72f)
        }
        val subLabelText = when (phase) {
            is ShutterPhase.Idle -> null
            is ShutterPhase.CountingDown -> if (phase.totalShots > 1) "${phase.currentShot}/${phase.totalShots}" else null
            is ShutterPhase.Triggering -> if (phase.totalShots > 1) "${phase.currentShot}/${phase.totalShots}" else null
            is ShutterPhase.ShotSuccess -> if (phase.totalShots > 1) "${phase.totalShots}/${phase.totalShots}" else null
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                translationY = textOffsetY * density
                scaleX = textScale
                scaleY = textScale
            }
        ) {
            Text(
                text = labelText,
                color = labelColor,
                fontSize = labelSize,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = if (phase is ShutterPhase.Idle) (2f * scaleRatio).sp else 0.sp
            )
            if (subLabelText != null) {
                Spacer(modifier = Modifier.height((4f * scaleRatio).dp))
                Text(
                    text = subLabelText,
                    color = labelColor.copy(alpha = 0.9f),
                    fontSize = (13f * scaleRatio).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

private fun DrawScope.drawIndustrialScrew(
    center: Offset,
    radius: Float,
    slotAngleDeg: Float,
    unit: Float
) {
    // 螺丝沉孔阴影
    drawCircle(
        color = Color.Black.copy(alpha = 0.75f),
        radius = radius + 1.5f * unit,
        center = center + Offset(0f, 1f * unit)
    )
    // 螺丝金属头
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF646970), Color(0xFF282B30)),
            start = center - Offset(radius, radius),
            end = center + Offset(radius, radius)
        ),
        radius = radius,
        center = center
    )
    // 一字槽口
    val rad = slotAngleDeg * PI / 180.0
    val dx = (radius * 0.68f * cos(rad)).toFloat()
    val dy = (radius * 0.68f * sin(rad)).toFloat()
    drawLine(
        color = Color(0xFF121316),
        start = Offset(center.x - dx, center.y - dy),
        end = Offset(center.x + dx, center.y + dy),
        strokeWidth = 2f * unit,
        cap = StrokeCap.Round
    )
}
