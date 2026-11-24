package com.sonytimer.shutter.protocol

/**
 * 索尼 Alpha 系列相机 BLE 遥控协议定义（适配 A7C II / ILCE-7CM2 及同协议机型）。
 *
 * 参考实现：
 * - coral/freemote: https://github.com/coral/freemote
 * - HYPOXIC Sony Camera BLE Control Interface:
 *   https://gethypoxic.com/blogs/technical/sony-camera-ble-control-protocol-di-remote-control
 */
object SonyBleProtocol {
    /** Sony Corporation Bluetooth SIG Company Identifier (0x012D) */
    const val SONY_MANUFACTURER_ID: Int = 0x012D

    /** 索尼远程控制主服务 UUID */
    const val SERVICE_REMOTE_CONTROL_UUID: String = "8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF"

    /** 写指令特征值 (0xFF01) */
    const val CHAR_REMOTE_COMMAND_UUID: String = "0000FF01-0000-1000-8000-00805F9B34FB"

    /** 相机状态通知特征值 (0xFF02) */
    const val CHAR_REMOTE_NOTIFY_UUID: String = "0000FF02-0000-1000-8000-00805F9B34FB"

    /** 标准 CCCD 描述符，用于开启 0xFF02 Notify */
    const val CCCD_UUID: String = "00002902-0000-1000-8000-00805F9B34FB"

    // 快门四步完整指令：必须成对释放，否则相机会卡在半按/不可操作状态
    val CMD_SHUTTER_HALF_DOWN: ByteArray = byteArrayOf(0x01, 0x07)
    val CMD_SHUTTER_FULL_DOWN: ByteArray = byteArrayOf(0x01, 0x09)
    val CMD_SHUTTER_FULL_UP: ByteArray = byteArrayOf(0x01, 0x08)
    val CMD_SHUTTER_HALF_UP: ByteArray = byteArrayOf(0x01, 0x06)

    // 相机 0xFF02 状态通知解析
    sealed interface CameraNotifyEvent {
        data object FocusLost : CameraNotifyEvent
        data object FocusAcquired : CameraNotifyEvent
        data object ShutterReady : CameraNotifyEvent
        data object ShutterActive : CameraNotifyEvent
        data class Unknown(val raw: ByteArray) : CameraNotifyEvent
    }

    fun parseNotify(payload: ByteArray): CameraNotifyEvent {
        if (payload.size >= 3 && payload[0] == 0x02.toByte()) {
            val type = payload[1].toInt() and 0xFF
            val state = payload[2].toInt() and 0xFF
            return when (type) {
                0x3F -> if (state == 0x20) CameraNotifyEvent.FocusAcquired else CameraNotifyEvent.FocusLost
                0xA0 -> if (state == 0x20) CameraNotifyEvent.ShutterActive else CameraNotifyEvent.ShutterReady
                else -> CameraNotifyEvent.Unknown(payload)
            }
        }
        return CameraNotifyEvent.Unknown(payload)
    }

    /**
     * 解析索尼 BLE 广播 Manufacturer Data（0x012D 后面的字节）：
     * 前两字节为 0x03 0x00 表示相机设备；包含 0x22 0xEF 0x00 表示相机当前处于配对模式。
     */
    data class SonyAdvInfo(
        val isCamera: Boolean,
        val isPairingMode: Boolean
    )

    fun parseManufacturerData(data: ByteArray?): SonyAdvInfo? {
        if (data == null || data.size < 2) return null
        val isCamera = (data[0].toInt() and 0xFF) == 0x03 && (data[1].toInt() and 0xFF) == 0x00
        if (!isCamera) return SonyAdvInfo(isCamera = false, isPairingMode = false)

        var pairing = false
        for (i in 0..(data.size - 3)) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            val b2 = data[i + 2].toInt() and 0xFF
            if (b0 == 0x22 && b1 == 0xEF && b2 == 0x00) {
                pairing = true
                break
            }
        }
        return SonyAdvInfo(isCamera = true, isPairingMode = pairing)
    }
}
