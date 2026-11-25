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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonytimer.shutter.controller.ShutterTimerViewModel

private val PRESET_SECONDS = listOf(2, 3, 5, 10, 15, 30)
private val PRESET_BURST_COUNTS = listOf(2, 3, 5, 10, 20)
private val PRESET_INTERVAL_SECONDS = listOf(1, 2, 3, 5, 10, 30)

/**
 * 点击底部拍摄参数胶囊呼出的倒计时与连拍参数调节面板
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerSettingsSheet(
    currentSeconds: Int,
    burstEnabled: Boolean,
    burstCount: Int,
    burstIntervalSeconds: Int,
    remoteTriggerEnabled: Boolean,
    onSelectSeconds: (Int) -> Unit,
    onToggleBurst: (Boolean) -> Unit,
    onSelectBurstCount: (Int) -> Unit,
    onSelectBurstInterval: (Int) -> Unit,
    onToggleRemoteTrigger: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "拍摄参数设定",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFF3F4F6)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "参数修改后自动保存",
                fontSize = 12.sp,
                color = Color(0xFF9CA3AF)
            )

            Spacer(modifier = Modifier.height(22.dp))

            NumericSettingSection(
                title = "倒计时延时",
                rangeHint = "${ShutterTimerViewModel.MIN_TIMER_SECONDS}~${ShutterTimerViewModel.MAX_TIMER_SECONDS}秒",
                valueText = "${currentSeconds}s",
                currentValue = currentSeconds,
                minValue = ShutterTimerViewModel.MIN_TIMER_SECONDS,
                maxValue = ShutterTimerViewModel.MAX_TIMER_SECONDS,
                presets = PRESET_SECONDS,
                presetLabel = { "${it}s" },
                onValueChange = onSelectSeconds
            )

            Spacer(modifier = Modifier.height(22.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(18.dp))

            // 连拍模式开关卡片
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF22262C))
                    .border(
                        width = 1.dp,
                        color = if (burstEnabled) Color(0xFFF87171).copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onToggleBurst(!burstEnabled) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "连拍模式",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF3F4F6)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (burstEnabled) {
                            "共 $burstCount 张 · 每张间隔 ${burstIntervalSeconds}s"
                        } else {
                            "开启后可设置连拍张数与间隔时间"
                        },
                        fontSize = 12.sp,
                        color = Color(0xFF9CA3AF)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = burstEnabled,
                    onCheckedChange = onToggleBurst,
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
                visible = burstEnabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(20.dp))

                    NumericSettingSection(
                        title = "连拍张数",
                        rangeHint = "${ShutterTimerViewModel.MIN_BURST_COUNT}~${ShutterTimerViewModel.MAX_BURST_COUNT}张",
                        valueText = "${burstCount}张",
                        currentValue = burstCount,
                        minValue = ShutterTimerViewModel.MIN_BURST_COUNT,
                        maxValue = ShutterTimerViewModel.MAX_BURST_COUNT,
                        presets = PRESET_BURST_COUNTS,
                        presetLabel = { "${it}张" },
                        onValueChange = onSelectBurstCount
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    NumericSettingSection(
                        title = "每张间隔",
                        rangeHint = "${ShutterTimerViewModel.MIN_BURST_INTERVAL_SECONDS}~${ShutterTimerViewModel.MAX_BURST_INTERVAL_SECONDS}秒",
                        valueText = "${burstIntervalSeconds}s",
                        currentValue = burstIntervalSeconds,
                        minValue = ShutterTimerViewModel.MIN_BURST_INTERVAL_SECONDS,
                        maxValue = ShutterTimerViewModel.MAX_BURST_INTERVAL_SECONDS,
                        presets = PRESET_INTERVAL_SECONDS,
                        presetLabel = { "${it}s" },
                        onValueChange = onSelectBurstInterval
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(18.dp))

            // 手环 / 蓝牙快门遥控开关卡片
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF22262C))
                    .border(
                        width = 1.dp,
                        color = if (remoteTriggerEnabled) Color(0xFF34D399).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onToggleRemoteTrigger(!remoteTriggerEnabled) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "手环 / 蓝牙快门遥控",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF3F4F6)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (remoteTriggerEnabled) {
                                "小米手环 9 Pro、蓝牙快门或音量键均可触发倒计时"
                            } else {
                                "开启后支持小米手环、蓝牙自拍杆或音量键触发倒计时"
                            },
                            fontSize = 12.sp,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = remoteTriggerEnabled,
                        onCheckedChange = onToggleRemoteTrigger,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF059669),
                            checkedBorderColor = Color(0xFF34D399),
                            uncheckedThumbColor = Color(0xFFD1D5DB),
                            uncheckedTrackColor = Color(0xFF374151),
                            uncheckedBorderColor = Color.White.copy(alpha = 0.14f)
                        )
                    )
                }

                AnimatedVisibility(
                    visible = remoteTriggerEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF181B20))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "• 方式 1（遥控拍照）：手环通过小米运动健康开启蓝牙拍照后点击拍照",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFFD1D5DB)
                        )
                        Text(
                            text = "• 方式 2（音乐控制，鸿蒙推荐）：手环打开音乐控制，点播放/切歌/音量直接触发",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFFA7F3D0)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NumericSettingSection(
    title: String,
    rangeHint: String,
    valueText: String,
    currentValue: Int,
    minValue: Int,
    maxValue: Int,
    presets: List<Int>,
    presetLabel: (Int) -> String,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFE5E7EB)
            )
            Text(
                text = rangeHint,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF9CA3AF)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            StepButton(
                label = "−",
                enabled = currentValue > minValue
            ) {
                onValueChange((currentValue - 1).coerceAtLeast(minValue))
            }
            Spacer(modifier = Modifier.width(24.dp))
            Text(
                text = valueText,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFFF6B57)
            )
            Spacer(modifier = Modifier.width(24.dp))
            StepButton(
                label = "+",
                enabled = currentValue < maxValue
            ) {
                onValueChange((currentValue + 1).coerceAtMost(maxValue))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                val selected = preset == currentValue
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Color(0xFFB91C1C) else Color(0xFF262A30))
                        .border(
                            width = 1.dp,
                            color = if (selected) Color(0xFFF87171) else Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onValueChange(preset) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = presetLabel(preset),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (selected) Color.White else Color(0xFFD1D5DB)
                    )
                }
            }
        }
    }
}

@Composable
private fun StepButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Color(0xFF262A30) else Color(0xFF1F2227))
            .border(
                1.dp,
                Color.White.copy(alpha = if (enabled) 0.1f else 0.04f),
                RoundedCornerShape(12.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE5E7EB).copy(alpha = if (enabled) 1f else 0.3f)
        )
    }
}
