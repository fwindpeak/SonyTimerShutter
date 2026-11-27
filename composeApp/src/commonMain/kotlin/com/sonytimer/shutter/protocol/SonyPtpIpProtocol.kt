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
    const val OP_CLOSE_SESSION: Int = 0x1003
    const val OP_GET_STORAGE_IDS: Int = 0x1004
    const val OP_GET_OBJECT_HANDLES: Int = 0x1007
    const val OP_GET_OBJECT_INFO: Int = 0x1008
    const val OP_GET_OBJECT: Int = 0x1009
    const val OP_GET_THUMB: Int = 0x100A
    const val OP_GET_PARTIAL_OBJECT: Int = 0x101B
    const val OP_SDIO_CONNECT: Int = 0x9201
    const val OP_SDIO_GET_EXT_DEVICE_INFO: Int = 0x9202
    const val OP_SDIO_CONTROL_DEVICE: Int = 0x9207
    const val OP_SDIO_OPEN_SESSION: Int = 0x9210
    const val OP_SDIO_GET_PARTIAL_LARGE_OBJECT: Int = 0x9211
    const val OP_SDIO_SET_CONTENTS_TRANSFER_MODE: Int = 0x9212

    // Sony SDIO_OpenSession FunctionMode values
    const val FUNCTION_MODE_REMOTE: Int = 0x0
    const val FUNCTION_MODE_CONTENTS_TRANSFER: Int = 0x1
    const val FUNCTION_MODE_REMOTE_AND_CONTENTS_TRANSFER: Int = 0x2

    // Sony SDIO_SetContentsTransferMode parameters
    const val CONTENTS_SELECT_CAMERA: Int = 0x1
    const val CONTENTS_SELECT_HOST: Int = 0x2
    const val TRANSFER_MODE_DISABLE: Int = 0x0
    const val TRANSFER_MODE_ENABLE: Int = 0x1

    const val SDIO_PROTOCOL_VERSION: Int = 0x012C
    const val SDRAM_IMAGE_HANDLE: Int = 0xFFFFC001.toInt()
    const val LIVEVIEW_HANDLE: Int = 0xFFFFC002.toInt()
    const val STORAGE_ALL: Int = 0xFFFFFFFF.toInt()
    const val STORAGE_REMOTE_DUMMY: Int = 0x00010000
    const val STORAGE_MEDIA_1: Int = 0x00010001
    const val STORAGE_MEDIA_2: Int = 0x00010002
    const val ASSOCIATION_ALL: Int = 0x00000000
    const val ASSOCIATION_ROOT: Int = 0xFFFFFFFF.toInt()

    // PTP Object Format Codes
    const val FORMAT_ALL: Int = 0x0000
    const val FORMAT_ASSOCIATION: Int = 0x3001
    const val FORMAT_EXIF_JPEG: Int = 0x3801
    const val FORMAT_JFIF: Int = 0x3808
    const val FORMAT_DNG: Int = 0x3811
    const val FORMAT_HEIF: Int = 0x3812
    const val FORMAT_SONY_ARW: Int = 0xB101

    // PTP Event Codes
    const val EVENT_OBJECT_ADDED: Int = 0x4002
    const val EVENT_OBJECT_REMOVED: Int = 0x4003
    const val EVENT_STORE_ADDED: Int = 0x4004
    const val EVENT_STORE_REMOVED: Int = 0x4005
    const val EVENT_SONY_OBJECT_ADDED_IN_SDRAM: Int = 0xC201
    const val EVENT_SONY_OBJECT_REMOVED_IN_SDRAM: Int = 0xC202

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

    class PtpEventPacket(
        val eventCode: Int,
        val transactionId: Int,
        val params: IntArray
    ) {
        val param1: Int? get() = params.getOrNull(0)
        val param2: Int? get() = params.getOrNull(1)
        val param3: Int? get() = params.getOrNull(2)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PtpEventPacket) return false
            return eventCode == other.eventCode &&
                transactionId == other.transactionId &&
                params.contentEquals(other.params)
        }

        override fun hashCode(): Int {
            var result = eventCode
            result = 31 * result + transactionId
            result = 31 * result + params.contentHashCode()
            return result
        }
    }

    data class PtpObjectInfo(
        val storageId: Int,
        val objectFormat: Int,
        val compressedSize: Long,
        val thumbFormat: Int,
        val thumbCompressedSize: Int,
        val thumbPixWidth: Int,
        val thumbPixHeight: Int,
        val imagePixWidth: Int,
        val imagePixHeight: Int,
        val filename: String,
        val captureDate: String
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
     * 解析 PTP/IP Event 数据包载荷（PTPIP_EVENT = 0x0008）：
     * - Bytes 0..1: EventCode (UINT16 LE)
     * - Bytes 2..5: TransactionID (UINT32 LE)
     * - Bytes 6.. : 0~3 个 UINT32 LE 参数（如 ObjectHandle）
     */
    fun parseEventPacket(body: ByteArray): PtpEventPacket? {
        if (body.size < 6) return null
        val eventCode = readUInt16Le(body, 0)
        val transactionId = readInt32Le(body, 2)
        val paramCount = ((body.size - 6) / 4).coerceAtLeast(0)
        val params = IntArray(paramCount) { idx ->
            readInt32Le(body, 6 + idx * 4)
        }
        return PtpEventPacket(
            eventCode = eventCode,
            transactionId = transactionId,
            params = params
        )
    }

    /**
     * 解析标准 PTP UINT32 数组（用于 GetStorageIDs / GetObjectHandles）：
     * - Bytes 0..3: 元素数量 count (UINT32 LE)
     * - Bytes 4.. : count 个 UINT32 LE 元素
     */
    fun parseUInt32Array(data: ByteArray): IntArray {
        if (data.size < 4) return intArrayOf()
        val countLong = readUInt32Le(data, 0)
        val maxPossible = (data.size - 4) / 4
        val count = countLong.coerceAtMost(maxPossible.toLong()).toInt().coerceAtLeast(0)
        return IntArray(count) { idx ->
            readInt32Le(data, 4 + idx * 4)
        }
    }

    /**
     * 解析标准 ISO 15740 PTP ObjectInfo 数据集（GetObjectInfo 0x1008）。
     */
    fun parseObjectInfo(data: ByteArray): PtpObjectInfo? {
        if (data.size < 52) return null
        val storageId = readInt32Le(data, 0)
        val objectFormat = readUInt16Le(data, 4)
        val compressedSize = readUInt32Le(data, 8)
        val thumbFormat = readUInt16Le(data, 12)
        val thumbCompressedSize = readInt32Le(data, 14)
        val thumbPixWidth = readInt32Le(data, 18)
        val thumbPixHeight = readInt32Le(data, 22)
        val imagePixWidth = readInt32Le(data, 26)
        val imagePixHeight = readInt32Le(data, 30)

        var offset = 52
        val (filename, nextAfterName) = readPtpString(data, offset)
        offset = nextAfterName
        val (captureDate, _) = readPtpString(data, offset)

        return PtpObjectInfo(
            storageId = storageId,
            objectFormat = objectFormat,
            compressedSize = compressedSize,
            thumbFormat = thumbFormat,
            thumbCompressedSize = thumbCompressedSize,
            thumbPixWidth = thumbPixWidth,
            thumbPixHeight = thumbPixHeight,
            imagePixWidth = imagePixWidth,
            imagePixHeight = imagePixHeight,
            filename = filename,
            captureDate = captureDate
        )
    }

    /**
     * 读取标准 PTP String（1 字节字符数 numChars 含末尾 NUL + numChars 个 UTF-16LE 字符）。
     */
    fun readPtpString(bytes: ByteArray, offset: Int): Pair<String, Int> {
        if (offset >= bytes.size) return "" to bytes.size
        val numChars = bytes[offset].toInt() and 0xFF
        val start = offset + 1
        if (numChars == 0) return "" to start
        val availableChars = ((bytes.size - start) / 2).coerceAtLeast(0)
        val readChars = numChars.coerceAtMost(availableChars)
        val sb = StringBuilder(readChars)
        for (i in 0 until readChars) {
            val pos = start + i * 2
            val ch = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
            if (ch == 0) break
            sb.append(ch.toChar())
        }
        val nextOffset = (start + readChars * 2).coerceAtMost(bytes.size)
        return sb.toString() to nextOffset
    }

    fun isJpegFormat(formatCode: Int, filename: String = ""): Boolean {
        if (formatCode == FORMAT_EXIF_JPEG || formatCode == FORMAT_JFIF) return true
        val lower = filename.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
    }

    fun isRawFormat(formatCode: Int, filename: String = ""): Boolean {
        if (formatCode == FORMAT_SONY_ARW || formatCode == FORMAT_DNG) return true
        val lower = filename.lowercase()
        return lower.endsWith(".arw") || lower.endsWith(".dng") || lower.endsWith(".raw")
    }

    fun isImageFormat(formatCode: Int, filename: String = ""): Boolean {
        if (isJpegFormat(formatCode, filename) || isRawFormat(formatCode, filename)) return true
        if (formatCode == FORMAT_HEIF) return true
        val lower = filename.lowercase()
        return lower.endsWith(".hif") || lower.endsWith(".heif") || lower.endsWith(".heic")
    }

    fun formatCaptureDate(raw: String): String {
        val clean = raw.trim()
        if (clean.length < 15) return clean
        // PTP standard: YYYYMMDDTHHMMSS
        if (clean[8] == 'T') {
            val year = clean.substring(0, 4)
            val month = clean.substring(4, 6)
            val day = clean.substring(6, 8)
            val hour = clean.substring(9, 11)
            val min = clean.substring(11, 13)
            val sec = clean.substring(13, 15)
            return "$year-$month-$day $hour:$min:$sec"
        }
        return clean
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "--"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return "${kb.toInt()} KB"
        val mb = kb / 1024.0
        val rounded = ((mb * 10.0).toInt()) / 10.0
        return "$rounded MB"
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
