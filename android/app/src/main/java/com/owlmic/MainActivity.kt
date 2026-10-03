package com.owlmic

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import com.owlmic.core.hub.Feature
import com.owlmic.core.hub.FeaturePhase
import com.owlmic.core.permission.PermissionGate
import com.owlmic.core.permission.PermissionGate.Decision
import com.owlmic.hub.AppMsg
import com.owlmic.ui.MainScreen

/**
 * The main screen's host. It starts the service (discovery begins the moment the app opens) and asks for permissions
 * just in time (section 8.1): a feature's permission on its first tap, notifications after the first feature turns on,
 * Nearby devices on "Try Bluetooth".
 */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("owlmic.ui", MODE_PRIVATE) }
    private var answer: (Boolean) -> Unit = {}
    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) { answer(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.BLACK), SystemBarStyle.dark(android.graphics.Color.BLACK))
        super.onCreate(savedInstanceState)
        setContent { MainScreen(onFeature = ::tap, onTryBluetooth = ::tryBluetooth) }
    }

    override fun onStart() {
        super.onStart()
        OwlmicService.open(this)
    }

    private fun tap(feature: Feature) {
        val state = Owlmic.state.value
        if (state.feature(feature).phase == FeaturePhase.PAUSED && state.connected) {
            Owlmic.post(AppMsg.Resume(feature))
            return
        }
        if (state.feature(feature).phase != FeaturePhase.OFF) {
            OwlmicService.turnOff(this, feature)
            return
        }
        if (!state.connected) return
        when (val d = PermissionGate.decide(feature, granted(), asked(), ::shouldShowRequestPermissionRationale)) {
            Decision.Proceed -> turnOn(feature)
            is Decision.Ask -> ask(d.permission) { ok -> if (ok) turnOn(feature) else Owlmic.post(AppMsg.PermissionMissing(feature)) }
            Decision.OpenSettings -> {
                Owlmic.post(AppMsg.PermissionMissing(feature))
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
    }

    private fun turnOn(feature: Feature) {
        OwlmicService.turnOn(this, feature)
        if (PermissionGate.askNotifications(Build.VERSION.SDK_INT, granted(), asked())) ask(PermissionGate.POST_NOTIFICATIONS) {}
    }

    private fun tryBluetooth() {
        val permission = PermissionGate.bluetoothPermission(Build.VERSION.SDK_INT)
        if (permission == null || permission in granted()) {
            Owlmic.post(AppMsg.TryBluetooth)
        } else {
            ask(permission) { ok -> if (ok) Owlmic.post(AppMsg.TryBluetooth) }
        }
    }

    private fun ask(permission: String, then: (Boolean) -> Unit) {
        prefs.edit { putStringSet(ASKED, asked() + permission) }
        answer = then
        request.launch(permission)
    }

    private fun asked(): Set<String> = prefs.getStringSet(ASKED, emptySet()).orEmpty()

    private fun granted(): Set<String> = PERMISSIONS.filter { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.toSet()

    private companion object {
        const val ASKED = "asked"
        val PERMISSIONS = listOf(PermissionGate.RECORD_AUDIO, PermissionGate.CAMERA, PermissionGate.POST_NOTIFICATIONS, PermissionGate.BLUETOOTH_CONNECT)
    }
}
