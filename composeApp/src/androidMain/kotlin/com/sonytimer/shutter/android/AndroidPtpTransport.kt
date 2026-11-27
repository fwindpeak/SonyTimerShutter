package com.sonytimer.shutter.android

import com.sonytimer.shutter.controller.CameraPhotoItem
import com.sonytimer.shutter.controller.PtpTransport
import java.io.File

/**
 * Android 端统一 PTP 传输层扩展接口：
 * - 支持分块读取（GetPartialObject / SDIO_GetPartialLargeObject / MtpDevice.getPartialObject），
 *   使得 SonyArwParser 可以直接从 40MB+ RAW 文件中仅读取必要字节来定位并提取内嵌全尺寸 JPG。
 * - 支持流式导出原图到本地文件，避免大文件一次性进入堆内存。
 */
interface AndroidPtpTransport : PtpTransport {
    suspend fun readPartialObject(
        item: CameraPhotoItem,
        offset: Long,
        maxBytes: Int
    ): Result<ByteArray>

    suspend fun exportOriginalToFile(
        item: CameraPhotoItem,
        destFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<Long>
}
