package com.sonytimer.shutter.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SonyPtpIpProtocolTest {

    @Test
    fun buildInitCommandRequest_encodesHeaderGuidUtf16NameAndVersion() {
        val guid = ByteArray(16) { (it + 1).toByte() }
        val packet = SonyPtpIpProtocol.buildInitCommandRequest(guid, clientName = "Sony")

        // 8 (header) + 16 (guid) + (4+1)*2 (UTF-16LE + null) + 4 (version) = 38
        assertEquals(38, packet.size)
        val (len, type) = SonyPtpIpProtocol.parsePacketHeader(packet)
        assertEquals(38, len)
        assertEquals(SonyPtpIpProtocol.PTPIP_INIT_COMMAND_REQUEST, type)

        // GUID check
        assertContentEquals(guid, packet.copyOfRange(8, 24))

        // UTF-16LE "Sony\0"
        val expectedName = byteArrayOf(
            'S'.code.toByte(), 0,
            'o'.code.toByte(), 0,
            'n'.code.toByte(), 0,
            'y'.code.toByte(), 0,
            0, 0
        )
        assertContentEquals(expectedName, packet.copyOfRange(24, 34))

        // Version 0x00010000
        assertEquals(0x00010000, SonyPtpIpProtocol.readInt32Le(packet, 34))
    }

    @Test
    fun buildInitEventRequest_encodesConnectionId() {
        val packet = SonyPtpIpProtocol.buildInitEventRequest(connectionId = 0x12345678)
        assertEquals(12, packet.size)
        val (len, type) = SonyPtpIpProtocol.parsePacketHeader(packet)
        assertEquals(12, len)
        assertEquals(SonyPtpIpProtocol.PTPIP_INIT_EVENT_REQUEST, type)
        assertEquals(0x12345678, SonyPtpIpProtocol.readInt32Le(packet, 8))
    }

    @Test
    fun buildOperationRequest_encodesOpCodeTxIdAndParams() {
        val packet = SonyPtpIpProtocol.buildOperationRequest(
            opCode = SonyPtpIpProtocol.OP_GET_OBJECT,
            transactionId = 42,
            params = intArrayOf(SonyPtpIpProtocol.LIVEVIEW_HANDLE)
        )
        assertEquals(22, packet.size)
        val (len, type) = SonyPtpIpProtocol.parsePacketHeader(packet)
        assertEquals(22, len)
        assertEquals(SonyPtpIpProtocol.PTPIP_OPER_REQ, type)
        assertEquals(0, SonyPtpIpProtocol.readInt32Le(packet, 8)) // dataPhase = 0
        assertEquals(SonyPtpIpProtocol.OP_GET_OBJECT, SonyPtpIpProtocol.readUInt16Le(packet, 12))
        assertEquals(42, SonyPtpIpProtocol.readInt32Le(packet, 14))
        assertEquals(SonyPtpIpProtocol.LIVEVIEW_HANDLE, SonyPtpIpProtocol.readInt32Le(packet, 18))
    }

    @Test
    fun buildControlDevicePackets_encodesShutterS1DownAndS2Up() {
        val downPackets = SonyPtpIpProtocol.buildControlDevicePackets(
            controlCode = SonyPtpIpProtocol.CONTROL_SHUTTER_HALF_S1,
            transactionId = 7,
            buttonValue = SonyPtpIpProtocol.BUTTON_DOWN
        )
        assertEquals(4, downPackets.size)

        // 1. OPER_REQ with dataPhase = 1, op = 0x9207, params = [0xD2C1, 0]
        val operReq = downPackets[0]
        assertEquals(26, operReq.size)
        assertEquals(1, SonyPtpIpProtocol.readInt32Le(operReq, 8))
        assertEquals(SonyPtpIpProtocol.OP_SDIO_CONTROL_DEVICE, SonyPtpIpProtocol.readUInt16Le(operReq, 12))
        assertEquals(7, SonyPtpIpProtocol.readInt32Le(operReq, 14))
        assertEquals(SonyPtpIpProtocol.CONTROL_SHUTTER_HALF_S1, SonyPtpIpProtocol.readInt32Le(operReq, 18))
        assertEquals(0, SonyPtpIpProtocol.readInt32Le(operReq, 22))

        // 2. START_DATA (20 bytes, 8-byte LE payload size = 2)
        val startData = downPackets[1]
        assertEquals(20, startData.size)
        assertEquals(SonyPtpIpProtocol.PTPIP_START_DATA, SonyPtpIpProtocol.parsePacketHeader(startData).second)
        assertEquals(7, SonyPtpIpProtocol.readInt32Le(startData, 8))
        assertEquals(2, SonyPtpIpProtocol.readInt32Le(startData, 12))
        assertEquals(0, SonyPtpIpProtocol.readInt32Le(startData, 16))

        // 3. DATA (12 + 2 = 14 bytes, payload = 0x0002 LE)
        val dataPkt = downPackets[2]
        assertEquals(14, dataPkt.size)
        assertEquals(SonyPtpIpProtocol.PTPIP_DATA, SonyPtpIpProtocol.parsePacketHeader(dataPkt).second)
        assertEquals(SonyPtpIpProtocol.BUTTON_DOWN, SonyPtpIpProtocol.readUInt16Le(dataPkt, 12))

        // 4. END_DATA (12 bytes)
        val endPkt = downPackets[3]
        assertEquals(12, endPkt.size)
        assertEquals(SonyPtpIpProtocol.PTPIP_END_DATA, SonyPtpIpProtocol.parsePacketHeader(endPkt).second)

        // Check S2 UP payload = 0x0001
        val upPackets = SonyPtpIpProtocol.buildControlDevicePackets(
            controlCode = SonyPtpIpProtocol.CONTROL_SHUTTER_FULL_S2,
            transactionId = 8,
            buttonValue = SonyPtpIpProtocol.BUTTON_UP
        )
        assertEquals(SonyPtpIpProtocol.CONTROL_SHUTTER_FULL_S2, SonyPtpIpProtocol.readInt32Le(upPackets[0], 18))
        assertEquals(SonyPtpIpProtocol.BUTTON_UP, SonyPtpIpProtocol.readUInt16Le(upPackets[2], 12))
    }

    @Test
    fun parseInitCommandAck_andOperationResponse() {
        val body = ByteArray(20 + 18)
        SonyPtpIpProtocol.writeInt32Le(body, 0, 99)
        val name = "ILCE-7CM2"
        for (i in name.indices) {
            body[20 + i * 2] = name[i].code.toByte()
            body[20 + i * 2 + 1] = 0
        }
        val ack = SonyPtpIpProtocol.parseInitCommandAck(body)
        assertNotNull(ack)
        assertEquals(99, ack.connectionId)
        assertEquals("ILCE-7CM2", ack.cameraName)

        val respBody = ByteArray(6)
        SonyPtpIpProtocol.writeUInt16Le(respBody, 0, SonyPtpIpProtocol.RC_OK)
        SonyPtpIpProtocol.writeInt32Le(respBody, 2, 123)
        val resp = SonyPtpIpProtocol.parseOperationResponse(respBody)
        assertNotNull(resp)
        assertEquals(SonyPtpIpProtocol.RC_OK, resp.responseCode)
        assertEquals(123, resp.transactionId)
    }

    @Test
    fun extractLiveViewJpeg_handlesValidZeroFallbackAndInvalidPayloads() {
        // 1. Standard LiveView Dataset with offset=16, size=4 (JPEG SOI + EOI)
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val dataset = ByteArray(16 + jpegBytes.size)
        SonyPtpIpProtocol.writeInt32Le(dataset, 0, 16)
        SonyPtpIpProtocol.writeInt32Le(dataset, 4, jpegBytes.size)
        jpegBytes.copyInto(dataset, destinationOffset = 16)
        assertContentEquals(jpegBytes, SonyPtpIpProtocol.extractLiveViewJpeg(dataset))

        // 2. Zero image size returns null
        val zeroSize = ByteArray(16)
        SonyPtpIpProtocol.writeInt32Le(zeroSize, 0, 16)
        SonyPtpIpProtocol.writeInt32Le(zeroSize, 4, 0)
        assertNull(SonyPtpIpProtocol.extractLiveViewJpeg(zeroSize))

        // 3. Fallback SOI scan when header offset/size exceeds buffer
        val truncated = ByteArray(16 + jpegBytes.size)
        SonyPtpIpProtocol.writeInt32Le(truncated, 0, 16)
        SonyPtpIpProtocol.writeInt32Le(truncated, 4, 9999)
        jpegBytes.copyInto(truncated, destinationOffset = 16)
        assertContentEquals(jpegBytes, SonyPtpIpProtocol.extractLiveViewJpeg(truncated))

        // 4. Invalid bytes without SOI returns null
        val garbage = ByteArray(24) { 0x11 }
        assertNull(SonyPtpIpProtocol.extractLiveViewJpeg(garbage))
    }
}
