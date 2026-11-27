package com.sonytimer.shutter.controller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraPhotoAggregatorTest {

    @Test
    fun mergePairedRawAndJpg_mergesMatchingNamesIntoSingleJpgFirstCard() {
        val items = listOf(
            CameraPhotoItem(
                handle = 101,
                transport = PtpTransportMode.USB_MTP,
                filename = "DSC00422.ARW",
                sizeBytes = 38_000_000L,
                isRaw = true,
                metadataLoaded = true
            ),
            CameraPhotoItem(
                handle = 102,
                transport = PtpTransportMode.USB_MTP,
                filename = "DSC00422.JPG",
                sizeBytes = 8_500_000L,
                isRaw = false,
                metadataLoaded = true
            ),
            CameraPhotoItem(
                handle = 103,
                transport = PtpTransportMode.USB_MTP,
                filename = "DSC00421.ARW",
                sizeBytes = 41_000_000L,
                isRaw = true,
                metadataLoaded = true
            ),
            CameraPhotoItem(
                handle = 104,
                transport = PtpTransportMode.USB_MTP,
                filename = "DSC00420.JPG",
                sizeBytes = 7_200_000L,
                isRaw = false,
                metadataLoaded = true
            )
        )

        val merged = CameraPhotoAggregator.mergePairedRawAndJpg(items)
        assertEquals(3, merged.size)

        // DSC00422 merged into JPG primary card with paired RAW metadata
        val paired = merged[0]
        assertEquals(102, paired.handle)
        assertEquals("DSC00422.JPG", paired.filename)
        assertFalse(paired.isRaw)
        assertTrue(paired.hasPairedRaw)
        assertEquals(101, paired.pairedRawHandle)
        assertEquals("DSC00422.ARW", paired.pairedRawFilename)
        assertEquals(38_000_000L, paired.pairedRawSizeBytes)

        // DSC00421 is RAW-only -> kept intact
        val rawOnly = merged[1]
        assertEquals(103, rawOnly.handle)
        assertTrue(rawOnly.isRaw)
        assertFalse(rawOnly.hasPairedRaw)
        assertNull(rawOnly.pairedRawHandle)

        // DSC00420 is JPG-only -> no download choices needed
        val jpgOnly = merged[2]
        assertEquals(104, jpgOnly.handle)
        assertFalse(jpgOnly.isRaw)
        assertFalse(jpgOnly.hasDownloadChoices)
    }
}
