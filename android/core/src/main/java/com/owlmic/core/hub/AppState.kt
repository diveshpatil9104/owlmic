package com.owlmic.core.hub

/** The links in priority order; [wire] is the number the protocol uses. */
enum class LinkKind(val wire: Int) {
    USB_DEBUGGING(1),
    USB_TETHERING(2),
    WIFI(3),
    BLUETOOTH(4),
    ;

    val isCable get() = this == USB_DEBUGGING || this == USB_TETHERING
    val isWireless get() = !isCable

    companion object {
        fun of(wire: Int) = entries.firstOrNull { it.wire == wire }
    }
}

/** How long the phone has been looking without finding anything (section 7.9). */
enum class SearchStage { SEARCHING, NOT_FOUND, HELP }

/** Where the phone stands with a PC. The status tile and the indicator (section 36) render only this. */
sealed interface Connection {
    data class Searching(val stage: SearchStage = SearchStage.SEARCHING) : Connection

    data class Choosing(val count: Int) : Connection

    data class Connecting(val pc: String?, val link: LinkKind?) : Connection

    data class Approving(val pc: String, val code: String, val link: LinkKind) : Connection

    data class Connected(val pc: String, val link: LinkKind, val hotspot: Boolean = false, val weak: Boolean = false) : Connection

    /** The link dropped less than 30 s ago; the session is held (section 7.6). */
    data class Reconnecting(val pc: String, val link: LinkKind?) : Connection

    /** 30 s to 5 min after a drop: hardware released, features paused until the PC is back. */
    data class Waiting(val pc: String) : Connection

    data class Denied(val pc: String) : Connection

    data class Busy(val pc: String, val owner: String) : Connection

    /** The PC speaks another protocol version. [pc] names the PC when it is the one to update; null means this phone. */
    data class UpdateNeeded(val pc: String?) : Connection
}

enum class FeaturePhase { OFF, STARTING, ON, PAUSED, RECOVERING, FAILED }

/** Why a feature can't run, shown in its tile. */
enum class FeatureProblem { PERMISSION, NO_BLUETOOTH }

data class FeatureView(val phase: FeaturePhase = FeaturePhase.OFF, val problem: FeatureProblem? = null) {
    val isOn get() = phase == FeaturePhase.ON || phase == FeaturePhase.STARTING || phase == FeaturePhase.RECOVERING
}

enum class Feature { MIC, CAMERA, SPEAKER }

enum class SpeakerOutput { SPEAKER, HEADPHONES, BLUETOOTH }

/** A PC that answered, for the chooser. */
data class PcChoice(val id: String, val name: String)

/** A PC this phone has paired with, for Settings → PCs. */
data class KnownPcView(val id: String, val name: String, val current: Boolean)

/** One immutable snapshot of everything the UI shows (section 11.2, rule 7). */
data class AppState(
    val connection: Connection = Connection.Searching(),
    val mic: FeatureView = FeatureView(),
    val camera: FeatureView = FeatureView(),
    val speaker: FeatureView = FeatureView(),
    val speakerOutput: SpeakerOutput = SpeakerOutput.SPEAKER,
    /** The current pairing's settings, or the defaults when no PC is connected. */
    val settings: Map<String, String> = emptyMap(),
    val choices: List<PcChoice> = emptyList(),
    val knownPcs: List<KnownPcView> = emptyList(),
    val manualAddresses: List<String> = emptyList(),
    /** Show "Try Bluetooth": nothing found for 10 s and the phone has a paired PC. */
    val bluetoothOffer: Boolean = false,
    /** The phone's USB debugging prompt is waiting for Allow. */
    val adbNeedsAllow: Boolean = false,
) {
    val connected get() = connection is Connection.Connected

    fun feature(f: Feature) = when (f) {
        Feature.MIC -> mic
        Feature.CAMERA -> camera
        Feature.SPEAKER -> speaker
    }
}
