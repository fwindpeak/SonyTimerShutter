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
import com.sonytimer.shutter.controller.GalleryListResult
import com.sonytimer.shutter.controller.PhotoThumbnailResult
import com.sonytimer.shutter.controller.PtpEvent
import com.sonytimer.shutter.controller.PtpTransportMode
import com.sonytimer.shutter.controller.SavedPhotoResult
import com.sonytimer.shutter.controller.UsbConnectionState
import com.sonytimer.shutter.parser.BinaryReader
import com.sonytimer.shutter.parser.EmbeddedJpegInfo
import com.sonytimer.shutter.parser.SonyArwParser
import com.sonytimer.shutter.protocol.SonyPtpIpProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Android 端统一相机图库控制器：
 * - 聚合 Wi-Fi PTP/IP 与 USB MTP 两个底层 [AndroidPtpTransport]
 * - 集成 [SonyArwParser]（移植自 fwindpeak/PhotoPicker），支持从索尼 RAW (.ARW) 中提取预览图与全尺寸 JPG
 * - 优先使用分块读取（GetPartialObject / SDIO_GetPartialLargeObject / MtpDevice.getPartialObject）
 *   直接定位并提取 ARW 内嵌全尺寸 JPG，大幅缩短传输时间
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

    override suspend fun listPhotos(mode: PtpTransportMode): Result<GalleryListResult> {
        val transport = resolveTransport(mode)
        return transport.listPhotos(maxItems = DEFAULT_MAX_PHOTOS).map { result ->
            val mapped = result.photos.map { item ->
                val cached = metadataCache.get(item.stableKey)
                if (cached != null && !item.isSdram) cached else item
            }
            result.copy(photos = mapped)
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

                // 若在延迟拉取 ObjectInfo 后发现该句柄是目录或非图片文件（如 MP4/XML），通知 UI 自动过滤
                if (resolvedItem.metadataLoaded && !resolvedItem.isSdram) {
                    val isFolder = resolvedItem.formatCode == SonyPtpIpProtocol.FORMAT_ASSOCIATION
                    val isPhoto = SonyPtpIpProtocol.isImageFormat(
                        resolvedItem.formatCode,
                        resolvedItem.filename
                    )
                    if (isFolder || !isPhoto) {
                        return@runCatching PhotoThumbnailResult(
                            updatedItem = resolvedItem,
                            thumbnail = null,
                            shouldRemove = true
                        )
                    }
                }

                // 1. 优先走标准 GetThumb（毫秒级，仅约 30KB）
                var bitmap = cachedThumb ?: transport.getThumbnailBytes(resolvedItem)
                    .getOrNull()
                    ?.let { bytes -> decodeSampledBitmap(bytes, maxDimension = MAX_THUMB_DIMENSION) }

                // 2. 若 GetThumb 失败（如索尼 RAW / SDRAM 0xFFFFC001 照片），用 SonyArwParser 提取内嵌预览 JPG
                if (bitmap == null && (resolvedItem.isRaw || resolvedItem.isSdram)) {
                    bitmap = extractEmbeddedPreviewBitmap(transport, resolvedItem, MAX_THUMB_DIMENSION)
                }

                if (bitmap != null && !resolvedItem.isSdram) {
                    thumbnailCache.put(cacheKey, bitmap)
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

            val finalName = sanitizeFilename(enrichedItem, forceJpg = false)
            tempFile = File(cacheDir, "tmp_${System.currentTimeMillis()}_$finalName")
            val dest = tempFile!!

            val writtenBytes = transport.exportOriginalToFile(
                item = enrichedItem,
                destFile = dest,
                onProgress = onProgress
            ).getOrThrow()

            val displayPath = saveFileToGallery(dest, finalName, isRaw = enrichedItem.isRaw)
            val previewBitmap = if (enrichedItem.isRaw) {
                extractJpegBitmapFromArwFile(dest, MAX_PREVIEW_DIMENSION)
            } else {
                decodeSampledBitmapFromFile(dest, MAX_PREVIEW_DIMENSION)
            }

            SavedPhotoResult(
                filename = finalName,
                displayPath = displayPath,
                sizeBytes = writtenBytes,
                previewBitmap = previewBitmap,
                isExtractedJpeg = false
            )
        }.also {
            runCatching { tempFile?.delete() }
        }
    }

    override suspend fun extractRawFullJpeg(
        item: CameraPhotoItem,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<SavedPhotoResult> = withContext(Dispatchers.IO) {
        val transport = resolveTransport(item.transport)
        val cacheDir = File(appContext.cacheDir, "camera_downloads").apply { mkdirs() }
        var tempRawFile: File? = null
        var tempJpgFile: File? = null

        runCatching {
            val enrichedItem = if (item.metadataLoaded && item.filename.isNotBlank()) {
                item
            } else {
                transport.getObjectInfo(item).getOrDefault(item)
            }

            val jpgName = sanitizeFilename(enrichedItem, forceJpg = true)
            tempJpgFile = File(cacheDir, "jpg_${System.currentTimeMillis()}_$jpgName")
            val outJpg = tempJpgFile!!

            // 方案 A（极速）：通过分块读取仅拉取 ARW 头部 + 内嵌全尺寸 JPG 区间（省去 90% 流量）
            val extractedViaPartial = tryExtractFullJpegViaPartial(
                transport = transport,
                item = enrichedItem,
                destJpgFile = outJpg,
                onProgress = onProgress
            )

            val jpgSize: Long = if (extractedViaPartial > 0L) {
                extractedViaPartial
            } else {
                // 方案 B（通用兜底）：完整拉取 ARW 到缓存，再用 SonyArwParser 本地提取全尺寸 JPG
                tempRawFile = File(cacheDir, "raw_${System.currentTimeMillis()}.arw")
                val rawFile = tempRawFile!!
                transport.exportOriginalToFile(
                    item = enrichedItem,
                    destFile = rawFile,
                    onProgress = onProgress
                ).getOrThrow()

                extractBestJpegFromArwFileToFile(rawFile, outJpg)
            }

            val displayPath = saveFileToGallery(outJpg, jpgName, isRaw = false)
            val previewBitmap = decodeSampledBitmapFromFile(outJpg, MAX_PREVIEW_DIMENSION)

            SavedPhotoResult(
                filename = jpgName,
                displayPath = displayPath,
                sizeBytes = jpgSize,
                previewBitmap = previewBitmap,
                isExtractedJpeg = true
            )
        }.also {
            runCatching { tempRawFile?.delete() }
            runCatching { tempJpgFile?.delete() }
        }
    }

    override fun release() {
        thumbnailCache.evictAll()
        metadataCache.evictAll()
        usbTransport.release()
    }

    private suspend fun extractEmbeddedPreviewBitmap(
        transport: AndroidPtpTransport,
        item: CameraPhotoItem,
        maxDimension: Int
    ): ImageBitmap? {
        // 1. 优先尝试通过分块读取解析 ARW 内嵌缩略图 / 预览图
        if (item.sizeBytes > 0L) {
            val partialBytes = runCatching {
                val reader = RemotePartialBinaryReader(item.sizeBytes) { offset, len ->
                    runBlocking { transport.readPartialObject(item, offset, len).getOrNull() }
                }
                val parsed = SonyArwParser(reader).parse(allowFallbackScan = false)
                val target = parsed.thumbnail ?: parsed.preview ?: parsed.fullSize
                if (target != null && target.length in 1_024L..6_000_000L) {
                    readRangeViaPartial(transport, item, target.offset, target.length.toInt())
                } else {
                    null
                }
            }.getOrNull()

            if (partialBytes != null && partialBytes.isNotEmpty()) {
                decodeSampledBitmap(partialBytes, maxDimension)?.let { return it }
            }
        }

        // 2. 兜底（如 SDRAM 0xFFFFC001 不支持分块读时）：导出到临时文件后用 SonyArwParser 提取内嵌预览图
        if (item.isSdram) {
            val cacheDir = File(appContext.cacheDir, "camera_downloads").apply { mkdirs() }
            val tempFile = File(cacheDir, "sdram_preview_${System.currentTimeMillis()}.bin")
            try {
                val exported = transport.exportOriginalToFile(item, tempFile) { _, _ -> }.isSuccess
                if (exported && tempFile.exists() && tempFile.length() > 0L) {
                    return if (item.isRaw) {
                        extractJpegBitmapFromArwFile(tempFile, maxDimension)
                    } else {
                        decodeSampledBitmapFromFile(tempFile, maxDimension)
                    }
                }
            } finally {
                runCatching { tempFile.delete() }
            }
        }
        return null
    }

    private suspend fun tryExtractFullJpegViaPartial(
        transport: AndroidPtpTransport,
        item: CameraPhotoItem,
        destJpgFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Long {
        if (item.sizeBytes <= 0L) return -1L
        return runCatching {
            val reader = RemotePartialBinaryReader(item.sizeBytes) { offset, len ->
                runBlocking { transport.readPartialObject(item, offset, len).getOrNull() }
            }
            val parsed = SonyArwParser(reader).parse(allowFallbackScan = false)
            val embedded: EmbeddedJpegInfo = parsed.fullSize ?: parsed.preview ?: parsed.thumbnail
                ?: return@runCatching -1L
            if (embedded.length <= 0L || embedded.length > MAX_EXTRACTED_JPEG_BYTES) {
                return@runCatching -1L
            }

            var written = 0L
            val total = embedded.length
            onProgress(0L, total)
            FileOutputStream(destJpgFile).use { fos ->
                BufferedOutputStream(fos, 32 * 1024).use { bos ->
                    while (written < total) {
                        val toRead = minOf(PARTIAL_STREAM_CHUNK_SIZE.toLong(), total - written).toInt()
                        val chunk = transport.readPartialObject(
                            item = item,
                            offset = embedded.offset + written,
                            maxBytes = toRead
                        ).getOrThrow()
                        if (chunk.isEmpty()) break
                        bos.write(chunk)
                        written += chunk.size
                        onProgress(written, total)
                    }
                    bos.flush()
                }
            }
            if (written > 0L) written else -1L
        }.getOrDefault(-1L)
    }

    private suspend fun readRangeViaPartial(
        transport: AndroidPtpTransport,
        item: CameraPhotoItem,
        offset: Long,
        length: Int
    ): ByteArray? {
        val out = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val chunkSize = minOf(PARTIAL_STREAM_CHUNK_SIZE, length - filled)
            val chunk = transport.readPartialObject(item, offset + filled, chunkSize).getOrNull()
                ?: return null
            if (chunk.isEmpty()) break
            val copyLen = minOf(chunk.size, length - filled)
            chunk.copyInto(out, destinationOffset = filled, startIndex = 0, endIndex = copyLen)
            filled += copyLen
        }
        return if (filled > 0) out.copyOf(filled) else null
    }

    private fun extractBestJpegFromArwFileToFile(arwFile: File, destJpgFile: File): Long {
        val reader = RandomAccessFileBinaryReader(arwFile)
        return try {
            val parsed = SonyArwParser(reader).parse(allowFallbackScan = true)
            val embedded = parsed.bestPreview
                ?: throw IOException("未能从该 RAW 文件中解析出内嵌 JPG")
            RandomAccessFile(arwFile, "r").use { raf ->
                raf.seek(embedded.offset)
                FileOutputStream(destJpgFile).use { fos ->
                    BufferedOutputStream(fos, 32 * 1024).use { bos ->
                        val buf = ByteArray(32 * 1024)
                        var remaining = embedded.length
                        var written = 0L
                        while (remaining > 0L) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = raf.read(buf, 0, toRead)
                            if (n <= 0) break
                            bos.write(buf, 0, n)
                            written += n
                            remaining -= n
                        }
                        bos.flush()
                        if (written <= 0L) throw IOException("提取的内嵌 JPG 为空")
                        written
                    }
                }
            }
        } finally {
            reader.close()
        }
    }

    private fun extractJpegBitmapFromArwFile(arwFile: File, maxDimension: Int): ImageBitmap? {
        val reader = RandomAccessFileBinaryReader(arwFile)
        return try {
            val parsed = SonyArwParser(reader).parse(allowFallbackScan = true)
            val embedded = parsed.bestPreview ?: return null
            if (embedded.length <= 0L || embedded.length > MAX_EXTRACTED_JPEG_BYTES) return null
            val bytes = reader.readBytes(embedded.offset, embedded.length.toInt())
            if (bytes.isEmpty()) null else decodeSampledBitmap(bytes, maxDimension)
        } catch (_: Exception) {
            null
        } finally {
            reader.close()
        }
    }

    private fun resolveTransport(mode: PtpTransportMode): AndroidPtpTransport {
        return when (mode) {
            PtpTransportMode.WIFI_PTP_IP -> wifiTransport
            PtpTransportMode.USB_MTP -> usbTransport
        }
    }

    private fun sanitizeFilename(item: CameraPhotoItem, forceJpg: Boolean): String {
        val raw = item.filename.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        if (forceJpg) {
            val dotIdx = raw.lastIndexOf('.')
            val base = when {
                dotIdx > 0 -> raw.substring(0, dotIdx)
                raw.isNotBlank() -> raw
                else -> "SONY_${System.currentTimeMillis()}"
            }
            return "$base.JPG"
        }
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

    private fun saveFileToGallery(sourceFile: File, filename: String, isRaw: Boolean): String {
        val mimeType = resolveMimeType(filename, isRaw)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val targetRelative = if (isRaw) {
                "${Environment.DIRECTORY_DOWNLOADS}/$ALBUM_DIR_NAME"
            } else {
                "${Environment.DIRECTORY_PICTURES}/$ALBUM_DIR_NAME"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, targetRelative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (isRaw) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
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

    /**
     * 本地文件随机访问读取器（带 32KB 页缓冲）。
     */
    private class RandomAccessFileBinaryReader(file: File) : BinaryReader {
        private val raf = RandomAccessFile(file, "r")
        override val length: Long = file.length()
        private var cachedPageStart = -1L
        private var cachedPage: ByteArray = ByteArray(0)

        override fun readByte(offset: Long): Byte {
            if (offset < 0L || offset >= length) return 0
            val pageStart = (offset / PAGE_SIZE) * PAGE_SIZE
            if (pageStart != cachedPageStart) {
                val readLen = minOf(PAGE_SIZE.toLong(), length - pageStart).toInt()
                val buf = ByteArray(readLen)
                raf.seek(pageStart)
                raf.readFully(buf)
                cachedPageStart = pageStart
                cachedPage = buf
            }
            return cachedPage[(offset - pageStart).toInt()]
        }

        override fun readBytes(offset: Long, length: Int): ByteArray {
            if (offset < 0L || length <= 0 || offset >= this.length) return ByteArray(0)
            val valid = minOf(length.toLong(), this.length - offset).toInt()
            val buf = ByteArray(valid)
            raf.seek(offset)
            raf.readFully(buf)
            return buf
        }

        override fun close() {
            runCatching { raf.close() }
        }

        private companion object {
            const val PAGE_SIZE = 32 * 1024
        }
    }

    /**
     * 远程 PTP PartialObject 分页读取器（用于让 SonyArwParser 仅读取头部少量字节即定位全尺寸 JPG）。
     */
    private class RemotePartialBinaryReader(
        override val length: Long,
        private val fetchBlock: (offset: Long, size: Int) -> ByteArray?
    ) : BinaryReader {
        private val pages = LinkedHashMap<Long, ByteArray>(12, 0.75f, true)

        override fun readByte(offset: Long): Byte {
            if (offset < 0L || offset >= length) return 0
            val pageIndex = offset / PAGE_SIZE
            val page = loadPage(pageIndex) ?: return 0
            val idx = (offset - pageIndex * PAGE_SIZE).toInt()
            return if (idx in page.indices) page[idx] else 0
        }

        override fun readBytes(offset: Long, length: Int): ByteArray {
            if (offset < 0L || length <= 0 || offset >= this.length) return ByteArray(0)
            val valid = minOf(length.toLong(), this.length - offset).toInt()
            val out = ByteArray(valid)
            var copied = 0
            while (copied < valid) {
                val cur = offset + copied
                val pageIndex = cur / PAGE_SIZE
                val page = loadPage(pageIndex) ?: break
                val pageOffset = (cur - pageIndex * PAGE_SIZE).toInt()
                if (pageOffset >= page.size) break
                val n = minOf(valid - copied, page.size - pageOffset)
                page.copyInto(out, destinationOffset = copied, startIndex = pageOffset, endIndex = pageOffset + n)
                copied += n
            }
            return if (copied == valid) out else out.copyOf(copied)
        }

        private fun loadPage(pageIndex: Long): ByteArray? {
            pages[pageIndex]?.let { return it }
            val start = pageIndex * PAGE_SIZE
            if (start >= length) return null
            val size = minOf(PAGE_SIZE.toLong(), length - start).toInt()
            val data = fetchBlock(start, size) ?: return null
            if (pages.size >= MAX_CACHED_PAGES) {
                val eldest = pages.keys.firstOrNull()
                if (eldest != null) pages.remove(eldest)
            }
            pages[pageIndex] = data
            return data
        }

        private companion object {
            const val PAGE_SIZE = 64 * 1024
            const val MAX_CACHED_PAGES = 10
        }
    }

    private companion object {
        const val ALBUM_DIR_NAME = "SonyTimerShutter"
        const val DEFAULT_MAX_PHOTOS = 240
        const val MAX_CACHED_THUMBNAILS = 96
        const val MAX_CACHED_METADATA = 300
        const val MAX_THUMB_DIMENSION = 480
        const val MAX_PREVIEW_DIMENSION = 2048
        const val PARTIAL_STREAM_CHUNK_SIZE = 256 * 1024
        const val MAX_EXTRACTED_JPEG_BYTES = 25 * 1024 * 1024L
    }
}
