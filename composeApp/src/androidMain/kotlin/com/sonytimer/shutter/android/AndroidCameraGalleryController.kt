package com.sonytimer.shutter.android

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.sonytimer.shutter.controller.CameraGalleryController
import com.sonytimer.shutter.controller.CameraPhotoItem
import com.sonytimer.shutter.controller.PhotoThumbnailResult
import com.sonytimer.shutter.controller.PtpEvent
import com.sonytimer.shutter.controller.PtpTransportMode
import com.sonytimer.shutter.controller.SavedPhotoResult
import com.sonytimer.shutter.controller.UsbConnectionState
import com.sonytimer.shutter.protocol.SonyPtpIpProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Android 端统一相机图库控制器：
 * - 聚合 Wi-Fi PTP/IP 与 USB MTP 两个底层 [AndroidPtpTransport]
 * - 统一对外暴露 Flow<PtpEvent> 事件流、缩略图 LRU 缓存、以及原图下载到系统相册（Pictures/SonyTimerShutter）
 */
class AndroidCameraGalleryController(
    context: Context,
    private val wifiTransport: AndroidSonyLiveViewController,
    private val usbTransport: AndroidUsbMtpTransport
) : CameraGalleryController {

    private val appContext = context.applicationContext

    override val usbState: StateFlow<UsbConnectionState> = usbTransport.usbState
    override val events: Flow<PtpEvent> = merge(wifiTransport.events, usbTransport.events)

    private val thumbnailCache = LruCache<String, ImageBitmap>(MAX_CACHED_THUMBNAILS)
    private val metadataCache = LruCache<String, CameraPhotoItem>(MAX_CACHED_METADATA)

    override fun scanAndConnectUsb() {
        usbTransport.scanAndConnect()
    }

    override fun disconnectUsb() {
        usbTransport.disconnect()
    }

    override fun setGalleryActive(active: Boolean) {
        wifiTransport.setLiveViewPaused(active)
    }

    override suspend fun listPhotos(mode: PtpTransportMode): Result<List<CameraPhotoItem>> {
        val transport = resolveTransport(mode)
        return transport.listPhotos(maxItems = DEFAULT_MAX_PHOTOS).map { list ->
            list.map { item ->
                val cached = metadataCache.get(item.stableKey)
                if (cached != null && !item.isSdram) cached else item
            }
        }
    }

    override suspend fun loadThumbnail(item: CameraPhotoItem): Result<PhotoThumbnailResult> =
        withContext(Dispatchers.IO) {
            val transport = resolveTransport(item.transport)
            val cacheKey = item.stableKey

            val cachedThumb = if (!item.isSdram) thumbnailCache.get(cacheKey) else null
            val cachedMeta = if (!item.isSdram) metadataCache.get(cacheKey) else null
            if (cachedThumb != null && cachedMeta != null && cachedMeta.metadataLoaded) {
                return@withContext Result.success(
                    PhotoThumbnailResult(updatedItem = cachedMeta, thumbnail = cachedThumb)
                )
            }

            runCatching {
                val resolvedItem = if (item.metadataLoaded && !item.isSdram) {
                    item
                } else {
                    transport.getObjectInfo(item).getOrDefault(item).also { updated ->
                        if (!updated.isSdram && updated.metadataLoaded) {
                            metadataCache.put(cacheKey, updated)
                        }
                    }
                }

                val bitmap = cachedThumb ?: transport.getThumbnailBytes(resolvedItem).getOrNull()?.let { bytes ->
                    decodeSampledBitmap(bytes, maxDimension = MAX_THUMB_DIMENSION)
                }?.also { img ->
                    if (!resolvedItem.isSdram) {
                        thumbnailCache.put(cacheKey, img)
                    }
                }

                PhotoThumbnailResult(updatedItem = resolvedItem, thumbnail = bitmap)
            }
        }

    override suspend fun downloadOriginal(
        item: CameraPhotoItem,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<SavedPhotoResult> = withContext(Dispatchers.IO) {
        val transport = resolveTransport(item.transport)
        val cacheDir = File(appContext.cacheDir, "camera_downloads").apply { mkdirs() }
        var tempFile: File? = null

        runCatching {
            val enrichedItem = if (item.metadataLoaded && item.filename.isNotBlank()) {
                item
            } else {
                transport.getObjectInfo(item).getOrDefault(item)
            }

            val finalName = sanitizeFilename(enrichedItem)
            tempFile = File(cacheDir, "tmp_${System.currentTimeMillis()}_$finalName")
            val dest = tempFile!!

            val writtenBytes = transport.exportOriginalToFile(
                item = enrichedItem,
                destFile = dest,
                onProgress = onProgress
            ).getOrThrow()

            val displayPath = saveFileToGallery(dest, finalName, enrichedItem)
            val previewBitmap = if (!enrichedItem.isRaw) {
                decodeSampledBitmapFromFile(dest, maxDimension = MAX_PREVIEW_DIMENSION)
            } else {
                null
            }

            SavedPhotoResult(
                filename = finalName,
                displayPath = displayPath,
                sizeBytes = writtenBytes,
                previewBitmap = previewBitmap
            )
        }.also {
            runCatching { tempFile?.delete() }
        }
    }

    override fun release() {
        thumbnailCache.evictAll()
        metadataCache.evictAll()
        usbTransport.release()
    }

    private fun resolveTransport(mode: PtpTransportMode): AndroidPtpTransport {
        return when (mode) {
            PtpTransportMode.WIFI_PTP_IP -> wifiTransport
            PtpTransportMode.USB_MTP -> usbTransport
        }
    }

    private fun sanitizeFilename(item: CameraPhotoItem): String {
        val raw = item.filename.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (raw.isNotBlank() && raw.contains('.')) return raw
        val ext = when {
            SonyPtpIpProtocol.isRawFormat(item.formatCode, raw) -> "ARW"
            item.formatCode == SonyPtpIpProtocol.FORMAT_HEIF -> "HIF"
            else -> "JPG"
        }
        val base = raw.ifBlank { "SONY_${System.currentTimeMillis()}" }
        return "$base.$ext"
    }

    private fun resolveMimeType(filename: String, isRaw: Boolean): String {
        val lower = filename.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".hif") || lower.endsWith(".heif") -> "image/heif"
            lower.endsWith(".heic") -> "image/heic"
            lower.endsWith(".dng") -> "image/x-adobe-dng"
            lower.endsWith(".arw") || isRaw -> "image/x-sony-arw"
            else -> "image/jpeg"
        }
    }

    private fun saveFileToGallery(sourceFile: File, filename: String, item: CameraPhotoItem): String {
        val mimeType = resolveMimeType(filename, item.isRaw)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relativeDir = "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR_NAME"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (item.isRaw) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val targetRelative = if (item.isRaw) {
                "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM_DIR_NAME"
            } else {
                relativeDir
            }
            if (item.isRaw) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, targetRelative)
            }

            val resolver = appContext.contentResolver
            val uri = resolver.insert(collection, values)
                ?: throw IOException("无法在系统相册中创建文件")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out, bufferSize = 32 * 1024)
                    }
                } ?: throw IOException("无法写入系统相册输出流")

                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                return "$targetRelative/$filename"
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            val baseDir = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: appContext.filesDir
            val targetDir = File(baseDir, ALBUM_DIR_NAME).apply { mkdirs() }
            val outFile = File(targetDir, filename)
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            }
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(outFile.absolutePath),
                arrayOf(mimeType),
                null
            )
            return outFile.absolutePath
        }
    }

    private fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }

    private fun decodeSampledBitmapFromFile(file: File, maxDimension: Int): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
    }

    private fun computeInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        val largest = maxOf(width, height)
        while (largest / sample > maxDim) {
            sample *= 2
        }
        return sample
    }

    private companion object {
        const val ALBUM_DIR_NAME = "SonyTimerShutter"
        const val DEFAULT_MAX_PHOTOS = 240
        const val MAX_CACHED_THUMBNAILS = 96
        const val MAX_CACHED_METADATA = 300
        const val MAX_THUMB_DIMENSION = 480
        const val MAX_PREVIEW_DIMENSION = 1920
    }
}
