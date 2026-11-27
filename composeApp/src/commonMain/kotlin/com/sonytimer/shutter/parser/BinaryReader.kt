package com.sonytimer.shutter.parser

/**
 * 跨平台随机访问二进制读取器抽象（支持 ByteArray、本地 File、以及远程 PTP PartialObject 分块读取）。
 * 移植自 fwindpeak/PhotoPicker。
 */
interface BinaryReader {
    val length: Long

    fun readByte(offset: Long): Byte

    fun readBytes(offset: Long, length: Int): ByteArray {
        if (offset < 0L || length <= 0 || offset >= this.length) return ByteArray(0)
        val validLength = minOf(length.toLong(), this.length - offset).toInt()
        val bytes = ByteArray(validLength)
        for (i in 0 until validLength) {
            bytes[i] = readByte(offset + i)
        }
        return bytes
    }

    fun readShort(offset: Long, isLittleEndian: Boolean): Short {
        val bytes = readBytes(offset, 2)
        if (bytes.size < 2) return 0
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return if (isLittleEndian) {
            ((b1 shl 8) or b0).toShort()
        } else {
            ((b0 shl 8) or b1).toShort()
        }
    }

    fun readInt(offset: Long, isLittleEndian: Boolean): Int {
        val bytes = readBytes(offset, 4)
        if (bytes.size < 4) return 0
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        return if (isLittleEndian) {
            (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }

    fun close() {}
}

class ByteArrayBinaryReader(private val data: ByteArray) : BinaryReader {
    override val length: Long get() = data.size.toLong()

    override fun readByte(offset: Long): Byte {
        if (offset < 0L || offset >= data.size) return 0
        return data[offset.toInt()]
    }

    override fun readBytes(offset: Long, length: Int): ByteArray {
        if (offset < 0L || length <= 0 || offset >= data.size) return ByteArray(0)
        val validLength = minOf(length, data.size - offset.toInt())
        val result = ByteArray(validLength)
        data.copyInto(result, 0, offset.toInt(), offset.toInt() + validLength)
        return result
    }

    override fun readShort(offset: Long, isLittleEndian: Boolean): Short {
        val off = offset.toInt()
        if (off < 0 || off + 1 >= data.size) return 0
        val b0 = data[off].toInt() and 0xFF
        val b1 = data[off + 1].toInt() and 0xFF
        return if (isLittleEndian) {
            ((b1 shl 8) or b0).toShort()
        } else {
            ((b0 shl 8) or b1).toShort()
        }
    }

    override fun readInt(offset: Long, isLittleEndian: Boolean): Int {
        val off = offset.toInt()
        if (off < 0 || off + 3 >= data.size) return 0
        val b0 = data[off].toInt() and 0xFF
        val b1 = data[off + 1].toInt() and 0xFF
        val b2 = data[off + 2].toInt() and 0xFF
        val b3 = data[off + 3].toInt() and 0xFF
        return if (isLittleEndian) {
            (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        } else {
            (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }
    }
}
