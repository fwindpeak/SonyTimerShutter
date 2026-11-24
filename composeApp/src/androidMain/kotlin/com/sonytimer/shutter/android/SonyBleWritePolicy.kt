package com.sonytimer.shutter.android

/**
 * BLE GATT 写入策略、状态码判定与错误诊断辅助（纯 Kotlin 实现，兼容 API 26+ 并支持 JVM 单元测试）。
 */
internal object SonyBleWritePolicy {

    const val PROPERTY_WRITE: Int = 0x08
    const val PROPERTY_WRITE_NO_RESPONSE: Int = 0x04

    const val WRITE_TYPE_NO_RESPONSE: Int = 1
    const val WRITE_TYPE_DEFAULT: Int = 2

    // Android 13+ (API 33+) BluetoothStatusCodes 常量值（避免在低版本触发类加载异常）
    const val STATUS_SUCCESS: Int = 0
    const val STATUS_MISSING_PERMISSION: Int = 6
    const val STATUS_WRITE_NOT_ALLOWED: Int = 200
    const val STATUS_WRITE_REQUEST_BUSY: Int = 201

    // 标准 BLE GATT 状态码
    const val GATT_WRITE_NOT_PERMITTED: Int = 3
    const val GATT_INSUFFICIENT_AUTHENTICATION: Int = 5
    const val GATT_REQUEST_NOT_SUPPORTED: Int = 6
    const val GATT_INSUFFICIENT_ENCRYPTION: Int = 15
    const val GATT_ERROR: Int = 133
    const val GATT_AUTH_FAIL: Int = 137

    sealed interface GattWriteOutcome {
        data object Success : GattWriteOutcome
        data class Failed(
            val stage: String,
            val code: Int? = null,
            val writeType: Int? = null,
            val detail: String = ""
        ) : GattWriteOutcome
    }

    /**
     * 根据特征值声明的 properties 与历史成功偏好，生成候选写入模式列表。
     * 若容器化环境（如鸿蒙卓易通）未正确透传 properties 位（值为 0），则同时保留两种写入方式以供回退。
     */
    fun resolveCandidateWriteTypes(properties: Int, preferredWriteType: Int?): List<Int> {
        val supportsDefault = (properties and PROPERTY_WRITE) != 0
        val supportsNoResponse = (properties and PROPERTY_WRITE_NO_RESPONSE) != 0

        val base = when {
            supportsDefault && supportsNoResponse -> listOf(WRITE_TYPE_DEFAULT, WRITE_TYPE_NO_RESPONSE)
            supportsDefault -> listOf(WRITE_TYPE_DEFAULT)
            supportsNoResponse -> listOf(WRITE_TYPE_NO_RESPONSE)
            else -> listOf(WRITE_TYPE_DEFAULT, WRITE_TYPE_NO_RESPONSE)
        }

        if (preferredWriteType != null && preferredWriteType in base) {
            return listOf(preferredWriteType) + base.filterNot { it == preferredWriteType }
        }
        return base
    }

    fun isAuthErrorStatus(status: Int): Boolean {
        return status == GATT_INSUFFICIENT_AUTHENTICATION ||
            status == GATT_INSUFFICIENT_ENCRYPTION ||
            status == GATT_AUTH_FAIL
    }

    fun isBusyInitFailure(isApi33Plus: Boolean, initCode: Int?): Boolean {
        return if (isApi33Plus) {
            initCode == STATUS_WRITE_REQUEST_BUSY
        } else {
            initCode == null
        }
    }

    fun shouldTryLegacyApiFallback(initCode: Int): Boolean {
        return initCode != STATUS_SUCCESS &&
            initCode != STATUS_WRITE_REQUEST_BUSY &&
            initCode != STATUS_MISSING_PERMISSION
    }

    fun shouldTryNextWriteType(outcome: GattWriteOutcome.Failed): Boolean {
        if (outcome.stage == "disconnected" || outcome.stage == "permission") {
            return false
        }
        if (outcome.stage == "callback" && outcome.code != null && isAuthErrorStatus(outcome.code)) {
            return false
        }
        return true
    }

    fun formatWriteFailureMessage(actionLabel: String, failure: GattWriteOutcome.Failed): String {
        val code = failure.code
        return when {
            code != null && isAuthErrorStatus(code) ->
                "${actionLabel}失败：蓝牙未完成配对加密(status=$code)，请在系统蓝牙取消配对后重新配对"
            failure.stage == "permission" || (failure.stage == "init" && code == STATUS_MISSING_PERMISSION) ->
                "${actionLabel}失败：缺少蓝牙连接权限，请在系统设置开启蓝牙权限"
            failure.stage == "init" && code == STATUS_WRITE_REQUEST_BUSY ->
                "${actionLabel}失败：蓝牙通道繁忙(code=201)，请稍后重试或重新连接"
            (failure.stage == "init" && code == STATUS_WRITE_NOT_ALLOWED) ||
                (failure.stage == "callback" && code == GATT_WRITE_NOT_PERMITTED) ->
                "${actionLabel}失败：相机拒绝写入(code=$code)，请确认相机已开启「蓝牙遥控」"
            failure.stage == "timeout" ->
                "${actionLabel}超时，请确认相机处于蓝牙遥控状态并重新连接"
            failure.stage == "disconnected" ->
                "${actionLabel}失败：蓝牙连接已断开"
            else -> {
                val suffix = code?.let { "=$it" }.orEmpty()
                "${actionLabel}失败(${failure.stage}$suffix)，请重新连接相机"
            }
        }
    }
}
