package com.owlmic.core.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.owlmic.core.hub.LinkKind
import java.util.UUID

/** The Bluetooth link (section 14.1, level 4): RFCOMM to a paired PC that publishes the Owlmic service. Audio only. */
class Bluetooth(private val context: Context) {
    fun permitted(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Paired PCs that offer the service, or whose address an earlier pairing taught us. */
    @SuppressLint("MissingPermission") // permitted() checks BLUETOOTH_CONNECT; lint can't follow it.
    fun candidates(knownAddresses: Set<String>): List<Candidate> {
        if (!permitted()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try {
            if (!adapter.isEnabled) return emptyList()
            adapter.bondedDevices.orEmpty()
                .filter { d -> d.address.uppercase() in knownAddresses || d.uuids.orEmpty().any { it.uuid == SERVICE } }
                .map { d -> Candidate(pcId = null, name = d.name.orEmpty(), link = LinkKind.BLUETOOTH, btAddress = d.address) }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    /** Blocks up to the stack's own timeout (about 12 s). Close the socket from another thread to give up early. */
    @SuppressLint("MissingPermission") // Checked by permitted() first; a revoked permission throws SecurityException to the caller.
    fun connect(address: String): BluetoothSocket {
        check(permitted()) { "no Bluetooth permission" }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("no Bluetooth")
        val socket = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(SERVICE)
        socket.connect()
        return socket
    }

    companion object {
        /** "owlmic" in hex, then the version 4 marker bits. */
        val SERVICE: UUID = UUID.fromString("6f776c6d-6963-4000-8000-00805f9b34fb")
    }
}
