package com.sonytimer.shutter.controller

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 统一 PTP 传输通道类型：
 * - WIFI_PTP_IP：室内/无线同局域网 PTP/IP（TCP 15740）
 * - USB_MTP：室外/高速有线 Android 原生 MtpDevice（USB PTP/MTP）
 */
enum class PtpTransportMode(val label: String) {
    WIFI_PTP_IP("Wi-Fi"),
    USB_MTP("USB 有线")
}

/**
 * 统一 PTP 异步事件流（屏蔽 USB MtpDevice 与 Wi-Fi PTP/IP 底层差异）。
 */
sealed interface PtpEvent {
    data class ObjectAdded(
        val handle: Int,
        val transport: PtpTransportMode,
        val isSdram: Boolean = false
    ) : PtpEvent

    data class ObjectRemoved(
        val handle: Int,
        val transport: PtpTransportMode
    ) : PtpEvent

    data class StorageChanged(
        val transport: PtpTransportMode
    ) : PtpEvent

    data class TransportDisconnected(
        val transport: PtpTransportMode,
        val reason: String? = null
    ) : PtpEvent
}

/**
 * 相机图库中的单张照片条目。
 * 当存储卡内存在同名 RAW+JPG 时，合并为一张卡片：主句柄指向 JPG 用于极速预览，
 * 同时记录配套 RAW 句柄，便于用户下载时按需选择「仅 JPG」或「RAW + JPG」。
 */
data class CameraPhotoItem(
    val handle: Int,
    val transport: PtpTransportMode,
    val filename: String = "",
    val formatCode: Int = 0,
    val sizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val captureDate: String = "",
    val isRaw: Boolean = false,
    val isSdram: Boolean = false,
    val metadataLoaded: Boolean = false,
    val pairedRawHandle: Int? = null,
    val pairedRawFilename: String? = null,
    val pairedRawSizeBytes: Long = 0L
) {
    val stableKey: String
        get() = "${transport.name}_${handle}_${if (isSdram) "sdram" else "card"}"

    val hasPairedRaw: Boolean
        get() = pairedRawHandle != null

    val hasDownloadChoices: Boolean
        get() = hasPairedRaw || isRaw

    val displayName: String
        get() = filename.ifBlank {
            if (isSdram) "最新拍摄.JPG" else "IMG_${(handle and 0xFFFF).toString(16).uppercase()}"
        }
}

enum class PhotoDownloadMode {
    JPG_ONLY,
    RAW_AND_JPG,
    RAW_ONLY
}

data class PhotoThumbnailResult(
    val updatedItem: CameraPhotoItem,
    val thumbnail: ImageBitmap?,
    val shouldRemove: Boolean = false
)

data class GalleryListResult(
    val photos: List<CameraPhotoItem>,
    val notice: String? = null
)

data class SavedPhotoResult(
    val filename: String,
    val displayPath: String,
    val sizeBytes: Long,
    val previewBitmap: ImageBitmap? = null,
    val isExtractedJpeg: Boolean = false,
    val summaryLabel: String = filename
)

sealed interface UsbConnectionState {
    data object Disconnected : UsbConnectionState
    data class PermissionRequired(val deviceName: String) : UsbConnectionState
    data class Connecting(val deviceName: String) : UsbConnectionState
    data class Connected(val deviceName: String, val productName: String) : UsbConnectionState
    data class Error(val message: String) : UsbConnectionState
}

/**
 * 统一底层 PTP 传输接口：USB (MtpDevice) 与 Wi-Fi (PTP/IP Socket) 在此语义 100% 对齐。
 */
interface PtpTransport {
    val mode: PtpTransportMode
    val isConnected: Boolean
    val events: Flow<PtpEvent>

    suspend fun listPhotos(maxItems: Int = 200): Result<GalleryListResult>
    suspend fun getObjectInfo(item: CameraPhotoItem): Result<CameraPhotoItem>
    suspend fun getThumbnailBytes(item: CameraPhotoItem): Result<ByteArray>
}

/**
 * 供 ViewModel 与跨平台 UI 使用的相机图库控制器。
 */
interface CameraGalleryController {
    val usbState: StateFlow<UsbConnectionState>
    val events: Flow<PtpEvent>

    fun scanAndConnectUsb()
    fun disconnectUsb()
    fun setGalleryActive(active: Boolean)

    suspend fun listPhotos(mode: PtpTransportMode): Result<GalleryListResult>
    suspend fun loadThumbnail(item: CameraPhotoItem): Result<PhotoThumbnailResult>
    suspend fun loadLargePreview(item: CameraPhotoItem): Result<ImageBitmap>
    suspend fun downloadPhoto(
        item: CameraPhotoItem,
        mode: PhotoDownloadMode,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<SavedPhotoResult>

    fun release()
}
