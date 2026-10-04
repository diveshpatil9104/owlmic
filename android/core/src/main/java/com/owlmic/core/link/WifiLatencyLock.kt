package com.owlmic.core.link

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Keeps Wi-Fi out of power save while a feature streams over it, so latency doesn't spike. Not reference counted:
 * [hold] and [release] may be called on every state change, and one [release] always lets go.
 */
class WifiLatencyLock(context: Context) {
    private val lock = context.getSystemService(WifiManager::class.java)?.createWifiLock(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        },
        "owlmic",
    )?.apply { setReferenceCounted(false) }

    fun hold() {
        lock?.acquire()
    }

    fun release() {
        if (lock?.isHeld == true) lock.release()
    }
}
