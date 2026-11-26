package com.sonytimer.shutter.android

import com.sonytimer.shutter.controller.CameraPhotoItem
import com.sonytimer.shutter.controller.PtpTransport
import java.io.File

/**
 * Android 端统一 PTP 传输层扩展接口：
 * 在 commonMain [PtpTransport] 基础上补充流式导出原图到本地文件，
 * 避免 20MB+ JPG 或 60MB+ ARW 原图一次性加载进 JVM 堆导致 OOM。
 */
interface AndroidPtpTransport : PtpTransport {
    suspend fun exportOriginalToFile(
        item: CameraPhotoItem,
        destFile: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<Long>
}
