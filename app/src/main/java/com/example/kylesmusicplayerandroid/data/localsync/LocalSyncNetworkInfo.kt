package com.example.kylesmusicplayerandroid.data.localsync

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalSyncNetworkInfo {

    fun findLocalIpv4Address(): String? {
        return try {
            val addresses = NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses?.toList().orEmpty().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress.orEmpty() }
                .filter { it.isNotBlank() && !it.startsWith("127.") }
                .toList()

            addresses.firstOrNull { isPrivateLanAddress(it) } ?: addresses.firstOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    private fun isPrivateLanAddress(address: String): Boolean {
        return address.startsWith("192.168.") ||
            address.startsWith("10.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[0-1])\\.").containsMatchIn(address)
    }
}
