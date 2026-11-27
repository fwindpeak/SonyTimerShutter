package com.sonytimer.shutter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sonytimer.shutter.controller.CameraPhotoItem
import com.sonytimer.shutter.controller.GalleryDownloadProgress
import com.sonytimer.shutter.controller.GalleryUiState
import com.sonytimer.shutter.controller.PtpTransportMode
import com.sonytimer.shutter.controller.SavedPhotoResult
import com.sonytimer.shutter.controller.UsbConnectionState
import com.sonytimer.shutter.protocol.SonyPtpIpProtocol

@Composable
fun CameraGalleryOverlay(
    galleryState: GalleryUiState,
    isWifiConnected: Boolean,
    onSwitchTransport: (PtpTransportMode) -> Unit,
    onScanUsb: () -> Unit,
    onRefresh: () -> Unit,
    onRequestThumbnail: (CameraPhotoItem) -> Unit,
    onSelectPhoto: (CameraPhotoItem?) -> Unit,
    onDownloadOriginal: (CameraPhotoItem) -> Unit,
    onExtractRawJpeg: (CameraPhotoItem) -> Unit = {},
    onDismiss: () -> Unit
) {
    val selectedIndex = galleryState.selectedPhotoKey?.let { key ->
        galleryState.photos.indexOfFirst { it.stableKey == key }
    } ?: -1
    val selectedPhoto = galleryState.photos.getOrNull(selectedIndex)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1013))
            .clickable(enabled = true, onClick = {}) // 拦截点击透传
            .safeDrawingPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部标题与操作栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "相机图库",
                        color = Color(0xFFF3F4F6),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (galleryState.photos.isNotEmpty()) {
                        Text(
                            text = "${galleryState.photos.size} 张",
                            color = Color(0xFF9CA3AF),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0xFF1C1F24))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionPillButton(
                        label = "刷新",
                        onClick = onRefresh
                    )
                    ActionPillButton(
                        label = "关闭",
                        accent = true,
                        onClick = onDismiss
                    )
                }
            }

            // 传输通道切换条（Wi-Fi / USB 有线）
            TransportSwitcherBar(
                activeMode = galleryState.activeTransport,
                isWifiConnected = isWifiConnected,
                usbState = galleryState.usbState,
                onSelectMode = onSwitchTransport,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // 协议能力提示条（例如 Wi-Fi 电脑遥控模式未开放存储卡目录时，提示可切 USB MTP）
            val notice = galleryState.noticeMessage
            if (!notice.isNullOrBlank() && !galleryState.isLoadingList) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF231D14))
                        .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = notice,
                        color = Color(0xFFFDE68A),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    if (galleryState.activeTransport == PtpTransportMode.WIFI_PTP_IP) {
                        Text(
                            text = "切至 USB 有线图库 →",
                            color = Color(0xFF93C5FD),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onSwitchTransport(PtpTransportMode.USB_MTP) }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }

            // 主内容区
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp)
            ) {
                when {
                    galleryState.isLoadingList -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.6.dp,
                                color = Color(0xFFF87171),
                                trackColor = Color(0xFF262A30)
                            )
                            Text(
                                text = "正在读取相机照片列表...",
                                color = Color(0xFF9CA3AF),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    galleryState.errorMessage != null && galleryState.photos.isEmpty() -> {
                        GalleryEmptyOrErrorView(
                            title = if (galleryState.activeTransport == PtpTransportMode.USB_MTP) {
                                "等待 USB 相机连接"
                            } else {
                                "暂无法读取 Wi-Fi 图库"
                            },
                            message = galleryState.errorMessage,
                            primaryActionLabel = if (galleryState.activeTransport == PtpTransportMode.USB_MTP) {
                                "重新检测 USB 相机"
                            } else {
                                "重试加载"
                            },
                            onPrimaryAction = {
                                if (galleryState.activeTransport == PtpTransportMode.USB_MTP) {
                                    onScanUsb()
                                } else {
                                    onRefresh()
                                }
                            },
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    galleryState.photos.isEmpty() -> {
                        GalleryEmptyOrErrorView(
                            title = "相机中暂无照片",
                            message = if (galleryState.activeTransport == PtpTransportMode.WIFI_PTP_IP) {
                                "若处于电脑遥控模式，请确认相机存储卡内有 JPG 照片，或在「电脑遥控设置 → 静止影像保存目的地」中开启「目的地+相机」，新拍照片会自动出现。"
                            } else {
                                "未在存储卡中发现照片。请确认相机 USB 连接模式为「MTP」或「电脑遥控」，并插入存储卡。"
                            },
                            primaryActionLabel = "刷新列表",
                            onPrimaryAction = onRefresh,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = galleryState.photos,
                                key = { it.stableKey }
                            ) { item ->
                                val key = item.stableKey
                                LaunchedEffect(key) {
                                    onRequestThumbnail(item)
                                }
                                GalleryPhotoTile(
                                    item = item,
                                    thumbnail = galleryState.thumbnails[key],
                                    isLoading = key in galleryState.loadingThumbKeys,
                                    isDownloaded = key in galleryState.downloadedPhotos,
                                    onClick = { onSelectPhoto(item) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 单图详情与原图下载浮层
        if (selectedPhoto != null) {
            val key = selectedPhoto.stableKey
            val savedInfo = galleryState.downloadedPhotos[key]
            val activeProgress = galleryState.downloadProgress?.takeIf { it.photoKey == key }
            PhotoDetailLightbox(
                item = selectedPhoto,
                index = selectedIndex,
                totalCount = galleryState.photos.size,
                previewBitmap = savedInfo?.previewBitmap ?: galleryState.thumbnails[key],
                savedResult = savedInfo,
                downloadProgress = activeProgress,
                anyDownloadBusy = galleryState.downloadProgress != null,
                onPrev = if (selectedIndex > 0) {
                    { onSelectPhoto(galleryState.photos[selectedIndex - 1]) }
                } else {
                    null
                },
                onNext = if (selectedIndex in 0 until galleryState.photos.lastIndex) {
                    { onSelectPhoto(galleryState.photos[selectedIndex + 1]) }
                } else {
                    null
                },
                onDownloadOriginal = { onDownloadOriginal(selectedPhoto) },
                onExtractRawJpeg = { onExtractRawJpeg(selectedPhoto) },
                onClose = { onSelectPhoto(null) }
            )
        }
    }
}

@Composable
private fun TransportSwitcherBar(
    activeMode: PtpTransportMode,
    isWifiConnected: Boolean,
    usbState: UsbConnectionState,
    onSelectMode: (PtpTransportMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val isUsbConnected = usbState is UsbConnectionState.Connected
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF16191D))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TransportTabChip(
            label = "Wi-Fi 传输",
            statusDot = if (isWifiConnected) Color(0xFF34D399) else Color(0xFF6B7280),
            subtitle = if (isWifiConnected) "已连接" else "未连接",
            selected = activeMode == PtpTransportMode.WIFI_PTP_IP,
            onClick = { onSelectMode(PtpTransportMode.WIFI_PTP_IP) },
            modifier = Modifier.weight(1f)
        )
        TransportTabChip(
            label = "USB 有线",
            statusDot = when (usbState) {
                is UsbConnectionState.Connected -> Color(0xFF34D399)
                is UsbConnectionState.Connecting,
                is UsbConnectionState.PermissionRequired -> Color(0xFFF59E0B)
                else -> Color(0xFF6B7280)
            },
            subtitle = when (usbState) {
                is UsbConnectionState.Connected -> usbState.productName
                is UsbConnectionState.Connecting -> "连接中..."
                is UsbConnectionState.PermissionRequired -> "待授权"
                else -> "插线即用"
            },
            selected = activeMode == PtpTransportMode.USB_MTP,
            onClick = { onSelectMode(PtpTransportMode.USB_MTP) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TransportTabChip(
    label: String,
    statusDot: Color,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF252A32) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) Color(0xFFF87171).copy(alpha = 0.55f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(modifier = Modifier.size(7.dp)) {
            drawCircle(color = statusDot)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (selected) Color.White else Color(0xFFD1D5DB),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = subtitle,
                color = Color(0xFF9CA3AF),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GalleryPhotoTile(
    item: CameraPhotoItem,
    thumbnail: ImageBitmap?,
    isLoading: Boolean,
    isDownloaded: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(Color(0xFF1A1E24))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Color(0xFF9CA3AF),
                trackColor = Color(0xFF2A2F37)
            )
        } else {
            Text(
                text = if (item.isRaw) "RAW" else "JPG",
                color = Color(0xFF6B7280),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // 左上角格式 / 内存直传角标
        if (item.isRaw || item.isSdram) {
            Text(
                text = if (item.isSdram) "实时" else "RAW",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (item.isSdram) Color(0xFF2563EB).copy(alpha = 0.85f)
                        else Color(0xFFD97706).copy(alpha = 0.88f)
                    )
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }

        // 右上角已下载勾选标记
        if (isDownloaded) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF10B981)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 底部文件名与大小半透明条
        if (item.metadataLoaded) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.56f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.displayName,
                    color = Color(0xFFE5E7EB),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PhotoDetailLightbox(
    item: CameraPhotoItem,
    index: Int,
    totalCount: Int,
    previewBitmap: ImageBitmap?,
    savedResult: SavedPhotoResult?,
    downloadProgress: GalleryDownloadProgress?,
    anyDownloadBusy: Boolean,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onDownloadOriginal: () -> Unit,
    onExtractRawJpeg: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090B0E))
            .clickable(enabled = true, onClick = {})
    ) {
        // 顶部返回与页码
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionPillButton(label = "← 返回", onClick = onClose)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = item.displayName,
                    color = Color(0xFFF3F4F6),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${index + 1} / $totalCount · ${item.transport.label}",
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.size(56.dp))
        }

        // 中央图像预览区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap,
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = Color(0xFF9CA3AF)
                )
            }

            if (savedResult == null && previewBitmap != null) {
                Text(
                    text = "缩略图预览",
                    color = Color(0xFFE5E7EB),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // 底部元数据与下载原图操作区
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF14171B))
                .border(1.dp, Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 元数据一行展示
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val resText = if (item.width > 0 && item.height > 0) {
                    "${item.width}×${item.height}"
                } else {
                    "原图分辨率"
                }
                val sizeText = SonyPtpIpProtocol.formatFileSize(
                    savedResult?.sizeBytes ?: item.sizeBytes
                )
                Text(
                    text = "$resText · $sizeText",
                    color = Color(0xFFD1D5DB),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (item.captureDate.isNotBlank()) {
                    Text(
                        text = item.captureDate,
                        color = Color(0xFF9CA3AF),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // 下载进度条
            if (downloadProgress != null) {
                val pct = (downloadProgress.fraction * 100).toInt()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (downloadProgress.totalBytes > 0L) {
                                "正在下载原图 $pct%"
                            } else {
                                "正在下载原图..."
                            },
                            color = Color(0xFFFDE68A),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${SonyPtpIpProtocol.formatFileSize(downloadProgress.bytesRead)} / ${
                                SonyPtpIpProtocol.formatFileSize(downloadProgress.totalBytes)
                            }",
                            color = Color(0xFF9CA3AF),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (downloadProgress.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = { downloadProgress.fraction },
                            color = Color(0xFFF87171),
                            trackColor = Color(0xFF272B33),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                        )
                    } else {
                        LinearProgressIndicator(
                            color = Color(0xFFF87171),
                            trackColor = Color(0xFF272B33),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                        )
                    }
                }
            }

            if (savedResult != null) {
                Text(
                    text = "已保存：${savedResult.displayPath}",
                    color = Color(0xFF6EE7B7),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 上一张 / 主操作（RAW 优先提取全尺寸 JPG；JPG 直接下载原图） / 下一张
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionPillButton(
                    label = "‹ 上一张",
                    enabled = onPrev != null,
                    onClick = { onPrev?.invoke() }
                )

                val buttonBg = when {
                    savedResult != null -> Color(0xFF065F46)
                    downloadProgress != null -> Color(0xFF374151)
                    else -> Color(0xFFB91C1C)
                }
                val buttonBorder = when {
                    savedResult != null -> Color(0xFF34D399)
                    downloadProgress != null -> Color.White.copy(alpha = 0.16f)
                    else -> Color(0xFFF87171)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(buttonBg)
                        .border(1.dp, buttonBorder, RoundedCornerShape(12.dp))
                        .clickable(enabled = !anyDownloadBusy) {
                            if (item.isRaw) onExtractRawJpeg() else onDownloadOriginal()
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            downloadProgress != null -> if (item.isRaw) "提取/下载中..." else "下载中..."
                            item.isRaw && savedResult?.isExtractedJpeg == true -> "已提取全尺寸 JPG ✓"
                            item.isRaw -> "提取全尺寸 JPG 到相册"
                            savedResult != null -> "已保存原图 ✓（再次下载）"
                            else -> "下载原图到相册"
                        },
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                ActionPillButton(
                    label = "下一张 ›",
                    enabled = onNext != null,
                    onClick = { onNext?.invoke() }
                )
            }

            // RAW 格式额外提供下载原始 .ARW 文件按钮
            if (item.isRaw) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1F242B))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .clickable(enabled = !anyDownloadBusy) { onDownloadOriginal() }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "下载原始 ARW 文件 (${SonyPtpIpProtocol.formatFileSize(item.sizeBytes)})",
                        color = Color(0xFFD1D5DB),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryEmptyOrErrorView(
    title: String,
    message: String,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            color = Color(0xFFF3F4F6),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = message,
            color = Color(0xFF9CA3AF),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFB91C1C))
                .border(1.dp, Color(0xFFF87171), RoundedCornerShape(12.dp))
                .clickable(onClick = onPrimaryAction)
                .padding(horizontal = 20.dp, vertical = 11.dp)
        ) {
            Text(
                text = primaryActionLabel,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ActionPillButton(
    label: String,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Text(
        text = label,
        color = when {
            !enabled -> Color(0xFF4B5563)
            accent -> Color(0xFFFCA5A5)
            else -> Color(0xFFE5E7EB)
        },
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF1C2026))
            .border(
                width = 1.dp,
                color = if (accent && enabled) Color(0xFFF87171).copy(alpha = 0.45f)
                else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(999.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}
