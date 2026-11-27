package com.sonytimer.shutter.parser

/**
 * 索尼 ARW / RAW 文件中内嵌 JPEG 的位置与尺寸信息。
 * 移植自 fwindpeak/PhotoPicker。
 */
data class EmbeddedJpegInfo(
    val offset: Long,
    val length: Long,
    val width: Int = 0,
    val height: Int = 0,
    val isFullResolution: Boolean = false
)

/**
 * 索尼 ARW (TIFF 结构) RAW 文件高性能解析器：
 * 无需解拜耳即可直接定位内嵌的缩略图（160×120）、屏幕预览图（1616×1080）与全尺寸 JPG（如 7008×4672）。
 */
class SonyArwParser(private val reader: BinaryReader) {

    data class ParseResult(
        val thumbnail: EmbeddedJpegInfo?,
        val preview: EmbeddedJpegInfo?,
        val fullSize: EmbeddedJpegInfo?
    ) {
        val bestPreview: EmbeddedJpegInfo?
            get() = fullSize ?: preview ?: thumbnail

        val bestThumbnail: EmbeddedJpegInfo?
            get() = preview ?: thumbnail ?: fullSize
    }

    fun parse(allowFallbackScan: Boolean = true): ParseResult {
        if (reader.length < 8) return ParseResult(null, null, null)

        val b0 = reader.readByte(0).toInt() and 0xFF
        val b1 = reader.readByte(1).toInt() and 0xFF
        val isLittleEndian = when {
            b0 == 0x49 && b1 == 0x49 -> true  // "II" (Little-Endian)
            b0 == 0x4D && b1 == 0x4D -> false // "MM" (Big-Endian)
            else -> return if (allowFallbackScan) fallbackScan() else ParseResult(null, null, null)
        }

        val magic = reader.readShort(2, isLittleEndian).toInt() and 0xFFFF
        if (magic != 42 && magic != 0x55) {
            return if (allowFallbackScan) fallbackScan() else ParseResult(null, null, null)
        }

        val ifd0Offset = reader.readInt(4, isLittleEndian).toLong() and 0xFFFFFFFFL
        if (ifd0Offset <= 0 || ifd0Offset >= reader.length) {
            return if (allowFallbackScan) fallbackScan() else ParseResult(null, null, null)
        }

        val discoveredJpegs = mutableListOf<EmbeddedJpegInfo>()
        val visitedOffsets = mutableSetOf<Long>()

        parseIfd(ifd0Offset, isLittleEndian, discoveredJpegs, visitedOffsets, depth = 0)

        if (discoveredJpegs.isEmpty()) {
            return if (allowFallbackScan) fallbackScan() else ParseResult(null, null, null)
        }

        return categorize(discoveredJpegs)
    }

    private fun parseIfd(
        offset: Long,
        isLittleEndian: Boolean,
        outList: MutableList<EmbeddedJpegInfo>,
        visitedOffsets: MutableSet<Long>,
        depth: Int
    ) {
        if (depth > 10 || offset + 2 > reader.length || !visitedOffsets.add(offset)) return

        val numEntries = reader.readShort(offset, isLittleEndian).toInt() and 0xFFFF
        if (numEntries > 500) return

        var jpegOffset = 0L
        var jpegLength = 0L
        val subIfdOffsets = mutableListOf<Long>()
        var exifIfdOffset = 0L

        var current = offset + 2
        for (i in 0 until numEntries) {
            if (current + 12 > reader.length) break

            val tag = reader.readShort(current, isLittleEndian).toInt() and 0xFFFF
            val count = reader.readInt(current + 4, isLittleEndian).toLong() and 0xFFFFFFFFL
            val valueOrOffset = reader.readInt(current + 8, isLittleEndian).toLong() and 0xFFFFFFFFL

            when (tag) {
                0x0201 -> jpegOffset = valueOrOffset // JPEGInterchangeFormat
                0x0202 -> jpegLength = valueOrOffset // JPEGInterchangeFormatLength
                0x014A -> { // SubIFDs
                    if (count == 1L) {
                        subIfdOffsets.add(valueOrOffset)
                    } else if (count in 2..20 && valueOrOffset + count * 4 <= reader.length) {
                        for (c in 0 until count.toInt()) {
                            val subOff = reader.readInt(valueOrOffset + c * 4, isLittleEndian).toLong() and 0xFFFFFFFFL
                            subIfdOffsets.add(subOff)
                        }
                    }
                }
                0x8769 -> exifIfdOffset = valueOrOffset // ExifIFD
            }

            current += 12
        }

        // 仅提取 JPEGInterchangeFormat (0x0201)，不碰 StripOffsets (0x0111) 的传感器原始拜耳数据
        if (jpegOffset > 0 && jpegOffset < reader.length) {
            if (reader.readByte(jpegOffset) == 0xFF.toByte() && reader.readByte(jpegOffset + 1) == 0xD8.toByte()) {
                val actualLength = if (jpegLength > 0 && jpegOffset + jpegLength <= reader.length) {
                    jpegLength
                } else {
                    minOf(reader.length - jpegOffset, 50_000_000L)
                }

                val dims = JpegMarkerReader.readDimensions(reader, jpegOffset, actualLength)
                if (dims != null && dims.first > 0 && dims.second > 0) {
                    val w = dims.first
                    val h = dims.second
                    outList.add(
                        EmbeddedJpegInfo(
                            offset = jpegOffset,
                            length = actualLength,
                            width = w,
                            height = h,
                            isFullResolution = w >= 3000 || h >= 2000
                        )
                    )
                }
            }
        }

        for (subOffset in subIfdOffsets) {
            parseIfd(subOffset, isLittleEndian, outList, visitedOffsets, depth + 1)
        }

        if (exifIfdOffset > 0 && exifIfdOffset < reader.length) {
            parseExifIfd(exifIfdOffset, isLittleEndian, outList, depth + 1)
        }

        if (current + 4 <= reader.length) {
            val nextIfd = reader.readInt(current, isLittleEndian).toLong() and 0xFFFFFFFFL
            if (nextIfd > 0 && nextIfd < reader.length) {
                parseIfd(nextIfd, isLittleEndian, outList, visitedOffsets, depth + 1)
            }
        }
    }

    private fun parseExifIfd(
        offset: Long,
        isLittleEndian: Boolean,
        outList: MutableList<EmbeddedJpegInfo>,
        depth: Int
    ) {
        if (depth > 5 || offset + 2 > reader.length) return
        val numEntries = reader.readShort(offset, isLittleEndian).toInt() and 0xFFFF
        if (numEntries > 300) return

        var current = offset + 2
        for (i in 0 until numEntries) {
            if (current + 12 > reader.length) break
            val tag = reader.readShort(current, isLittleEndian).toInt() and 0xFFFF
            val count = reader.readInt(current + 4, isLittleEndian).toLong() and 0xFFFFFFFFL
            val valueOrOffset = reader.readInt(current + 8, isLittleEndian).toLong() and 0xFFFFFFFFL

            // 0x927C: MakerNote
            if (tag == 0x927C && count > 10 && valueOrOffset + count <= reader.length) {
                parseSonyMakerNote(valueOrOffset, isLittleEndian, count, outList)
            }
            current += 12
        }
    }

    private fun parseSonyMakerNote(
        offset: Long,
        isLittleEndian: Boolean,
        length: Long,
        outList: MutableList<EmbeddedJpegInfo>
    ) {
        if (offset + 2 > reader.length) return
        val entries = reader.readShort(offset, isLittleEndian).toInt() and 0xFFFF
        if (entries > 200) return

        var current = offset + 2
        for (i in 0 until entries) {
            if (current + 12 > offset + length || current + 12 > reader.length) break
            val tag = reader.readShort(current, isLittleEndian).toInt() and 0xFFFF
            val count = reader.readInt(current + 4, isLittleEndian).toLong() and 0xFFFFFFFFL
            val valOffset = reader.readInt(current + 8, isLittleEndian).toLong() and 0xFFFFFFFFL

            // Tag 0x2001: Sony Preview Image
            if (tag == 0x2001 && count > 1000) {
                val absoluteOffset = if (valOffset < offset) offset + valOffset else valOffset
                if (absoluteOffset + count <= reader.length) {
                    if (reader.readByte(absoluteOffset) == 0xFF.toByte() &&
                        reader.readByte(absoluteOffset + 1) == 0xD8.toByte()
                    ) {
                        val dims = JpegMarkerReader.readDimensions(reader, absoluteOffset, count)
                        if (dims != null && dims.first > 0 && dims.second > 0) {
                            val w = dims.first
                            val h = dims.second
                            outList.add(
                                EmbeddedJpegInfo(
                                    offset = absoluteOffset,
                                    length = count,
                                    width = w,
                                    height = h,
                                    isFullResolution = w >= 3000 || h >= 2000
                                )
                            )
                        }
                    }
                }
            }
            current += 12
        }
    }

    private fun categorize(jpegs: List<EmbeddedJpegInfo>): ParseResult {
        if (jpegs.isEmpty()) return ParseResult(null, null, null)

        val unique = jpegs.distinctBy { it.offset }

        val thumbCandidates = unique.filter { maxOf(it.width, it.height) in 1..320 }
        val thumb = thumbCandidates.minByOrNull { it.width.toLong() * it.height }

        val nonThumbs = unique.filter { maxOf(it.width, it.height) > 320 }

        val fullCandidates = nonThumbs.filter {
            it.isFullResolution || maxOf(it.width, it.height) >= 3000 || (it.width.toLong() * it.height >= 5_000_000L)
        }
        val fullSize = fullCandidates.maxByOrNull { it.width.toLong() * it.height }

        val previewCandidates = nonThumbs.filter { maxOf(it.width, it.height) in 321 until 3000 }
        val preview = previewCandidates.maxByOrNull { it.width.toLong() * it.height }
            ?: fullSize
            ?: thumb
            ?: unique.maxByOrNull { it.length }

        return ParseResult(
            thumbnail = thumb ?: preview,
            preview = preview,
            fullSize = fullSize ?: preview
        )
    }

    private fun fallbackScan(): ParseResult {
        val jpegs = JpegMarkerReader.scanJpegs(reader)
        return categorize(jpegs)
    }
}
