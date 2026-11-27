package com.sonytimer.shutter.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SonyArwParserTest {

    @Test
    fun parse_extractsThumbnailPreviewAndFullSizeJpegFromSyntheticArw() {
        // Build a synthetic Little-Endian TIFF/ARW with:
        // - IFD0 (offset 8): 1616x1080 screen preview JPEG
        // - IFD1 (next IFD): 160x120 thumbnail JPEG
        // - SubIFD of IFD0: 7008x4672 full-size JPEG (JpgFromRaw)
        val previewJpeg = buildMinimalJpeg(width = 1616, height = 1080)
        val thumbJpeg = buildMinimalJpeg(width = 160, height = 120)
        val fullJpeg = buildMinimalJpeg(width = 7008, height = 4672)

        val ifd0Offset = 8
        val ifd0Size = 2 + 3 * 12 + 4 // 42 bytes -> ends at 50
        val ifd1Offset = 64
        val subIfdOffset = 128
        val previewOffset = 256
        val thumbOffset = previewOffset + previewJpeg.size + 16
        val fullOffset = thumbOffset + thumbJpeg.size + 16
        val totalSize = fullOffset + fullJpeg.size + 16

        val buf = ByteArray(totalSize)
        // TIFF header: "II", 42, ifd0Offset
        buf[0] = 'I'.code.toByte()
        buf[1] = 'I'.code.toByte()
        writeU16Le(buf, 2, 42)
        writeU32Le(buf, 4, ifd0Offset)

        // IFD0: 3 entries (0x0201 previewOffset, 0x0202 previewLen, 0x014A subIfdOffset), next=ifd1Offset
        writeU16Le(buf, ifd0Offset, 3)
        writeIfdEntry(buf, ifd0Offset + 2, tag = 0x0201, type = 4, count = 1, value = previewOffset)
        writeIfdEntry(buf, ifd0Offset + 14, tag = 0x0202, type = 4, count = 1, value = previewJpeg.size)
        writeIfdEntry(buf, ifd0Offset + 26, tag = 0x014A, type = 4, count = 1, value = subIfdOffset)
        writeU32Le(buf, ifd0Offset + 2 + 3 * 12, ifd1Offset)

        // IFD1: 2 entries (0x0201 thumbOffset, 0x0202 thumbLen), next=0
        writeU16Le(buf, ifd1Offset, 2)
        writeIfdEntry(buf, ifd1Offset + 2, tag = 0x0201, type = 4, count = 1, value = thumbOffset)
        writeIfdEntry(buf, ifd1Offset + 14, tag = 0x0202, type = 4, count = 1, value = thumbJpeg.size)
        writeU32Le(buf, ifd1Offset + 2 + 2 * 12, 0)

        // SubIFD: 2 entries (0x0201 fullOffset, 0x0202 fullLen), next=0
        writeU16Le(buf, subIfdOffset, 2)
        writeIfdEntry(buf, subIfdOffset + 2, tag = 0x0201, type = 4, count = 1, value = fullOffset)
        writeIfdEntry(buf, subIfdOffset + 14, tag = 0x0202, type = 4, count = 1, value = fullJpeg.size)
        writeU32Le(buf, subIfdOffset + 2 + 2 * 12, 0)

        previewJpeg.copyInto(buf, destinationOffset = previewOffset)
        thumbJpeg.copyInto(buf, destinationOffset = thumbOffset)
        fullJpeg.copyInto(buf, destinationOffset = fullOffset)

        val result = SonyArwParser(ByteArrayBinaryReader(buf)).parse()

        assertNotNull(result.thumbnail)
        assertEquals(160, result.thumbnail!!.width)
        assertEquals(120, result.thumbnail!!.height)
        assertEquals(thumbOffset.toLong(), result.thumbnail!!.offset)

        assertNotNull(result.preview)
        assertEquals(1616, result.preview!!.width)
        assertEquals(1080, result.preview!!.height)
        assertEquals(previewOffset.toLong(), result.preview!!.offset)

        assertNotNull(result.fullSize)
        assertEquals(7008, result.fullSize!!.width)
        assertEquals(4672, result.fullSize!!.height)
        assertEquals(fullOffset.toLong(), result.fullSize!!.offset)
        assertEquals(true, result.fullSize!!.isFullResolution)
    }

    private fun buildMinimalJpeg(width: Int, height: Int): ByteArray {
        // SOI (FF D8) + SOF0 (FF C0, len=11, precision=8, height, width, 1 component) + EOI (FF D9)
        return byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xC0.toByte(),
            0x00, 0x0B,
            0x08,
            ((height ushr 8) and 0xFF).toByte(), (height and 0xFF).toByte(),
            ((width ushr 8) and 0xFF).toByte(), (width and 0xFF).toByte(),
            0x01, 0x01, 0x11, 0x00,
            0xFF.toByte(), 0xD9.toByte()
        )
    }

    private fun writeIfdEntry(buf: ByteArray, offset: Int, tag: Int, type: Int, count: Int, value: Int) {
        writeU16Le(buf, offset, tag)
        writeU16Le(buf, offset + 2, type)
        writeU32Le(buf, offset + 4, count)
        writeU32Le(buf, offset + 8, value)
    }

    private fun writeU16Le(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeU32Le(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
