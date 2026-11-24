package com.sonytimer.shutter.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonytimer.shutter.controller.LiveViewConnectionState
import com.sonytimer.shutter.controller.WifiCameraConfig
import com.sonytimer.shutter.protocol.SonyPtpIpProtocol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiPreviewSheet(
    liveViewState: LiveViewConnectionState,
    initialConfig: WifiCameraConfig,
    detectedGatewayIp: String?,
    onConnect: (WifiCameraConfig) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var host by remember(initialConfig.host) { mutableStateOf(initialConfig.host) }
    var useSsh by remember(initialConfig.useSsh) { mutableStateOf(initialConfig.useSsh) }
    var sshUsername by remember(initialConfig.sshUsername) { mutableStateOf(initialConfig.sshUsername) }
    var sshPassword by remember(initialConfig.sshPassword) { mutableStateOf(initialConfig.sshPassword) }
    var showPassword by remember { mutableStateOf(false) }

    val isConnected = liveViewState is LiveViewConnectionState.Connected
    val isConnecting = liveViewState is LiveViewConnectionState.Connecting

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Wi-Fi 实时预览",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF3F4F6)
                )
                if (isConnected || isConnecting) {
                    Text(
                        text = if (isConnecting) "取消" else "断开预览",
                        fontSize = 13.sp,
                        color = Color(0xFFF87171),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDisconnect)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "A7C II 设置：MENU → 网络 → 连接/PC 遥控 → PC 遥控功能 → PC 遥控(开)。" +
                    "可通过「Wi-Fi Direct 信息」直连相机热点，或与手机连入同一 Wi-Fi / 手机热点。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Color(0xFF9CA3AF)
            )

            Spacer(modifier = Modifier.height(16.dp))

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

            Spacer(modifier = Modifier.height(16.dp))

            // SSH 访问认证开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF22262C))
                    .border(
                        width = 1.dp,
                        color = if (useSsh) Color(0xFFF87171).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { useSsh = !useSsh }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SSH 访问认证",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF3F4F6)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (useSsh) {
                            "相机出厂默认开启，凭据见「访问认证信息」"
                        } else {
                            "免密直连 15740（需在相机「访问认证设置」设为关）"
                        },
                        fontSize = 12.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = useSsh,
                    onCheckedChange = { useSsh = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFB91C1C),
                        checkedBorderColor = Color(0xFFF87171),
                        uncheckedThumbColor = Color(0xFFD1D5DB),
                        uncheckedTrackColor = Color(0xFF374151),
                        uncheckedBorderColor = Color.White.copy(alpha = 0.14f)
                    )
                )
            }

            AnimatedVisibility(
                visible = useSsh,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = sshUsername,
                        onValueChange = { sshUsername = it },
                        label = { Text("SSH 用户名 (访问认证信息)") },
                        singleLine = true,
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = sshPassword,
                        onValueChange = { sshPassword = it },
                        label = { Text("SSH 密码") },
                        singleLine = true,
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            Text(
                                text = if (showPassword) "隐藏" else "显示",
                                fontSize = 12.sp,
                                color = Color(0xFF93C5FD),
                                modifier = Modifier
                                    .clickable { showPassword = !showPassword }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        },
                        colors = textFieldColors,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            when (liveViewState) {
                is LiveViewConnectionState.Connecting -> {
                    Spacer(modifier = Modifier.height(14.dp))
                    StatusNoticeBox(
                        text = liveViewState.stage,
                        textColor = Color(0xFFFCD34D),
                        borderColor = Color(0xFFF59E0B).copy(alpha = 0.35f)
                    )
                }
                is LiveViewConnectionState.Error -> {
                    Spacer(modifier = Modifier.height(14.dp))
                    StatusNoticeBox(
                        text = liveViewState.message,
                        textColor = Color(0xFFFCA5A5),
                        borderColor = Color(0xFFEF4444).copy(alpha = 0.45f)
                    )
                }
                is LiveViewConnectionState.Connected -> {
                    Spacer(modifier = Modifier.height(14.dp))
                    StatusNoticeBox(
                        text = "已连接 ${liveViewState.cameraName} (${liveViewState.host})",
                        textColor = Color(0xFF6EE7B7),
                        borderColor = Color(0xFF34D399).copy(alpha = 0.4f)
                    )
                }
                is LiveViewConnectionState.Disconnected -> Unit
            }

            Spacer(modifier = Modifier.height(18.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isConnecting) Color(0xFF374151) else Color(0xFFB91C1C))
                    .border(
                        width = 1.dp,
                        color = if (isConnecting) Color.White.copy(alpha = 0.1f) else Color(0xFFF87171),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable(enabled = !isConnecting) {
                        onConnect(
                            WifiCameraConfig(
                                host = host.trim(),
                                useSsh = useSsh,
                                sshUsername = sshUsername.trim(),
                                sshPassword = sshPassword
                            )
                        )
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        isConnecting -> "正在连接相机..."
                        isConnected -> "重新连接"
                        else -> "连接并开启预览"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
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
    text: String,
    textColor: Color,
    borderColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF14171A))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = textColor
        )
    }
}
