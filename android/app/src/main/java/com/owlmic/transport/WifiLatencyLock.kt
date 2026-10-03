package com.owlmic.transport

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/** Keeps Wi-Fi out of power save while we stream over it, so latency doesn't spike. */
class WifiLatencyLock(context: Context) {
    private val lock = context.getSystemService(WifiManager::class.java)?.createWifiLock(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        },
        "owlmic",
    )

    fun hold() {
        lock?.acquire()
    }

    fun release() {
        if (lock?.isHeld == true) lock.release()
    }
}
