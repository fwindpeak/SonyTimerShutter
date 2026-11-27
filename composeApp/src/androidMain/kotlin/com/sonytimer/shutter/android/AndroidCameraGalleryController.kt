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
import com.sonytimer.shutter.controller.CameraPhotoAggregator
import com.sonytimer.shutter.controller.CameraPhotoItem
import com.sonytimer.shutter.controller.GalleryListResult
import com.sonytimer.shutter.controller.PhotoDownloadMode
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
 * - 网格预览阶段：合并同名 RAW+JPG 为单张卡片，仅拉取约 30KB 小缩略图，绝不拉取 40MB+ RAW；
 * - 点开单图阶段：按需拉取高清 JPG 大图预览（纯 RAW 时通过分块读取仅提取内嵌 JPG 预览）；
 * - 下载到手机阶段：由用户按需选择「仅 JPG」「RAW + JPG」或「仅 RAW」。
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
    private val largePreviewCache = LruCache<String, ImageBitmap>(MAX_CACHED_LARGE_PREVIEWS)
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
            val withCache = result.photos.map { item ->
                val cached = metadataCache.get(item.stableKey)
                if (cached != null && !item.isSdram) cached else item
            }
            val merged = CameraPhotoAggregator.mergePairedRawAndJpg(withCache)
            result.copy(photos = merged)
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

                // 延迟拉取 ObjectInfo 后若发现是目录或非图片（如 MP4/XML），通知 UI 自动过滤
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

                // 1. 优先走标准 GetThumb（毫秒级，仅约 30KB，不拉原图与 RAW）
                var bitmap = cachedThumb ?: transport.getThumbnailBytes(resolvedItem)
                    .getOrNull()
                    ?.let { bytes -> decodeSampledBitmap(bytes, maxDimension = MAX_THUMB_DIMENSION) }

                // 2. 纯 RAW 或 SDRAM 若 GetThumb 失败，仅用分块读取提取内嵌小缩略图（不拉整张 RAW）
                if (bitmap == null && (resolvedItem.isRaw || resolvedItem.isSdram)) {
                    bitmap = extractEmbeddedPreviewBitmap(
                        transport = transport,
                        item = resolvedItem,
                        maxDimension = MAX_THUMB_DIMENSION,
                        preferSmallThumbnail = true
                    )
                }

                if (bitmap != null && !resolvedItem.isSdram) {
                    thumbnailCache.put(cacheKey, bitmap)
                }

                PhotoThumbnailResult(updatedItem = resolvedItem, thumbnail = bitmap)
            }
        }

    override suspend fun loadLargePreview(item: CameraPhotoItem): Result<ImageBitmap> =
        withContext(Dispatchers.IO) {
            val cacheKey = item.stableKey
            if (!item.isSdram) {
                largePreviewCache.get(cacheKey)?.let { return@withContext Result.success(it) }
            }

            val transport = resolveTransport(item.transport)
            runCatching {
                val enriched = if (item.metadataLoaded) item else {
                    transport.getObjectInfo(item).getOrDefault(item)
                }

                val bitmap: ImageBitmap? = if (!enriched.isRaw) {
                    // JPG 或 RAW+JPG（主句柄为 JPG）：仅拉取 JPG 流解码高清大图，绝不碰 RAW
                    val cacheDir = File(appContext.cacheDir, "camera_previews").apply { mkdirs() }
                    val tempFile = File(cacheDir, "prev_${System.currentTimeMillis()}.jpg")
                    try {
                        transport.exportOriginalToFile(enriched, tempFile) { _, _ -> }.getOrThrow()
                        decodeSampledBitmapFromFile(tempFile, MAX_PREVIEW_DIMENSION)
                    } finally {
                        runCatching { tempFile.delete() }
                    }
                } else {
                    // 纯 RAW（无同名 JPG）：仅分块提取 ARW 内嵌的屏幕预览/全尺寸 JPG，不拉整张 40MB RAW
                    extractEmbeddedPreviewBitmap(
                        transport = transport,
                        item = enriched,
                        maxDimension = MAX_PREVIEW_DIMENSION,
                        preferSmallThumbnail = false
                    )
                }

                val result = bitmap ?: throw IOException("无法加载高清预览图")
                if (!enriched.isSdram) {
                    largePreviewCache.put(cacheKey, result)
                }
                result
            }
        }

    override suspend fun downloadPhoto(
        item: CameraPhotoItem,
        mode: PhotoDownloadMode,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<SavedPhotoResult> = withContext(Dispatchers.IO) {
        when (mode) {
            PhotoDownloadMode.JPG_ONLY -> {
                if (item.isRaw) {
                    extractRawFullJpegInternal(item, onProgress)
                } else {
                    downloadSingleFileInternal(item, onProgress)
                }
            }
            PhotoDownloadMode.RAW_ONLY -> {
                val rawItem = resolveRawTargetItem(item)
                downloadSingleFileInternal(rawItem, onProgress)
            }
            PhotoDownloadMode.RAW_AND_JPG -> {
                runCatching {
                    val rawItem = resolveRawTargetItem(item)
                    val jpgExpected = if (item.isRaw) 3_000_000L else item.sizeBytes.coerceAtLeast(1L)
                    val rawExpected = rawItem.sizeBytes.coerceAtLeast(1L)
                    val combinedTotal = jpgExpected + rawExpected

                    // 第 1 步：保存 JPG（若为纯 RAW 则提取全尺寸 JPG）
                    val jpgSaved = if (item.isRaw) {
                        extractRawFullJpegInternal(item) { read, _ ->
                            onProgress(read.coerceAtMost(jpgExpected), combinedTotal)
                        }.getOrThrow()
                    } else {
                        downloadSingleFileInternal(item) { read, _ ->
                            onProgress(read.coerceAtMost(jpgExpected), combinedTotal)
                        }.getOrThrow()
                    }

                    // 第 2 步：保存 RAW (.ARW)
                    val rawSaved = downloadSingleFileInternal(rawItem) { read, _ ->
                        onProgress(jpgExpected + read, combinedTotal)
                    }.getOrThrow()

                    onProgress(combinedTotal, combinedTotal)
                    SavedPhotoResult(
                        filename = "${jpgSaved.filename} + ${rawSaved.filename}",
                        displayPath = "${jpgSaved.displayPath} & ${rawSaved.displayPath}",
                        sizeBytes = jpgSaved.sizeBytes + rawSaved.sizeBytes,
                        previewBitmap = jpgSaved.previewBitmap ?: rawSaved.previewBitmap,
                        isExtractedJpeg = jpgSaved.isExtractedJpeg,
                        summaryLabel = "已保存 RAW + JPG ✓"
                    )
                }
            }
        }
    }

    override fun release() {
        thumbnailCache.evictAll()
        largePreviewCache.evictAll()
        metadataCache.evictAll()
        usbTransport.release()
    }

    private fun resolveRawTargetItem(item: CameraPhotoItem): CameraPhotoItem {
        val pairedHandle = item.pairedRawHandle ?: return item
        return item.copy(
            handle = pairedHandle,
            filename = item.pairedRawFilename.orEmpty(),
            formatCode = SonyPtpIpProtocol.FORMAT_SONY_ARW,
            sizeBytes = item.pairedRawSizeBytes,
            isRaw = true,
            pairedRawHandle = null,
            pairedRawFilename = null,
            pairedRawSizeBytes = 0L
        )
    }

    private suspend fun downloadSingleFileInternal(
        item: CameraPhotoItem,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<SavedPhotoResult> {
        val transport = resolveTransport(item.transport)
        val cacheDir = File(appContext.cacheDir, "camera_downloads").apply { mkdirs() }
        var tempFile: File? = null

        return runCatching {
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
                isExtractedJpeg = false,
                summaryLabel = if (enrichedItem.isRaw) "已保存 ARW ✓" else "已保存 JPG 原图 ✓"
            )
        }.also {
            runCatching { tempFile?.delete() }
        }
    }

    private suspend fun extractRawFullJpegInternal(
        item: CameraPhotoItem,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<SavedPhotoResult> {
        val transport = resolveTransport(item.transport)
        val cacheDir = File(appContext.cacheDir, "camera_downloads").apply { mkdirs() }
        var tempRawFile: File? = null
        var tempJpgFile: File? = null

        return runCatching {
            val enrichedItem = if (item.metadataLoaded && item.filename.isNotBlank()) {
                item
            } else {
                transport.getObjectInfo(item).getOrDefault(item)
            }

            val jpgName = sanitizeFilename(enrichedItem, forceJpg = true)
            tempJpgFile = File(cacheDir, "jpg_${System.currentTimeMillis()}_$jpgName")
            val outJpg = tempJpgFile!!

            val extractedViaPartial = tryExtractFullJpegViaPartial(
                transport = transport,
                item = enrichedItem,
                destJpgFile = outJpg,
                onProgress = onProgress
            )

            val jpgSize: Long = if (extractedViaPartial > 0L) {
                extractedViaPartial
            } else {
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
                isExtractedJpeg = true,
                summaryLabel = "已提取全尺寸 JPG ✓"
            )
        }.also {
            runCatching { tempRawFile?.delete() }
            runCatching { tempJpgFile?.delete() }
        }
    }

    private suspend fun extractEmbeddedPreviewBitmap(
        transport: AndroidPtpTransport,
        item: CameraPhotoItem,
        maxDimension: Int,
        preferSmallThumbnail: Boolean
    ): ImageBitmap? {
        if (item.sizeBytes > 0L) {
            val partialBytes = runCatching {
                val reader = RemotePartialBinaryReader(item.sizeBytes) { offset, len ->
                    runBlocking { transport.readPartialObject(item, offset, len).getOrNull() }
                }
                val parsed = SonyArwParser(reader).parse(allowFallbackScan = false)
                val target = if (preferSmallThumbnail) {
                    parsed.thumbnail ?: parsed.preview
                } else {
                    parsed.preview ?: parsed.fullSize ?: parsed.thumbnail
                }
                val maxAllowed = if (preferSmallThumbnail) 2_000_000L else MAX_EXTRACTED_JPEG_BYTES
                if (target != null && target.length in 1_024L..maxAllowed) {
                    readRangeViaPartial(transport, item, target.offset, target.length.toInt())
                } else {
                    null
                }
            }.getOrNull()

            if (partialBytes != null && partialBytes.isNotEmpty()) {
                decodeSampledBitmap(partialBytes, maxDimension)?.let { return it }
            }
        }

        // 仅在点开大图且 SDRAM 不支持分块读时兜底导出解析
        if (item.isSdram) {
            val cacheDir = File(appContext.cacheDir, "camera_previews").apply { mkdirs() }
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
        const val MAX_CACHED_LARGE_PREVIEWS = 10
        const val MAX_CACHED_METADATA = 300
        const val MAX_THUMB_DIMENSION = 480
        const val MAX_PREVIEW_DIMENSION = 2048
        const val PARTIAL_STREAM_CHUNK_SIZE = 256 * 1024
        const val MAX_EXTRACTED_JPEG_BYTES = 25 * 1024 * 1024L
    }
}
