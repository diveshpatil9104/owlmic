package com.owlmic.core.link

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.Closeable
import java.net.InetAddress

/**
 * The Wi-Fi network, for binding sockets to it. When Wi-Fi has no internet, Android keeps mobile data as the default
 * network, and an unbound socket to a PC on the Wi-Fi would go out over mobile data. The phone's hotspot and USB
 * tethering are not networks of their own: their peers are reached without binding.
 */
class WifiNetwork(context: Context) : Closeable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    @Volatile private var current: Pair<Network, LinkProperties>? = null

    @Volatile private var onChange: () -> Unit = {}

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            current = network to properties
            onChange()
        }

        override fun onLost(network: Network) {
            if (current?.first == network) current = null
            onChange()
        }
    }

    /** Starts watching; [changed] runs on a system thread whenever the Wi-Fi network comes, goes or changes address. */
    @SuppressLint("MissingPermission") // The app's manifest declares ACCESS_NETWORK_STATE; lint checks this library alone.
    fun listen(changed: () -> Unit) {
        onChange = changed
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivity?.registerNetworkCallback(request, callback) }
    }

    /** The Wi-Fi network when [host] is on its subnet, else null for Android's normal routing. */
    fun forHost(host: InetAddress): Network? {
        val (network, properties) = current ?: return null
        return network.takeIf { properties.linkAddresses.any { inSubnet(host.address, it.address.address, it.prefixLength) } }
    }

    override fun close() {
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }
}
