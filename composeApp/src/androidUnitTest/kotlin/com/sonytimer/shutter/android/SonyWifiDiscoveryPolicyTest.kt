package com.sonytimer.shutter.android

import com.sonytimer.shutter.protocol.SonyPtpIpProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SonyWifiDiscoveryPolicyTest {

    @Test
    fun isIgnoredInterfaceName_skipsCellularAndLoopbackButAllowsWifiAndHotspot() {
        assertTrue(SonyWifiDiscoveryPolicy.isIgnoredInterfaceName("lo"))
        assertTrue(SonyWifiDiscoveryPolicy.isIgnoredInterfaceName("rmnet_data0"))
        assertTrue(SonyWifiDiscoveryPolicy.isIgnoredInterfaceName("v4-rmnet_data1"))
        assertTrue(SonyWifiDiscoveryPolicy.isIgnoredInterfaceName("ccmni0"))
        assertTrue(SonyWifiDiscoveryPolicy.isIgnoredInterfaceName("tun0"))
        assertTrue(SonyWifiDiscoveryPolicy.isIgnoredInterfaceName(""))
        assertTrue(SonyWifiDiscoveryPolicy.isIgnoredInterfaceName(null))

        assertFalse(SonyWifiDiscoveryPolicy.isIgnoredInterfaceName("wlan0"))
        assertFalse(SonyWifiDiscoveryPolicy.isIgnoredInterfaceName("wlan1"))
        assertFalse(SonyWifiDiscoveryPolicy.isIgnoredInterfaceName("ap0"))
        assertFalse(SonyWifiDiscoveryPolicy.isIgnoredInterfaceName("swlan0"))
        assertFalse(SonyWifiDiscoveryPolicy.isIgnoredInterfaceName("softap0"))
    }

    @Test
    fun isPrivateLanIpv4_validatesPrivateRangesOnly() {
        assertTrue(SonyWifiDiscoveryPolicy.isPrivateLanIpv4("192.168.122.1"))
        assertTrue(SonyWifiDiscoveryPolicy.isPrivateLanIpv4("192.168.43.105"))
        assertTrue(SonyWifiDiscoveryPolicy.isPrivateLanIpv4("10.0.0.12"))
        assertTrue(SonyWifiDiscoveryPolicy.isPrivateLanIpv4("172.20.10.4"))

        assertFalse(SonyWifiDiscoveryPolicy.isPrivateLanIpv4("8.8.8.8"))
        assertFalse(SonyWifiDiscoveryPolicy.isPrivateLanIpv4("172.32.0.1"))
        assertFalse(SonyWifiDiscoveryPolicy.isPrivateLanIpv4("127.0.0.1"))
        assertFalse(SonyWifiDiscoveryPolicy.isPrivateLanIpv4("not-an-ip"))
        assertFalse(SonyWifiDiscoveryPolicy.isPrivateLanIpv4("192.168.1.999"))
    }

    @Test
    fun buildPriorityHosts_deduplicatesAndOrdersPreferredGatewayDefault() {
        assertEquals(
            listOf("192.168.1.55", "192.168.1.1", SonyPtpIpProtocol.DEFAULT_CAMERA_IP),
            SonyWifiDiscoveryPolicy.buildPriorityHosts(
                preferredHost = "192.168.1.55",
                gatewayIp = "192.168.1.1"
            )
        )

        assertEquals(
            listOf(SonyPtpIpProtocol.DEFAULT_CAMERA_IP),
            SonyWifiDiscoveryPolicy.buildPriorityHosts(
                preferredHost = SonyPtpIpProtocol.DEFAULT_CAMERA_IP,
                gatewayIp = SonyPtpIpProtocol.DEFAULT_CAMERA_IP
            )
        )

        assertEquals(
            listOf("192.168.43.1", SonyPtpIpProtocol.DEFAULT_CAMERA_IP),
            SonyWifiDiscoveryPolicy.buildPriorityHosts(
                preferredHost = "invalid",
                gatewayIp = "192.168.43.1"
            )
        )
    }

    @Test
    fun enumerateSubnetHosts_excludesSelfAndAlreadyProbedHosts() {
        val hosts = SonyWifiDiscoveryPolicy.enumerateSubnetHosts(
            localIp = "192.168.43.1",
            excludeHosts = setOf("192.168.43.2", "192.168.43.100")
        )
        assertEquals(251, hosts.size)
        assertFalse("192.168.43.1" in hosts)
        assertFalse("192.168.43.2" in hosts)
        assertFalse("192.168.43.100" in hosts)
        assertTrue("192.168.43.3" in hosts)
        assertTrue("192.168.43.254" in hosts)

        assertTrue(SonyWifiDiscoveryPolicy.enumerateSubnetHosts("8.8.8.8").isEmpty())
    }

    @Test
    fun extractIpFromSsdpResponse_parsesLocationHeader() {
        val response = """
            HTTP/1.1 200 OK
            CACHE-CONTROL: max-age=1800
            LOCATION: http://192.168.1.88:64321/dd.xml
            ST: urn:schemas-sony-com:service:ScalarWebAPI:1
        """.trimIndent()
        assertEquals("192.168.1.88", SonyWifiDiscoveryPolicy.extractIpFromSsdpResponse(response))

        assertNull(SonyWifiDiscoveryPolicy.extractIpFromSsdpResponse("HTTP/1.1 200 OK\r\nST: ssdp:all"))
    }
}
