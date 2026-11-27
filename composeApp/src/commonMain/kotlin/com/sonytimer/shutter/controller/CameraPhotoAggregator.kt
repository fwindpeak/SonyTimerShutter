package com.sonytimer.shutter.controller

/**
 * 将同名的 RAW (.ARW/.DNG) 与 JPG/HEIF 照片合并为同一张图库卡片：
 * - 预览缩略图与点开大图一律优先走轻量 JPG 句柄，绝不为了预览而拉取 40MB+ RAW；
 * - 保留配套 RAW 句柄与大小，用户选择「下载 RAW+JPG」时再按需拉取 RAW。
 */
object CameraPhotoAggregator {

    fun mergePairedRawAndJpg(items: List<CameraPhotoItem>): List<CameraPhotoItem> {
        if (items.size <= 1) return items

        // 按文件名基名（去扩展名、大写）收集同名 RAW
        val rawByBaseName = HashMap<String, CameraPhotoItem>()
        val jpgByBaseName = HashMap<String, CameraPhotoItem>()

        for (item in items) {
            if (item.isSdram || item.filename.isBlank()) continue
            val base = baseNameOf(item.filename) ?: continue
            if (item.isRaw) {
                rawByBaseName.putIfAbsent(base, item)
            } else {
                jpgByBaseName.putIfAbsent(base, item)
            }
        }

        val consumedRawKeys = HashSet<String>()
        val result = ArrayList<CameraPhotoItem>(items.size)

        for (item in items) {
            if (item.isSdram || item.filename.isBlank()) {
                result.add(item)
                continue
            }
            val base = baseNameOf(item.filename)
            if (base == null) {
                result.add(item)
                continue
            }

            if (!item.isRaw) {
                val pairedRaw = rawByBaseName[base]
                if (pairedRaw != null && pairedRaw.transport == item.transport) {
                    consumedRawKeys.add(pairedRaw.stableKey)
                    result.add(
                        item.copy(
                            pairedRawHandle = pairedRaw.handle,
                            pairedRawFilename = pairedRaw.filename,
                            pairedRawSizeBytes = pairedRaw.sizeBytes
                        )
                    )
                } else {
                    result.add(item)
                }
            } else {
                // 若存在同名 JPG，则该 RAW 已被合并到 JPG 卡片中，跳过单独展示
                val pairedJpg = jpgByBaseName[base]
                if (pairedJpg != null && pairedJpg.transport == item.transport) {
                    consumedRawKeys.add(item.stableKey)
                } else {
                    result.add(item)
                }
            }
        }
        return result
    }

    fun baseNameOf(filename: String): String? {
        val clean = filename.trim()
        if (clean.isEmpty()) return null
        val dot = clean.lastIndexOf('.')
        val base = if (dot > 0) clean.substring(0, dot) else clean
        return base.uppercase().takeIf { it.isNotEmpty() }
    }
}
