package com.sonytimer.shutter.parser

/**
 * JPEG 头部标记解析器，用于直接读取嵌入 JPEG 的宽高尺寸（SOF0/SOF1/SOF2）与兜底扫描。
 * 移植自 fwindpeak/PhotoPicker。
 */
object JpegMarkerReader {

    /**
     * 直接从 JPEG 头部的 SOF0/SOF1/SOF2 段读取 (width, height)。
     */
    fun readDimensions(reader: BinaryReader, offset: Long, length: Long): Pair<Int, Int>? {
        if (length < 4 || offset + 4 > reader.length) return null

        val b0 = reader.readByte(offset).toInt() and 0xFF
        val b1 = reader.readByte(offset + 1).toInt() and 0xFF
        if (b0 != 0xFF || b1 != 0xD8) return null // Not SOI

        var pos = offset + 2
        val end = minOf(offset + length, reader.length)

        while (pos + 4 < end) {
            val marker0 = reader.readByte(pos).toInt() and 0xFF
            val marker1 = reader.readByte(pos + 1).toInt() and 0xFF

            if (marker0 != 0xFF) {
                pos++
                continue
            }

            if (marker1 == 0xFF || marker1 == 0x00) {
                pos++
                continue
            }

            // SOS (0xDA) 或 EOI (0xD9) 表示头部结束
            if (marker1 == 0xDA || marker1 == 0xD9) {
                break
            }

            val segmentLength = reader.readShort(pos + 2, isLittleEndian = false).toInt() and 0xFFFF
            if (segmentLength < 2) break

            // SOF0 (Baseline), SOF1 (Extended), SOF2 (Progressive)
            if (marker1 == 0xC0 || marker1 == 0xC1 || marker1 == 0xC2) {
                if (pos + 2 + 7 <= end) {
                    val height = reader.readShort(pos + 5, isLittleEndian = false).toInt() and 0xFFFF
                    val width = reader.readShort(pos + 7, isLittleEndian = false).toInt() and 0xFFFF
                    return Pair(width, height)
                }
            }

            pos += 2 + segmentLength
        }

        return null
    }

    /**
     * 兜底扫描二进制流中的 JPEG 段（FF D8 ... FF D9）。
     */
    fun scanJpegs(reader: BinaryReader, maxScanBytes: Long = 20_000_000L): List<EmbeddedJpegInfo> {
        val result = mutableListOf<EmbeddedJpegInfo>()
        val limit = minOf(reader.length, maxScanBytes)
        var pos = 0L

        while (pos < limit - 4) {
            val b0 = reader.readByte(pos).toInt() and 0xFF
            val b1 = reader.readByte(pos + 1).toInt() and 0xFF

            if (b0 == 0xFF && b1 == 0xD8) {
                val b2 = reader.readByte(pos + 2).toInt() and 0xFF
                if (b2 == 0xFF) {
                    val dims = readDimensions(reader, pos, limit - pos)
                    val eoi = findEoi(reader, pos + 2, minOf(pos + 25_000_000L, limit))
                    val length = if (eoi > pos) (eoi - pos + 2) else (limit - pos)

                    if (dims != null && dims.first > 0 && dims.second > 0) {
                        val w = dims.first
                        val h = dims.second
                        result.add(
                            EmbeddedJpegInfo(
                                offset = pos,
                                length = length,
                                width = w,
                                height = h,
                                isFullResolution = w >= 3000 || h >= 2000
                            )
                        )
                        pos += length
                        continue
                    }
                }
            }
            pos++
        }

        return result
    }

    private fun findEoi(reader: BinaryReader, start: Long, limit: Long): Long {
        var p = start
        while (p < limit - 1) {
            if (reader.readByte(p) == 0xFF.toByte() && reader.readByte(p + 1) == 0xD9.toByte()) {
                return p
            }
            p++
        }
        return -1L
    }
}
