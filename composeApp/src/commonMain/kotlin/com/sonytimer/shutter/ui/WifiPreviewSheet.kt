package com.sonytimer.shutter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonytimer.shutter.controller.BleConnectionState
import com.sonytimer.shutter.controller.LiveViewConnectionState
import com.sonytimer.shutter.controller.WifiCameraConfig
import com.sonytimer.shutter.protocol.SonyPtpIpProtocol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiPreviewSheet(
    liveViewState: LiveViewConnectionState,
    bleConnectionState: BleConnectionState,
    initialConfig: WifiCameraConfig,
    detectedGatewayIp: String?,
    onAutoConnect: () -> Unit,
    onConnectManual: (WifiCameraConfig) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var host by remember(initialConfig.host) { mutableStateOf(initialConfig.host) }
    var showManualIp by remember { mutableStateOf(false) }

    val isConnected = liveViewState is LiveViewConnectionState.Connected
    val isBusy = liveViewState is LiveViewConnectionState.Searching ||
        liveViewState is LiveViewConnectionState.WaitingForConfirm ||
        liveViewState is LiveViewConnectionState.Connecting

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFFF87171),
        unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
        focusedTextColor = Color(0xFFF9FAFB),
        unfocusedTextColor = Color(0xFFE5E7EB),
        focusedLabelColor = Color(0xFFFCA5A5),
        unfocusedLabelColor = Color(0xFF9CA3AF),
        cursorColor = Color(0xFFF87171),
        focusedContainerColor = Color(0xFF22262C),
        unfocusedContainerColor = Color(0xFF22262C)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1D21),
        contentColor = Color(0xFFE5E7EB)
    ) {
        EnforceDarkSystemBars()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 32.dp)
        ) {
            // 顶部标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "画面监控（自动连接）",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF3F4F6)
                )
                if (isConnected || isBusy) {
                    Text(
                        text = if (isConnected) "断开预览" else "取消",
                        fontSize = 13.sp,
                        color = Color(0xFFF87171),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDisconnect)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // 若蓝牙已连相机，展示友好提示胶囊
            if (bleConnectionState is BleConnectionState.Connected) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF14171A))
                        .border(1.dp, Color(0xFF34D399).copy(alpha = 0.3f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34D399))
                    )
                    Text(
                        text = "蓝牙已连 ${bleConnectionState.cameraName} · 正在局域网自动匹配",
                        fontSize = 11.sp,
                        color = Color(0xFFA7F3D0)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 主状态卡片（傻瓜式清晰引导）
            when (liveViewState) {
                is LiveViewConnectionState.WaitingForConfirm -> {
                    ConfirmOnCameraCard(
                        host = liveViewState.host,
                        remainingSeconds = liveViewState.remainingSeconds
                    )
                }

                is LiveViewConnectionState.Searching -> {
                    SearchingCameraCard(
                        hint = liveViewState.hint,
                        elapsedSeconds = liveViewState.elapsedSeconds
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SimpleThreeStepGuideCard()
                }

                is LiveViewConnectionState.Connecting -> {
                    ConnectingStageCard(stage = liveViewState.stage)
                }

                is LiveViewConnectionState.Connected -> {
                    StatusNoticeBox(
                        title = "已连接 ${liveViewState.cameraName}",
                        body = "相机地址 ${liveViewState.host} · 实时画面监控中",
                        titleColor = Color(0xFF6EE7B7),
                        bodyColor = Color(0xFFD1FAE5),
                        borderColor = Color(0xFF34D399).copy(alpha = 0.45f)
                    )
                }

                is LiveViewConnectionState.Error -> {
                    StatusNoticeBox(
                        title = "未能自动连上相机",
                        body = liveViewState.message,
                        titleColor = Color(0xFFFCA5A5),
                        bodyColor = Color(0xFFE5E7EB),
                        borderColor = Color(0xFFEF4444).copy(alpha = 0.45f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SimpleThreeStepGuideCard()
                }

                is LiveViewConnectionState.Disconnected -> {
                    SimpleThreeStepGuideCard()
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 主操作按钮
            if (!isBusy) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isConnected) Color(0xFF262B32) else Color(0xFFB91C1C))
                        .border(
                            width = 1.dp,
                            color = if (isConnected) Color.White.copy(alpha = 0.14f) else Color(0xFFF87171),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable {
                            if (isConnected) onDisconnect() else onAutoConnect()
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            isConnected -> "断开画面监控"
                            liveViewState is LiveViewConnectionState.Error -> "一键重新搜索并连接"
                            else -> "一键自动搜索并连接"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }

            // 折叠的高级手动 IP 入口（默认隐藏，避免干扰普通用户）
            if (!isConnected) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showManualIp = !showManualIp }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showManualIp) "收起手动输入 IP ▴" else "自动搜不到？手动输入 IP ▾",
                        fontSize = 12.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }

                AnimatedVisibility(visible = showManualIp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("相机 IP 地址") },
                            placeholder = { Text(SonyPtpIpProtocol.DEFAULT_CAMERA_IP) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = textFieldColors,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuickIpChip(
                                label = "${SonyPtpIpProtocol.DEFAULT_CAMERA_IP} (直连)",
                                selected = host.trim() == SonyPtpIpProtocol.DEFAULT_CAMERA_IP,
                                onClick = { host = SonyPtpIpProtocol.DEFAULT_CAMERA_IP }
                            )
                            if (!detectedGatewayIp.isNullOrBlank() &&
                                detectedGatewayIp != SonyPtpIpProtocol.DEFAULT_CAMERA_IP
                            ) {
                                QuickIpChip(
                                    label = "网关 $detectedGatewayIp",
                                    selected = host.trim() == detectedGatewayIp,
                                    onClick = { host = detectedGatewayIp }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF262B32))
                                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                                .clickable {
                                    onConnectManual(
                                        WifiCameraConfig(
                                            host = host.trim(),
                                            useSsh = false
                                        )
                                    )
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "连接此 IP",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFF3F4F6)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchingCameraCard(
    hint: String,
    elapsedSeconds: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF14171A))
            .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.5.dp,
            color = Color(0xFFFBBF24),
            trackColor = Color(0xFF374151)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "正在自动寻找相机...",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFDE68A)
                )
                Text(
                    text = "${elapsedSeconds}s",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF9CA3AF)
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = hint,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Color(0xFFD1D5DB)
            )
        }
    }
}

@Composable
private fun ConfirmOnCameraCard(
    host: String,
    remainingSeconds: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF261E11))
            .border(1.5.dp, Color(0xFFF59E0B).copy(alpha = 0.75f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.2.dp,
                    color = Color(0xFFFBBF24),
                    trackColor = Color(0xFF451A03)
                )
                Text(
                    text = "请在相机屏幕上点「确认」",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFEF3C7)
                )
            }
            Text(
                text = "${remainingSeconds}s",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFFCD34D)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "已自动发现相机 ($host)。不用着急，慢慢在相机屏幕上点击确认即可（最长等待 60 秒）。配对成功后，下次将自动直连。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Color(0xFFFDE68A).copy(alpha = 0.9f)
        )
    }
}

@Composable
private fun ConnectingStageCard(stage: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF14171A))
            .border(1.dp, Color(0xFF34D399).copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.5.dp,
            color = Color(0xFF34D399),
            trackColor = Color(0xFF374151)
        )
        Text(
            text = stage,
            fontSize = 13.sp,
            color = Color(0xFFD1FAE5)
        )
    }
}

@Composable
private fun SimpleThreeStepGuideCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF14171A))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "只需 3 步（不用找 IP，不限先后顺序）：",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE5E7EB)
        )
        Text(
            text = "1. 相机与手机连同一个 Wi-Fi（或连手机热点 / 相机热点）",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Color(0xFF9CA3AF)
        )
        Text(
            text = "2. 相机打开电脑遥控：MENU → 网络 → 连接 → 遥控拍摄 → 电脑遥控功能",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Color(0xFF9CA3AF)
        )
        Text(
            text = "3. 进入相机的配对界面（首次配对按相机提示点「确认」即可）",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Color(0xFF9CA3AF)
        )
    }
}

@Composable
private fun QuickIpChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = label,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = if (selected) Color.White else Color(0xFFD1D5DB),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Color(0xFFB91C1C).copy(alpha = 0.7f) else Color(0xFF252930))
            .border(
                1.dp,
                if (selected) Color(0xFFF87171) else Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun StatusNoticeBox(
    title: String,
    body: String,
    titleColor: Color,
    bodyColor: Color,
    borderColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF14171A))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = titleColor
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = body,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = bodyColor
        )
    }
}
