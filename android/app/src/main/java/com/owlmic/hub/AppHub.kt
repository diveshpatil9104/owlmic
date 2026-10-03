package com.owlmic.hub

import android.content.Context
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.provider.Settings.Global
import com.owlmic.Owlmic
import com.owlmic.core.hub.AppState
import com.owlmic.core.hub.Connection
import com.owlmic.core.hub.Feature
import com.owlmic.core.hub.FeaturePhase
import com.owlmic.core.hub.FeatureProblem
import com.owlmic.core.hub.FeatureView
import com.owlmic.core.hub.Health
import com.owlmic.core.hub.Hub
import com.owlmic.core.hub.KnownPcView
import com.owlmic.core.hub.LinkKind
import com.owlmic.core.hub.SpeakerOutput
import com.owlmic.core.link.Bluetooth
import com.owlmic.core.link.LinkEvent
import com.owlmic.core.link.LinkHub
import com.owlmic.core.link.LinkMsg
import com.owlmic.core.link.MediaRouter
import com.owlmic.core.proto.FeatureState
import com.owlmic.core.proto.KeyframeRequest
import com.owlmic.core.proto.Report
import com.owlmic.core.proto.State
import com.owlmic.core.proto.Stream
import com.owlmic.core.proto.StreamStart
import com.owlmic.core.proto.StreamStop
import com.owlmic.core.settings.KeystoreWrapper
import com.owlmic.core.settings.SettingValues
import com.owlmic.core.settings.SettingsEvent
import com.owlmic.core.settings.SettingsHub
import com.owlmic.core.settings.SettingsMsg
import com.owlmic.core.settings.Store
import com.owlmic.media.MediaEvent
import com.owlmic.media.MediaHub
import com.owlmic.media.MediaMsg
import com.owlmic.media.audio.MicCodec
import com.owlmic.media.audio.MicConfig
import com.owlmic.media.audio.SpeakerFormat
import com.owlmic.media.camera.CameraConfig
import com.owlmic.media.camera.Lens
import com.owlmic.media.camera.Orientation
import com.owlmic.media.camera.VideoPlan
import org.json.JSONObject
import java.io.File
import com.owlmic.core.proto.Settings as SettingsMessage

sealed interface AppMsg {
    /** Turn a feature on or off. The service has already made itself foreground with the right types. */
    class SetFeature(val feature: Feature, val on: Boolean) : AppMsg

    /** The permission a feature needs was refused: its tile says so (section 8.1). */
    class PermissionMissing(val feature: Feature) : AppMsg

    /** The notification's Pause mic or Resume mic. */
    data object ToggleMicPause : AppMsg

    /** A tap on a paused tile: the user takes back what the PC paused. */
    class Resume(val feature: Feature) : AppMsg

    data object StopAll : AppMsg

    data object FlipLens : AppMsg

    class Setting(val id: String, val value: String) : AppMsg

    class ChoosePc(val id: String) : AppMsg

    data object AskAgain : AppMsg

    class AddAddress(val address: String) : AppMsg

    class RemoveAddress(val address: String) : AppMsg

    class Forget(val id: String) : AppMsg

    data object TryBluetooth : AppMsg

    data object Opened : AppMsg

    class Output(val output: SpeakerOutput) : AppMsg

    class Thermal(val status: Int) : AppMsg

    class FromLink(val event: LinkEvent) : AppMsg

    class FromSettings(val event: SettingsEvent) : AppMsg

    class FromMedia(val event: MediaEvent) : AppMsg
}

/**
 * The App Hub (section 11.1): owns the Link, Settings and Media hubs, turns their events into one [AppState], and
 * carries out the feature rules: what turns on when, what pauses during a drop and what comes back (section 7.6).
 */
class AppHub(context: Context, private val onState: (AppState) -> Unit) : Hub<AppMsg>("app") {
    private val store = Store(File(context.filesDir, "owlmic.json"), KeystoreWrapper())
    private val router = MediaRouter()
    private var thermal: Int? = null

    private val link = LinkHub(
        store, router, Bluetooth(context),
        phoneName = { phoneName(context) },
        model = Build.MODEL,
        sdk = Build.VERSION.SDK_INT,
        thermal = { thermal },
        emit = { post(AppMsg.FromLink(it)) },
    )
    private val settings = SettingsHub(store) { post(AppMsg.FromSettings(it)) }
    val media = MediaHub(context, router, Owlmic.mutableLevel) { post(AppMsg.FromMedia(it)) }

    private var state = AppState(
        settings = SettingValues.plain(SettingValues.defaults()),
        manualAddresses = store.addresses(),
    )
    private var linkKind: LinkKind? = null
    private var pcId: String? = null
    private var waiting = false
    private var micPausedByPc = false
    private var micPausedByUser = false
    private var cameraPausedByPc = false
    private var speakerPausedByPc = false
    private var speakerFormat: SpeakerFormat? = null
    private var cameraFormat: Triple<Int, Int, Int>? = null

    init {
        knownChanged()
        publish()
    }

    override fun handle(message: AppMsg) {
        when (message) {
            is AppMsg.SetFeature -> setFeature(message.feature, message.on)
            is AppMsg.PermissionMissing -> set(message.feature, FeatureView(FeaturePhase.OFF, FeatureProblem.PERMISSION))
            AppMsg.ToggleMicPause -> when {
                state.mic.phase == FeaturePhase.PAUSED && state.connected -> resume(Feature.MIC)
                state.mic.isOn -> {
                    micPausedByUser = true
                    applyMicPause()
                    sendState()
                }
            }
            is AppMsg.Resume -> if (state.connected) resume(message.feature)
            AppMsg.StopAll -> Feature.entries.forEach { if (state.feature(it).phase != FeaturePhase.OFF) setFeature(it, false) }
            AppMsg.FlipLens -> settings.post(SettingsMsg.Change("camera.lens", if (state.settings["camera.lens"] == "front") "back" else "front"))
            is AppMsg.Setting -> settings.post(SettingsMsg.Change(message.id, message.value))
            is AppMsg.ChoosePc -> link.post(LinkMsg.Choose(message.id))
            AppMsg.AskAgain -> link.post(LinkMsg.AskAgain)
            is AppMsg.AddAddress -> {
                store.addAddress(message.address)
                state = state.copy(manualAddresses = store.addresses())
                link.post(LinkMsg.Opened)
            }
            is AppMsg.RemoveAddress -> {
                store.removeAddress(message.address)
                state = state.copy(manualAddresses = store.addresses())
            }
            is AppMsg.Forget -> link.post(LinkMsg.Forget(message.id))
            AppMsg.TryBluetooth -> link.post(LinkMsg.TryBluetooth)
            AppMsg.Opened -> link.post(LinkMsg.Opened)
            is AppMsg.Output -> state = state.copy(speakerOutput = message.output)
            is AppMsg.Thermal -> thermal = message.status
            is AppMsg.FromLink -> fromLink(message.event)
            is AppMsg.FromSettings -> fromSettings(message.event)
            is AppMsg.FromMedia -> fromMedia(message.event)
        }
        publish()
    }

    // Features.

    private fun setFeature(f: Feature, on: Boolean) {
        if (on) {
            if (!state.connected || state.feature(f).isOn) return
            when (f) {
                Feature.MIC -> {
                    set(f, FeatureView(FeaturePhase.STARTING))
                    startMic()
                    if (state.speaker.isOn) startSpeaker()
                }
                Feature.CAMERA -> {
                    if (linkKind == LinkKind.BLUETOOTH) {
                        set(f, FeatureView(FeaturePhase.OFF, FeatureProblem.NO_BLUETOOTH))
                        return
                    }
                    set(f, FeatureView(FeaturePhase.STARTING))
                    media.post(MediaMsg.Camera(cameraConfig()))
                }
                Feature.SPEAKER -> {
                    set(f, FeatureView(FeaturePhase.STARTING))
                    link.post(LinkMsg.SpeakerExpected(true))
                    startSpeaker()
                    if (state.mic.isOn) startMic()
                }
            }
        } else {
            set(f, FeatureView())
            when (f) {
                Feature.MIC -> {
                    micPausedByPc = false
                    micPausedByUser = false
                    media.post(MediaMsg.Mic(null))
                    link.post(LinkMsg.Send(StreamStop(Stream.MIC)))
                    if (state.speaker.isOn) startSpeaker()
                }
                Feature.CAMERA -> {
                    cameraPausedByPc = false
                    media.post(MediaMsg.Camera(null))
                    cameraFormat = null
                    link.post(LinkMsg.Send(StreamStop(Stream.CAMERA)))
                }
                Feature.SPEAKER -> {
                    speakerPausedByPc = false
                    link.post(LinkMsg.SpeakerExpected(false))
                    media.post(MediaMsg.Speaker(null, false, true))
                }
            }
        }
        sendState()
    }

    /** Whoever paused it, the user's resume wins, and the PC hears "on" again. */
    private fun resume(f: Feature) {
        when (f) {
            Feature.MIC -> {
                micPausedByPc = false
                micPausedByUser = false
                applyMicPause()
            }
            Feature.CAMERA -> {
                cameraPausedByPc = false
                media.post(MediaMsg.CameraPaused(false))
            }
            Feature.SPEAKER -> speakerPausedByPc = false
        }
        if (state.feature(f).phase == FeaturePhase.PAUSED) set(f, FeatureView(FeaturePhase.ON))
        sendState()
    }

    private fun micConfig() = MicConfig(
        codec = MicCodec.forLink(linkKind ?: LinkKind.WIFI),
        voiceCommunication = state.speaker.isOn,
        noiseReduction = state.settings["mic.noiseReduction"] != "off",
        boostDb = when (state.settings["mic.boost"]) {
            "plus6" -> 6
            "plus12" -> 12
            else -> 0
        },
    )

    private fun startMic() {
        val c = micConfig()
        media.post(MediaMsg.Mic(c))
        // Without a platform noise suppressor the PC runs its own when the setting is "phone".
        val params = JSONObject().put("sampleRate", 48_000).put("channels", 1).put("frameMs", c.codec.frameMs)
            .put("noiseSuppressor", NoiseSuppressor.isAvailable())
        link.post(LinkMsg.Send(StreamStart(Stream.MIC, c.codec.wire, params)))
    }

    private fun startSpeaker() {
        val f = speakerFormat ?: return
        media.post(MediaMsg.Speaker(f, state.mic.isOn, linkKind?.isWireless != false))
    }

    private fun cameraConfig(): CameraConfig {
        val s = state.settings
        val fps = s["camera.frameRate"]?.toIntOrNull() ?: 24
        return CameraConfig(
            lens = if (s["camera.lens"] == "front") Lens.FRONT else Lens.BACK,
            plan = VideoPlan.of(s["camera.quality"] ?: "auto", fps, linkKind ?: LinkKind.WIFI),
            orientation = when (s["camera.orientation"]) {
                "landscape" -> Orientation.LANDSCAPE
                "portrait" -> Orientation.PORTRAIT
                else -> Orientation.AUTO
            },
        )
    }

    /** The caller tells the PC. */
    private fun applyMicPause() {
        val paused = micPausedByPc || micPausedByUser
        media.post(MediaMsg.MicPaused(paused))
        if (state.mic.phase != FeaturePhase.OFF) set(Feature.MIC, FeatureView(if (paused) FeaturePhase.PAUSED else FeaturePhase.ON))
    }

    private fun set(f: Feature, v: FeatureView) {
        state = when (f) {
            Feature.MIC -> state.copy(mic = v)
            Feature.CAMERA -> state.copy(camera = v)
            Feature.SPEAKER -> state.copy(speaker = v)
        }
    }

    private fun wire(v: FeatureView) = when (v.phase) {
        FeaturePhase.OFF, FeaturePhase.FAILED -> FeatureState.OFF
        FeaturePhase.PAUSED -> FeatureState.PAUSED
        else -> FeatureState.ON
    }

    private fun sendState() {
        if (state.connected) link.post(LinkMsg.Send(State(wire(state.mic), wire(state.camera), wire(state.speaker))))
    }

    // Link.

    private fun fromLink(e: LinkEvent) {
        when (e) {
            is LinkEvent.ConnectionChanged -> {
                val wasConnected = state.connected
                state = state.copy(connection = e.connection)
                (e.connection as? Connection.Connected)?.let { linkKind = it.link }
                if (wasConnected != state.connected) knownChanged()
            }
            is LinkEvent.Choices -> state = state.copy(choices = e.pcs)
            is LinkEvent.BluetoothOffer -> state = state.copy(bluetoothOffer = e.show)
            is LinkEvent.Welcomed -> welcomed(e)
            is LinkEvent.Switched -> switched(e.link)
            is LinkEvent.Control -> control(e.message)
            LinkEvent.Held -> Unit
            LinkEvent.Release -> {
                // 30 s without the PC: let go of the hardware, keep the user's choices to bring back (section 7.6).
                waiting = true
                media.post(MediaMsg.Mic(null))
                media.post(MediaMsg.Camera(null))
                media.post(MediaMsg.Speaker(null, false, true))
                Feature.entries.forEach { f -> if (state.feature(f).isOn) set(f, FeatureView(FeaturePhase.PAUSED)) }
            }
            LinkEvent.Ended -> {
                waiting = false
                Feature.entries.forEach { f -> if (state.feature(f).phase != FeaturePhase.OFF) set(f, FeatureView()) }
                media.post(MediaMsg.Mic(null))
                media.post(MediaMsg.Camera(null))
                media.post(MediaMsg.Speaker(null, false, true))
                link.post(LinkMsg.SpeakerExpected(false))
                linkKind = null
                pcId = null
                settings.post(SettingsMsg.Use(null, false))
            }
            is LinkEvent.RestartSource -> media.post(
                MediaMsg.Restart(
                    when (e.stream) {
                        Stream.MIC -> Feature.MIC
                        Stream.CAMERA -> Feature.CAMERA
                        else -> Feature.SPEAKER
                    },
                ),
            )
            LinkEvent.KnownChanged -> knownChanged()
        }
    }

    private fun welcomed(e: LinkEvent.Welcomed) {
        linkKind = e.link
        pcId = e.pcId
        settings.post(SettingsMsg.Use(e.pcId, true, e.settings))
        val bringBack = waiting
        waiting = false
        micPausedByPc = false
        cameraPausedByPc = false
        speakerPausedByPc = false
        // Whatever was on carries on with the PC: after a short drop the pipelines kept running; after a long one they restart.
        if (state.mic.phase != FeaturePhase.OFF) {
            set(Feature.MIC, FeatureView(if (micPausedByUser) FeaturePhase.PAUSED else FeaturePhase.STARTING))
            startMic()
            if (micPausedByUser) media.post(MediaMsg.MicPaused(true))
        }
        if (state.camera.phase != FeaturePhase.OFF) {
            if (e.link == LinkKind.BLUETOOTH) {
                media.post(MediaMsg.Camera(null))
                set(Feature.CAMERA, FeatureView(FeaturePhase.OFF, FeatureProblem.NO_BLUETOOTH))
            } else {
                set(Feature.CAMERA, FeatureView(FeaturePhase.STARTING))
                media.post(MediaMsg.Camera(cameraConfig()))
                cameraFormat?.let { (w, h, fps) -> sendCameraFormat(w, h, fps) }
                media.post(MediaMsg.Keyframe)
            }
        }
        if (state.speaker.phase != FeaturePhase.OFF) {
            set(Feature.SPEAKER, FeatureView(FeaturePhase.STARTING))
            link.post(LinkMsg.SpeakerExpected(true))
            if (bringBack) startSpeaker()
        }
        sendState()
    }

    private fun switched(to: LinkKind) {
        linkKind = to
        if (state.mic.isOn || state.mic.phase == FeaturePhase.PAUSED) startMic()
        if (state.camera.phase != FeaturePhase.OFF) {
            if (to == LinkKind.BLUETOOTH) {
                media.post(MediaMsg.Camera(null))
                set(Feature.CAMERA, FeatureView(FeaturePhase.OFF, FeatureProblem.NO_BLUETOOTH))
                link.post(LinkMsg.Send(StreamStop(Stream.CAMERA)))
            } else {
                media.post(MediaMsg.Camera(cameraConfig()))
                media.post(MediaMsg.Keyframe)
            }
        }
        if (state.speaker.isOn) startSpeaker()
        sendState()
    }

    private fun control(m: com.owlmic.core.proto.Message) {
        when (m) {
            is State -> {
                // The PC pauses and resumes what the phone turned on; it never turns anything on (section 17.4).
                if (m.mic == FeatureState.PAUSED && state.mic.isOn) {
                    micPausedByPc = true
                    applyMicPause()
                } else if (m.mic == FeatureState.ON && micPausedByPc) {
                    micPausedByPc = false
                    applyMicPause()
                }
                if (m.camera == FeatureState.PAUSED && state.camera.isOn) {
                    cameraPausedByPc = true
                    media.post(MediaMsg.CameraPaused(true))
                    set(Feature.CAMERA, FeatureView(FeaturePhase.PAUSED))
                } else if (m.camera == FeatureState.ON && cameraPausedByPc) {
                    cameraPausedByPc = false
                    media.post(MediaMsg.CameraPaused(false))
                    set(Feature.CAMERA, FeatureView(FeaturePhase.ON))
                }
                if (m.speaker == FeatureState.PAUSED && state.speaker.isOn) {
                    speakerPausedByPc = true
                    set(Feature.SPEAKER, FeatureView(FeaturePhase.PAUSED))
                } else if (m.speaker == FeatureState.ON && speakerPausedByPc) {
                    speakerPausedByPc = false
                    set(Feature.SPEAKER, FeatureView(FeaturePhase.ON))
                }
                // The PC hears back what the phone now does, "paused" included.
                sendState()
            }
            is SettingsMessage -> settings.post(SettingsMsg.Remote(m.changes))
            is StreamStart -> if (m.stream == Stream.SPEAKER) {
                speakerFormat = SpeakerFormat(
                    codec = m.codec,
                    channels = m.params.optInt("channels", 2),
                    frameMs = m.params.optInt("frameMs", 10),
                )
                if (state.speaker.phase != FeaturePhase.OFF) startSpeaker()
            }
            is StreamStop -> if (m.stream == Stream.SPEAKER) {
                speakerFormat = null
                media.post(MediaMsg.Speaker(null, false, true))
            }
            KeyframeRequest -> media.post(MediaMsg.Keyframe)
            is Report -> {
                media.post(MediaMsg.MicLoss(m.lossPct.toInt()))
                media.post(MediaMsg.CameraReport(m.lossPct, m.rttMs))
            }
            else -> Unit
        }
    }

    private fun knownChanged() {
        state = state.copy(
            knownPcs = store.known().values.filter { it.approved }.sortedByDescending { it.lastUsedAt }
                .map { KnownPcView(it.id, it.name, current = it.id == pcId && state.connected) },
        )
    }

    // Settings.

    private fun fromSettings(e: SettingsEvent) {
        when (e) {
            is SettingsEvent.Push -> link.post(LinkMsg.Send(SettingsMessage(e.changes)))
            is SettingsEvent.Values -> {
                state = state.copy(settings = e.values)
                val changed = e.changed
                if (changed.any { it.startsWith("mic.") } && state.mic.phase != FeaturePhase.OFF && !waiting) media.post(MediaMsg.Mic(micConfig()))
                if (changed.any { it.startsWith("camera.") } && state.camera.phase != FeaturePhase.OFF && !waiting) media.post(MediaMsg.Camera(cameraConfig()))
                if (changed.any { it.startsWith("link.") }) {
                    val allowed = LinkKind.entries.filter { k ->
                        state.settings[
                            when (k) {
                                LinkKind.USB_DEBUGGING -> "link.usbDebugging"
                                LinkKind.USB_TETHERING -> "link.usbTethering"
                                LinkKind.WIFI -> "link.wifi"
                                LinkKind.BLUETOOTH -> "link.bluetooth"
                            },
                        ] != "off"
                    }.toSet()
                    link.post(LinkMsg.Allowed(allowed))
                }
            }
        }
    }

    // Media.

    private fun fromMedia(e: MediaEvent) {
        when (e) {
            is MediaEvent.CameraFormat -> {
                cameraFormat = Triple(e.width, e.height, e.fps)
                sendCameraFormat(e.width, e.height, e.fps)
            }
            is MediaEvent.Health -> {
                val v = state.feature(e.feature)
                if (v.phase == FeaturePhase.OFF || v.phase == FeaturePhase.PAUSED) return
                set(
                    e.feature,
                    when (e.health) {
                        Health.Ok -> FeatureView(FeaturePhase.ON)
                        is Health.Degraded -> FeatureView(FeaturePhase.RECOVERING)
                        is Health.Failed -> FeatureView(FeaturePhase.FAILED)
                    },
                )
                if (e.health is Health.Failed) {
                    // Given up: let go of the hardware. The tile shows the failure until the user taps it.
                    when (e.feature) {
                        Feature.MIC -> media.post(MediaMsg.Mic(null))
                        Feature.CAMERA -> media.post(MediaMsg.Camera(null))
                        Feature.SPEAKER -> media.post(MediaMsg.Speaker(null, false, true))
                    }
                    if (e.feature != Feature.SPEAKER) link.post(LinkMsg.Send(StreamStop(if (e.feature == Feature.MIC) Stream.MIC else Stream.CAMERA)))
                    sendState()
                }
            }
        }
    }

    private fun sendCameraFormat(w: Int, h: Int, fps: Int) {
        link.post(LinkMsg.Send(StreamStart(Stream.CAMERA, "h264", JSONObject().put("width", w).put("height", h).put("fps", fps))))
    }

    private fun publish() {
        if (Owlmic.mutableState.value != state) {
            Owlmic.mutableState.value = state
            onState(state)
        }
    }

    override fun close() {
        link.close()
        settings.close()
        media.close()
        super.close()
    }

    private companion object {
        fun phoneName(context: Context): String =
            runCatching { Global.getString(context.contentResolver, Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}
