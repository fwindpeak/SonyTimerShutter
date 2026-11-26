package com.sonytimer.shutter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonytimer.shutter.controller.LiveViewConnectionState
import com.sonytimer.shutter.controller.ShutterPhase

@Composable
fun LiveViewStatusPill(
    liveViewState: LiveViewConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (dotColor, iconColor, borderColor, label) = when (liveViewState) {
        is LiveViewConnectionState.Connected -> Quad(
            Color(0xFF34D399),
            Color(0xFF6EE7B7),
            Color(0xFF34D399).copy(alpha = 0.28f),
            "预览中"
        )
        is LiveViewConnectionState.Searching -> Quad(
            Color(0xFFF59E0B),
            Color(0xFFFCD34D),
            Color(0xFFF59E0B).copy(alpha = 0.28f),
            "搜索中..."
        )
        is LiveViewConnectionState.WaitingForConfirm -> Quad(
            Color(0xFFF59E0B),
            Color(0xFFFCD34D),
            Color(0xFFF59E0B).copy(alpha = 0.45f),
            "待相机确认"
        )
        is LiveViewConnectionState.Connecting -> Quad(
            Color(0xFFF59E0B),
            Color(0xFFFCD34D),
            Color(0xFFF59E0B).copy(alpha = 0.28f),
            "连接中..."
        )
        is LiveViewConnectionState.Error -> Quad(
            Color(0xFFEF4444),
            Color(0xFFFCA5A5),
            Color(0xFFEF4444).copy(alpha = 0.28f),
            "预览异常"
        )
        is LiveViewConnectionState.Disconnected -> Quad(
            Color(0xFF6B7280),
            Color(0xFF9CA3AF),
            Color.White.copy(alpha = 0.08f),
            "实时预览"
        )
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF1C1F24).copy(alpha = 0.85f))
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(modifier = Modifier.size(8.dp)) {
            drawCircle(color = dotColor)
        }

        // 矢量取景器图标
        Canvas(modifier = Modifier.size(16.dp)) {
            val w = size.width
            val h = size.height
            drawRoundRect(
                color = iconColor,
                topLeft = Offset(w * 0.1f, h * 0.22f),
                size = Size(w * 0.8f, h * 0.58f),
                cornerRadius = CornerRadius(2.5.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawCircle(
                color = iconColor,
                radius = w * 0.16f,
                center = Offset(w * 0.5f, h * 0.51f),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        Text(
            text = label,
            color = Color(0xFFE5E7EB),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LiveViewPreviewCard(
    frame: ImageBitmap?,
    fps: Int,
    isMirrored: Boolean,
    phase: ShutterPhase,
    onToggleMirror: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF090B0D))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
        contentAlignment = Alignment.Center
    ) {
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = "相机实时取景画面",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = if (isMirrored) -1f else 1f
                    }
            )
        } else {
            Text(
                text = "等待相机实时画面...",
                color = Color(0xFF9CA3AF),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // 左上角 LIVE 帧率角标
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.58f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Canvas(modifier = Modifier.size(6.dp)) {
                drawCircle(color = Color(0xFF34D399))
            }
            Text(
                text = if (fps > 0) "LIVE · ${fps}fps" else "LIVE",
                color = Color(0xFFE5E7EB),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }

        // 右下角自拍水平镜像开关
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    if (isMirrored) Color(0xFFB91C1C).copy(alpha = 0.88f)
                    else Color.Black.copy(alpha = 0.62f)
                )
                .border(
                    width = 1.dp,
                    color = if (isMirrored) Color(0xFFF87171).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(999.dp)
                )
                .clickable(onClick = onToggleMirror)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 对称三角水平镜像矢量图标
            Canvas(modifier = Modifier.size(14.dp)) {
                val w = size.width
                val h = size.height
                val iconColor = Color.White
                drawLine(
                    color = iconColor,
                    start = Offset(w * 0.5f, h * 0.12f),
                    end = Offset(w * 0.5f, h * 0.88f),
                    strokeWidth = 1.4.dp.toPx(),
                    cap = StrokeCap.Round
                )
                val leftTri = Path().apply {
                    moveTo(w * 0.36f, h * 0.25f)
                    lineTo(w * 0.10f, h * 0.5f)
                    lineTo(w * 0.36f, h * 0.75f)
                    close()
                }
                val rightTri = Path().apply {
                    moveTo(w * 0.64f, h * 0.25f)
                    lineTo(w * 0.90f, h * 0.5f)
                    lineTo(w * 0.64f, h * 0.75f)
                    close()
                }
                drawPath(path = leftTri, color = iconColor)
                drawPath(
                    path = rightTri,
                    color = iconColor,
                    style = Stroke(width = 1.3.dp.toPx(), join = StrokeJoin.Round)
                )
            }
            Text(
                text = "镜像",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }

        // 远距离自拍超大倒计时叠层
        AnimatedVisibility(
            visible = phase is ShutterPhase.CountingDown,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val counting = phase as? ShutterPhase.CountingDown
            if (counting != null) {
                Box(
                    modifier = Modifier
                        .size(144.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.56f))
                        .border(
                            width = 1.5.dp,
                            color = (if (counting.isInterval) Color(0xFF38BDF8) else Color(0xFFFF5A36))
                                .copy(alpha = 0.7f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = counting.remainingSeconds.toString(),
                            color = Color.White,
                            fontSize = 72.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        if (counting.totalShots > 1) {
                            Text(
                                text = "${counting.currentShot}/${counting.totalShots}",
                                color = Color(0xFFE5E7EB),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // 快门释放瞬间柔和白闪提示
        AnimatedVisibility(
            visible = phase is ShutterPhase.Triggering,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.22f))
            )
        }
    }
}

private data class Quad<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
