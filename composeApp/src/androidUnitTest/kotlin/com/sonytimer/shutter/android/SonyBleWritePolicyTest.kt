package com.sonytimer.shutter.android

import com.sonytimer.shutter.android.SonyBleWritePolicy.GATT_AUTH_FAIL
import com.sonytimer.shutter.android.SonyBleWritePolicy.GATT_ERROR
import com.sonytimer.shutter.android.SonyBleWritePolicy.GATT_INSUFFICIENT_AUTHENTICATION
import com.sonytimer.shutter.android.SonyBleWritePolicy.GATT_INSUFFICIENT_ENCRYPTION
import com.sonytimer.shutter.android.SonyBleWritePolicy.GATT_REQUEST_NOT_SUPPORTED
import com.sonytimer.shutter.android.SonyBleWritePolicy.GATT_WRITE_NOT_PERMITTED
import com.sonytimer.shutter.android.SonyBleWritePolicy.GattWriteOutcome
import com.sonytimer.shutter.android.SonyBleWritePolicy.PROPERTY_WRITE
import com.sonytimer.shutter.android.SonyBleWritePolicy.PROPERTY_WRITE_NO_RESPONSE
import com.sonytimer.shutter.android.SonyBleWritePolicy.STATUS_MISSING_PERMISSION
import com.sonytimer.shutter.android.SonyBleWritePolicy.STATUS_SUCCESS
import com.sonytimer.shutter.android.SonyBleWritePolicy.STATUS_WRITE_NOT_ALLOWED
import com.sonytimer.shutter.android.SonyBleWritePolicy.STATUS_WRITE_REQUEST_BUSY
import com.sonytimer.shutter.android.SonyBleWritePolicy.WRITE_TYPE_DEFAULT
import com.sonytimer.shutter.android.SonyBleWritePolicy.WRITE_TYPE_NO_RESPONSE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SonyBleWritePolicyTest {

    @Test
    fun resolveCandidateWriteTypes_respectsPropertiesAndPreference() {
        assertEquals(
            listOf(WRITE_TYPE_DEFAULT),
            SonyBleWritePolicy.resolveCandidateWriteTypes(PROPERTY_WRITE, null)
        )
        assertEquals(
            listOf(WRITE_TYPE_NO_RESPONSE),
            SonyBleWritePolicy.resolveCandidateWriteTypes(PROPERTY_WRITE_NO_RESPONSE, WRITE_TYPE_DEFAULT)
        )
        assertEquals(
            listOf(WRITE_TYPE_DEFAULT, WRITE_TYPE_NO_RESPONSE),
            SonyBleWritePolicy.resolveCandidateWriteTypes(PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE, null)
        )
        assertEquals(
            listOf(WRITE_TYPE_NO_RESPONSE, WRITE_TYPE_DEFAULT),
            SonyBleWritePolicy.resolveCandidateWriteTypes(
                PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE,
                WRITE_TYPE_NO_RESPONSE
            )
        )
        // 容器未透传 properties (0) 时，保留两种模式以支持降级尝试
        assertEquals(
            listOf(WRITE_TYPE_DEFAULT, WRITE_TYPE_NO_RESPONSE),
            SonyBleWritePolicy.resolveCandidateWriteTypes(0, null)
        )
        assertEquals(
            listOf(WRITE_TYPE_NO_RESPONSE, WRITE_TYPE_DEFAULT),
            SonyBleWritePolicy.resolveCandidateWriteTypes(0, WRITE_TYPE_NO_RESPONSE)
        )
    }

    @Test
    fun authBusyAndFallbackPredicates_behaveAsExpected() {
        assertTrue(SonyBleWritePolicy.isAuthErrorStatus(GATT_INSUFFICIENT_AUTHENTICATION))
        assertTrue(SonyBleWritePolicy.isAuthErrorStatus(GATT_INSUFFICIENT_ENCRYPTION))
        assertTrue(SonyBleWritePolicy.isAuthErrorStatus(GATT_AUTH_FAIL))
        assertFalse(SonyBleWritePolicy.isAuthErrorStatus(GATT_ERROR))

        assertTrue(SonyBleWritePolicy.isBusyInitFailure(isApi33Plus = true, initCode = STATUS_WRITE_REQUEST_BUSY))
        assertFalse(SonyBleWritePolicy.isBusyInitFailure(isApi33Plus = true, initCode = STATUS_WRITE_NOT_ALLOWED))
        assertTrue(SonyBleWritePolicy.isBusyInitFailure(isApi33Plus = false, initCode = null))

        assertFalse(SonyBleWritePolicy.shouldTryLegacyApiFallback(STATUS_SUCCESS))
        assertFalse(SonyBleWritePolicy.shouldTryLegacyApiFallback(STATUS_WRITE_REQUEST_BUSY))
        assertFalse(SonyBleWritePolicy.shouldTryLegacyApiFallback(STATUS_MISSING_PERMISSION))
        assertTrue(SonyBleWritePolicy.shouldTryLegacyApiFallback(STATUS_WRITE_NOT_ALLOWED))

        assertFalse(SonyBleWritePolicy.shouldTryNextWriteType(GattWriteOutcome.Failed("disconnected")))
        assertFalse(SonyBleWritePolicy.shouldTryNextWriteType(GattWriteOutcome.Failed("permission")))
        assertFalse(
            SonyBleWritePolicy.shouldTryNextWriteType(
                GattWriteOutcome.Failed("callback", GATT_INSUFFICIENT_AUTHENTICATION)
            )
        )
        assertTrue(
            SonyBleWritePolicy.shouldTryNextWriteType(
                GattWriteOutcome.Failed("init", STATUS_WRITE_NOT_ALLOWED)
            )
        )
        assertTrue(
            SonyBleWritePolicy.shouldTryNextWriteType(
                GattWriteOutcome.Failed("callback", GATT_REQUEST_NOT_SUPPORTED)
            )
        )
        assertTrue(SonyBleWritePolicy.shouldTryNextWriteType(GattWriteOutcome.Failed("timeout")))
    }

    @Test
    fun formatWriteFailureMessage_producesSpecificDiagnostics() {
        val label = "半按对焦指令写入"

        val authMsg = SonyBleWritePolicy.formatWriteFailureMessage(
            label,
            GattWriteOutcome.Failed("callback", GATT_INSUFFICIENT_ENCRYPTION)
        )
        assertTrue(authMsg.contains("配对加密") && authMsg.contains("15"))

        val busyMsg = SonyBleWritePolicy.formatWriteFailureMessage(
            label,
            GattWriteOutcome.Failed("init", STATUS_WRITE_REQUEST_BUSY)
        )
        assertTrue(busyMsg.contains("201") && busyMsg.contains("繁忙"))

        val notAllowedMsg = SonyBleWritePolicy.formatWriteFailureMessage(
            label,
            GattWriteOutcome.Failed("init", STATUS_WRITE_NOT_ALLOWED)
        )
        assertTrue(notAllowedMsg.contains("200") && notAllowedMsg.contains("蓝牙遥控"))

        val notPermittedMsg = SonyBleWritePolicy.formatWriteFailureMessage(
            label,
            GattWriteOutcome.Failed("callback", GATT_WRITE_NOT_PERMITTED)
        )
        assertTrue(notPermittedMsg.contains("3") && notPermittedMsg.contains("蓝牙遥控"))

        val permMsg = SonyBleWritePolicy.formatWriteFailureMessage(
            label,
            GattWriteOutcome.Failed("permission", STATUS_MISSING_PERMISSION)
        )
        assertTrue(permMsg.contains("权限"))

        val timeoutMsg = SonyBleWritePolicy.formatWriteFailureMessage(
            label,
            GattWriteOutcome.Failed("timeout")
        )
        assertTrue(timeoutMsg.contains("超时"))

        val disconnectMsg = SonyBleWritePolicy.formatWriteFailureMessage(
            label,
            GattWriteOutcome.Failed("disconnected")
        )
        assertTrue(disconnectMsg.contains("断开"))

        val genericMsg = SonyBleWritePolicy.formatWriteFailureMessage(
            label,
            GattWriteOutcome.Failed("callback", GATT_ERROR)
        )
        assertTrue(genericMsg.contains("callback=133"))
    }
}
