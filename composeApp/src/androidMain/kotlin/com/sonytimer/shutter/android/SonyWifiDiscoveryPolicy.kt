package com.sonytimer.shutter.android

import com.sonytimer.shutter.protocol.SonyPtpIpProtocol

/**
 * 索尼相机局域网 / 热点自动发现策略（纯逻辑，便于单元测试）。
 *
 * 负责筛选有效局域网接口、生成优先级探测列表、枚举 /24 子网主机，
 * 以及从 SSDP 响应中提取候选相机 IP。
 */
object SonyWifiDiscoveryPolicy {

    private val IGNORED_INTERFACE_PREFIXES = listOf(
        "lo",
        "rmnet",
        "v4-rmnet",
        "ccmni",
        "pdp",
        "tun",
        "tap",
        "ppp",
        "dummy",
        "clat",
        "sit",
        "ip6tnl"
    )

    private val LOCATION_IP_REGEX = Regex(
        pattern = """(?im)^LOCATION\s*:\s*https?://(\d{1,3}(?:\.\d{1,3}){3})(?::\d+)?(?:/|\s|$)"""
    )

    fun isIgnoredInterfaceName(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val lower = name.lowercase()
        return IGNORED_INTERFACE_PREFIXES.any { lower.startsWith(it) }
    }

    fun isValidIpv4(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        val parts = ip.trim().split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            if (part.isEmpty() || part.length > 3) return false
            val num = part.toIntOrNull() ?: return false
            num in 0..255
        }
    }

    fun isPrivateLanIpv4(ip: String): Boolean {
        if (!isValidIpv4(ip)) return false
        val octets = ip.trim().split('.').map { it.toInt() }
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            else -> false
        }
    }

    fun subnetPrefix24(ip: String): String? {
        if (!isValidIpv4(ip)) return null
        val lastDot = ip.trim().lastIndexOf('.')
        if (lastDot <= 0) return null
        return ip.trim().substring(0, lastDot)
    }

    /**
     * 优先探测的候选相机 IP：
     * 1. 上次成功连接的 IP（若有效）
     * 2. 当前 Wi-Fi 网关 IP（相机 Wi-Fi Direct 热点模式通常是网关）
     * 3. 索尼默认直连 IP (192.168.122.1)
     */
    fun buildPriorityHosts(
        preferredHost: String?,
        gatewayIp: String?
    ): List<String> {
        val result = LinkedHashSet<String>()
        val trimmedPreferred = preferredHost?.trim()
        if (isValidIpv4(trimmedPreferred)) {
            result.add(trimmedPreferred!!)
        }
        val trimmedGateway = gatewayIp?.trim()
        if (isValidIpv4(trimmedGateway)) {
            result.add(trimmedGateway!!)
        }
        result.add(SonyPtpIpProtocol.DEFAULT_CAMERA_IP)
        return result.toList()
    }

    /**
     * 枚举本机所在 /24 私网段内除本机与已探测地址之外的所有主机 IP。
     */
    fun enumerateSubnetHosts(
        localIp: String,
        excludeHosts: Set<String> = emptySet()
    ): List<String> {
        val trimmedLocal = localIp.trim()
        if (!isPrivateLanIpv4(trimmedLocal)) return emptyList()
        val prefix = subnetPrefix24(trimmedLocal) ?: return emptyList()

        val hosts = ArrayList<String>(254)
        for (lastOctet in 1..254) {
            val candidate = "$prefix.$lastOctet"
            if (candidate != trimmedLocal && candidate !in excludeHosts) {
                hosts.add(candidate)
            }
        }
        return hosts
    }

    fun extractIpFromSsdpResponse(responseText: String): String? {
        val match = LOCATION_IP_REGEX.find(responseText) ?: return null
        val ip = match.groupValues.getOrNull(1) ?: return null
        return ip.takeIf { isValidIpv4(it) }
    }
}
