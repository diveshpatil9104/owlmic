package com.owlmic.core.permission

import com.owlmic.core.hub.Feature

/**
 * Just-in-time permissions (section 8.1): asked the first time a feature needs them, never earlier. Pure decisions;
 * the activity does the asking. Permission and state are separate: granting never turns a feature on by itself.
 */
object PermissionGate {
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    const val CAMERA = "android.permission.CAMERA"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"

    sealed interface Decision {
        data object Proceed : Decision

        data class Ask(val permission: String) : Decision

        /** Android won't show the prompt again: the tile offers the system settings instead. */
        data object OpenSettings : Decision
    }

    fun permissionFor(feature: Feature): String? = when (feature) {
        Feature.MIC -> RECORD_AUDIO
        Feature.CAMERA -> CAMERA
        Feature.SPEAKER -> null
    }

    /**
     * [askedBefore]: this permission was requested at least once. [showRationale] is Android's shouldShowRequestPermissionRationale,
     * which is false both before the first ask and after "don't ask again".
     */
    fun decide(feature: Feature, granted: Set<String>, askedBefore: Set<String>, showRationale: (String) -> Boolean): Decision {
        val permission = permissionFor(feature) ?: return Decision.Proceed
        return when {
            permission in granted -> Decision.Proceed
            permission in askedBefore && !showRationale(permission) -> Decision.OpenSettings
            else -> Decision.Ask(permission)
        }
    }

    /** Notifications are asked once, right after the first feature turns on (Android 13+). */
    fun askNotifications(sdk: Int, granted: Set<String>, askedBefore: Set<String>): Boolean =
        sdk >= 33 && POST_NOTIFICATIONS !in granted && POST_NOTIFICATIONS !in askedBefore

    /** Bluetooth needs a runtime permission only on Android 12+, asked when the user taps "Try Bluetooth". */
    fun bluetoothPermission(sdk: Int): String? = if (sdk >= 31) BLUETOOTH_CONNECT else null
}
