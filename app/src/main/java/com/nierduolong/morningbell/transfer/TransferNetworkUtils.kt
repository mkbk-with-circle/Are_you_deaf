package com.nierduolong.morningbell.transfer

import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections

object TransferNetworkUtils {
    data class Address(val ip: String, val interfaceName: String, val score: Int)

    fun addresses(): List<Address> {
        val output = mutableListOf<Address>()
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { networkInterface ->
                val name = networkInterface.name.orEmpty()
                val lower = name.lowercase()
                if (!networkInterface.isUp || networkInterface.isLoopback || SKIP_INTERFACES.any(lower::contains)) return@forEach
                Collections.list(networkInterface.inetAddresses).forEach { address ->
                    if (address !is Inet4Address || address.isLoopbackAddress || address.isLinkLocalAddress) return@forEach
                    val ip = address.hostAddress ?: return@forEach
                    if (!address.isSiteLocalAddress) return@forEach
                    var score = 0
                    if (HOTSPOT_INTERFACES.any { lower == it || lower.startsWith(it) }) score += 100
                    if (lower.contains("ap") || lower.contains("softap")) score += 70
                    if (ip.endsWith(".1")) score += 35
                    if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.137.") || ip.startsWith("172.20.10.")) score += 30
                    if (lower == "wlan0" && !ip.endsWith(".1")) score -= 10
                    output += Address(ip, name, score)
                }
            }
        }
        return output.distinctBy(Address::ip).sortedByDescending(Address::score)
    }

    fun shareUrls(port: Int, sharePath: String): List<String> =
        addresses().take(MAX_SHOWN_ADDRESSES).map { "http://${it.ip}:$port$sharePath" }

    fun fingerprint(): String = addresses().joinToString("|") { "${it.interfaceName}=${it.ip}" }

    fun newAccessToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun wifiQrPayload(ssid: String, passphrase: String): String =
        "WIFI:T:WPA;S:${wifiEscape(ssid)};P:${wifiEscape(passphrase)};H:false;;"

    private fun wifiEscape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            if (char in listOf('\\', ';', ',', ':', '"')) append('\\')
            append(char)
        }
    }

    private val HOTSPOT_INTERFACES = listOf("ap0", "softap0", "swlan0", "wlan1", "wlan2", "p2p0")
    private val SKIP_INTERFACES = listOf("rmnet", "ccmni", "dummy", "lo", "sit", "ip6", "tun", "wg", "vpn")
    private const val MAX_SHOWN_ADDRESSES = 5
}
