package com.sonytimer.shutter.protocol

/**
 * 索尼 Alpha 系列相机 Camera Control PTP 3.0 over IP (PTP/IP) 协议编解码。
 *
 * 参考 Sony Camera Control PTP 3.0 规范及开源社区验证实现 (jakkuh/sony-alpha-python):
 * - 默认端口: TCP 15740 (开启「访问认证」时需通过 SSH 22 端口隧道转发本地至 127.0.0.1:15740)
 * - 实时取景对象句柄: 0xFFFFC002
 * - 快门控制 (SDIO_ControlDevice 0x9207):
 *   - 0xD2C1: S1 半按对焦测光
 *   - 0xD2C2: S2 全按快门释放
 *   - 按钮状态载荷 (UINT16 LE): 0x0002 = Down (按下), 0x0001 = Up (松开)
 */
object SonyPtpIpProtocol {
    const val DEFAULT_PORT: Int = 15740
    const val SSH_PORT: Int = 22
    const val DEFAULT_CAMERA_IP: String = "192.168.122.1"

    // PTP/IP Packet Types
    const val PTPIP_INIT_COMMAND_REQUEST: Int = 0x00000001
    const val PTPIP_INIT_COMMAND_ACK: Int = 0x00000002
    const val PTPIP_INIT_EVENT_REQUEST: Int = 0x00000003
    const val PTPIP_INIT_EVENT_ACK: Int = 0x00000004
    const val PTPIP_INIT_FAIL: Int = 0x00000005
    const val PTPIP_OPER_REQ: Int = 0x00000006
    const val PTPIP_OPER_RESP: Int = 0x00000007
    const val PTPIP_EVENT: Int = 0x00000008
    const val PTPIP_START_DATA: Int = 0x00000009
    const val PTPIP_DATA: Int = 0x0000000A
    const val PTPIP_CANCEL: Int = 0x0000000B
    const val PTPIP_END_DATA: Int = 0x0000000C
    const val PTPIP_PROBE_REQ: Int = 0x0000000D
    const val PTPIP_PROBE_RESP: Int = 0x0000000E

    // PTP Operation Codes
    const val OP_GET_DEVICE_INFO: Int = 0x1001
    const val OP_OPEN_SESSION: Int = 0x1002
    const val OP_GET_STORAGE_IDS: Int = 0x1004
    const val OP_GET_OBJECT_INFO: Int = 0x1008
    const val OP_GET_OBJECT: Int = 0x1009
    const val OP_SDIO_CONNECT: Int = 0x9201
    const val OP_SDIO_GET_EXT_DEVICE_INFO: Int = 0x9202
    const val OP_SDIO_CONTROL_DEVICE: Int = 0x9207

    const val SDIO_PROTOCOL_VERSION: Int = 0x012C
    const val LIVEVIEW_HANDLE: Int = 0xFFFFC002.toInt()

    // Shutter Control Codes & Button Values
    const val CONTROL_SHUTTER_HALF_S1: Int = 0xD2C1
    const val CONTROL_SHUTTER_FULL_S2: Int = 0xD2C2
    const val BUTTON_UP: Int = 0x0001
    const val BUTTON_DOWN: Int = 0x0002

    // PTP Response Codes
    const val RC_OK: Int = 0x2001
    const val RC_INVALID_OBJECT_HANDLE: Int = 0x2009
    const val RC_ACCESS_DENIED: Int = 0x200F
    const val RC_SESSION_ALREADY_OPEN_SONY: Int = 0x2013
    const val RC_DEVICE_BUSY: Int = 0x2019
    const val RC_SESSION_ALREADY_OPEN_STD: Int = 0x201E

    private const val PROTOCOL_VERSION_1_0: Int = 0x00010000

    class PtpIpPacket(val type: Int, val body: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PtpIpPacket) return false
            return type == other.type && body.contentEquals(other.body)
        }

        override fun hashCode(): Int = 31 * type + body.contentHashCode()
    }

    data class InitCommandAck(
        val connectionId: Int,
        val cameraName: String?
    )

    data class OperationResponse(
        val responseCode: Int,
        val transactionId: Int
    )

    fun buildInitCommandRequest(
        guid: ByteArray,
        clientName: String = "SonyTimerShutter"
    ): ByteArray {
        require(guid.size == 16) { "PTP/IP client GUID must be 16 bytes" }
        val nameBytes = encodeUtf16LeNullTerminated(clientName)
        val totalLength = 8 + 16 + nameBytes.size + 4
        val packet = ByteArray(totalLength)
        writeInt32Le(packet, 0, totalLength)
        writeInt32Le(packet, 4, PTPIP_INIT_COMMAND_REQUEST)
        guid.copyInto(packet, destinationOffset = 8)
        nameBytes.copyInto(packet, destinationOffset = 24)
        writeInt32Le(packet, 24 + nameBytes.size, PROTOCOL_VERSION_1_0)
        return packet
    }

    fun buildInitEventRequest(connectionId: Int): ByteArray {
        val packet = ByteArray(12)
        writeInt32Le(packet, 0, 12)
        writeInt32Le(packet, 4, PTPIP_INIT_EVENT_REQUEST)
        writeInt32Le(packet, 8, connectionId)
        return packet
    }

    fun buildOperationRequest(
        opCode: Int,
        transactionId: Int,
        params: IntArray = intArrayOf(),
        dataPhase: Int = 0
    ): ByteArray {
        val totalLength = 18 + params.size * 4
        val packet = ByteArray(totalLength)
        writeInt32Le(packet, 0, totalLength)
        writeInt32Le(packet, 4, PTPIP_OPER_REQ)
        writeInt32Le(packet, 8, dataPhase)
        writeUInt16Le(packet, 12, opCode)
        writeInt32Le(packet, 14, transactionId)
        for (i in params.indices) {
            writeInt32Le(packet, 18 + i * 4, params[i])
        }
        return packet
    }

    fun buildOperationWithDataPackets(
        opCode: Int,
        transactionId: Int,
        params: IntArray,
        payload: ByteArray
    ): List<ByteArray> {
        val operReq = buildOperationRequest(
            opCode = opCode,
            transactionId = transactionId,
            params = params,
            dataPhase = 1
        )

        val startData = ByteArray(20)
        writeInt32Le(startData, 0, 20)
        writeInt32Le(startData, 4, PTPIP_START_DATA)
        writeInt32Le(startData, 8, transactionId)
        writeInt64Le(startData, 12, payload.size.toLong())

        val dataPacket = ByteArray(12 + payload.size)
        writeInt32Le(dataPacket, 0, dataPacket.size)
        writeInt32Le(dataPacket, 4, PTPIP_DATA)
        writeInt32Le(dataPacket, 8, transactionId)
        payload.copyInto(dataPacket, destinationOffset = 12)

        val endData = ByteArray(12)
        writeInt32Le(endData, 0, 12)
        writeInt32Le(endData, 4, PTPIP_END_DATA)
        writeInt32Le(endData, 8, transactionId)

        return listOf(operReq, startData, dataPacket, endData)
    }

    fun buildControlDevicePackets(
        controlCode: Int,
        transactionId: Int,
        buttonValue: Int
    ): List<ByteArray> {
        val payload = ByteArray(2)
        writeUInt16Le(payload, 0, buttonValue)
        return buildOperationWithDataPackets(
            opCode = OP_SDIO_CONTROL_DEVICE,
            transactionId = transactionId,
            params = intArrayOf(controlCode, 0),
            payload = payload
        )
    }

    fun buildProbeRequest(): ByteArray {
        val packet = ByteArray(8)
        writeInt32Le(packet, 0, 8)
        writeInt32Le(packet, 4, PTPIP_PROBE_REQ)
        return packet
    }

    fun buildProbeResponse(): ByteArray {
        val packet = ByteArray(8)
        writeInt32Le(packet, 0, 8)
        writeInt32Le(packet, 4, PTPIP_PROBE_RESP)
        return packet
    }

    fun parsePacketHeader(header8Bytes: ByteArray): Pair<Int, Int> {
        require(header8Bytes.size >= 8) { "PTP/IP header requires at least 8 bytes" }
        val length = readInt32Le(header8Bytes, 0)
        val packetType = readInt32Le(header8Bytes, 4)
        return length to packetType
    }

    fun parseInitCommandAck(body: ByteArray): InitCommandAck? {
        if (body.size < 4) return null
        val connectionId = readInt32Le(body, 0)
        val cameraName = if (body.size > 20) {
            decodeUtf16LeNullTerminated(body, offset = 20)
        } else {
            null
        }
        return InitCommandAck(connectionId = connectionId, cameraName = cameraName)
    }

    fun parseOperationResponse(body: ByteArray): OperationResponse? {
        if (body.size < 6) return null
        val responseCode = readUInt16Le(body, 0)
        val transactionId = readInt32Le(body, 2)
        return OperationResponse(responseCode = responseCode, transactionId = transactionId)
    }

    /**
     * 从 Sony LiveView Dataset 中提取纯 JPEG 帧数据：
     * - Bytes 0..3: LiveView Image Offset (UINT32 LE)
     * - Bytes 4..7: LiveView Image Size (UINT32 LE)
     */
    fun extractLiveViewJpeg(dataset: ByteArray): ByteArray? {
        if (dataset.size < 8) return null
        val imgOffset = readUInt32Le(dataset, 0)
        val imgSize = readUInt32Le(dataset, 4)
        if (imgSize == 0L) return null

        val endOffset = imgOffset + imgSize
        if (imgOffset >= 0L && endOffset <= dataset.size.toLong()) {
            val start = imgOffset.toInt()
            val end = endOffset.toInt()
            if (end - start >= 2 &&
                (dataset[start].toInt() and 0xFF) == 0xFF &&
                (dataset[start + 1].toInt() and 0xFF) == 0xD8
            ) {
                return dataset.copyOfRange(start, end)
            }
        }

        // 回退：搜索 JPEG SOI (0xFF 0xD8)
        for (i in 0 until dataset.size - 1) {
            if ((dataset[i].toInt() and 0xFF) == 0xFF &&
                (dataset[i + 1].toInt() and 0xFF) == 0xD8
            ) {
                return dataset.copyOfRange(i, dataset.size)
            }
        }
        return null
    }

    fun readInt32Le(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun readUInt32Le(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }

    fun readUInt16Le(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun writeUInt16Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    fun writeInt32Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    fun writeInt64Le(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            bytes[offset + i] = ((value ushr (i * 8)) and 0xFFL).toByte()
        }
    }

    fun encodeGuidHex(guid: ByteArray): String {
        require(guid.size == 16) { "PTP/IP client GUID must be 16 bytes" }
        val hexChars = "0123456789abcdef"
        val out = CharArray(32)
        for (i in 0 until 16) {
            val v = guid[i].toInt() and 0xFF
            out[i * 2] = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0x0F]
        }
        return out.concatToString()
    }

    fun decodeGuidHex(hex: String?): ByteArray? {
        if (hex == null || hex.length != 32) return null
        val out = ByteArray(16)
        for (i in 0 until 16) {
            val hi = hexDigit(hex[i * 2])
            val lo = hexDigit(hex[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun encodeUtf16LeNullTerminated(text: String): ByteArray {
        val out = ByteArray((text.length + 1) * 2)
        for (i in text.indices) {
            val codeUnit = text[i].code
            out[i * 2] = (codeUnit and 0xFF).toByte()
            out[i * 2 + 1] = ((codeUnit ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun decodeUtf16LeNullTerminated(bytes: ByteArray, offset: Int): String? {
        if (offset >= bytes.size - 1) return null
        val sb = StringBuilder()
        var i = offset
        while (i + 1 < bytes.size) {
            val ch = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            if (ch == 0) break
            sb.append(ch.toChar())
            i += 2
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }
}
