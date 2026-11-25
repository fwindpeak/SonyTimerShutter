package com.sonytimer.shutter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonytimer.shutter.controller.BleConnectionState
import com.sonytimer.shutter.controller.DiscoveredCamera

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraPickerSheet(
    connectionState: BleConnectionState,
    cameras: List<DiscoveredCamera>,
    isScanning: Boolean,
    onRefresh: () -> Unit,
    onSelectCamera: (DiscoveredCamera) -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1D21),
        contentColor = Color(0xFFE5E7EB)
    ) {
        EnforceDarkSystemBars()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "选择索尼相机",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF3F4F6)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (connectionState is BleConnectionState.Connected) {
                        Text(
                            text = "断开",
                            fontSize = 13.sp,
                            color = Color(0xFFF87171),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onDisconnect)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = if (isScanning) "扫描中..." else "重新扫描",
                        fontSize = 13.sp,
                        color = Color(0xFF93C5FD),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onRefresh)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "索尼相机请先开启：网络 → 蓝牙功能(开) + 传输/远程 → 蓝牙遥控(开)；首次配对请进入相机「蓝牙 → 配对」。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Color(0xFF9CA3AF)
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (cameras.isEmpty()) {
                Text(
                    text = if (isScanning) "正在搜索附近的索尼 BLE 相机..." else "暂未发现相机，请确认相机已开机且开启蓝牙。",
                    fontSize = 13.sp,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(cameras, key = { it.address }) { cam ->
                        val isCurrent = connectionState is BleConnectionState.Connected &&
                            connectionState.address == cam.address
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isCurrent) Color(0xFF1F2D27) else Color(0xFF252930))
                                .border(
                                    width = 1.dp,
                                    color = if (isCurrent) Color(0xFF34D399) else Color.White.copy(alpha = 0.08f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onSelectCamera(cam) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = cam.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFF9FAFB)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = buildString {
                                        append(cam.address)
                                        if (cam.isBonded) append(" · 已配对")
                                        if (cam.isPairingMode) append(" · 可配对")
                                    },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF9CA3AF)
                                )
                            }
                            Text(
                                text = if (isCurrent) "已连接" else "连接",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isCurrent) Color(0xFF34D399) else Color(0xFFE5E7EB)
                            )
                        }
                    }
                }
            }
        }
    }
}
