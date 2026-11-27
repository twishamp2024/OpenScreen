package io.github.openscreen.core.net

/** Pure IPv4 helpers for the "is the PC on my network?" check (no Android dependencies). */
object Subnets {
    fun parse(ip: String): Int? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var value = 0
        for (p in parts) {
            val octet = p.toIntOrNull() ?: return null
            if (octet !in 0..255 || p.isEmpty() || (p.length > 1 && p[0] == '0')) return null
            value = (value shl 8) or octet
        }
        return value
    }

    fun sameSubnet(localIp: String, prefixLength: Int, remoteIp: String): Boolean {
        val a = parse(localIp) ?: return false
        val b = parse(remoteIp) ?: return false
        if (prefixLength !in 0..32) return false
        val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
        return (a and mask) == (b and mask)
    }

    /** Returns the first of [hosts] that shares a subnet with the local Wi-Fi address, if any. */
    fun firstReachable(hosts: List<String>, localIp: String?, prefixLength: Int?): String? {
        if (localIp == null || prefixLength == null) return null
        return hosts.firstOrNull { sameSubnet(localIp, prefixLength, it) }
    }

    /** Orders [hosts] so same-subnet addresses are tried first, preserving the QR order otherwise. */
    fun prioritize(hosts: List<String>, localIp: String?, prefixLength: Int?): List<String> {
        if (localIp == null || prefixLength == null) return hosts
        val (same, other) = hosts.partition { sameSubnet(localIp, prefixLength, it) }
        return same + other
    }
}
