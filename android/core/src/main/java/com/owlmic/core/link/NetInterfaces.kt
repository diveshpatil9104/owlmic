package com.owlmic.core.link

import com.owlmic.core.hub.LinkKind
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** An interface we can probe on: our IPv4 address there and its broadcast address. */
data class NetIf(val name: String, val address: InetAddress, val prefixLength: Int, val broadcast: InetAddress) {
    val kind: LinkKind = linkFor(name)

    /** The phone is the access point: the PC joined the phone's hotspot. */
    val hotspot: Boolean = name.startsWith("ap") || name.startsWith("swlan")

    fun contains(other: InetAddress): Boolean = inSubnet(other.address, address.address, prefixLength)
}

/** USB tethering interfaces are rndis*, usb* or ncm*. Everything with a broadcast address is Wi-Fi, hotspots included. */
fun linkFor(interfaceName: String): LinkKind =
    if (interfaceName.startsWith("rndis") || interfaceName.startsWith("usb") || interfaceName.startsWith("ncm")) {
        LinkKind.USB_TETHERING
    } else {
        LinkKind.WIFI
    }

/** Interfaces that are up, not loopback, with an IPv4 address and a broadcast address. Mobile data has none, so it is never probed. */
fun probeInterfaces(): List<NetIf> = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nic ->
            nic.interfaceAddresses.mapNotNull { ia ->
                val broadcast = ia.broadcast ?: return@mapNotNull null
                if (ia.address !is Inet4Address) return@mapNotNull null
                NetIf(nic.name, ia.address, ia.networkPrefixLength.toInt(), broadcast)
            }
        }
}.getOrDefault(emptyList())

fun inSubnet(ip: ByteArray, network: ByteArray, prefixLength: Int): Boolean {
    if (ip.size != network.size) return false
    var bits = prefixLength
    for (i in ip.indices) {
        if (bits <= 0) return true
        val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
        if ((ip[i].toInt() and mask) != (network[i].toInt() and mask)) return false
        bits -= 8
    }
    return true
}
