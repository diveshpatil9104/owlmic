package com.owlmic.media.audio

import com.owlmic.core.hub.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MicCodecTest {
    @Test
    fun cablesCarryPcmWifiOpusAndBluetoothLeanerOpus() {
        assertEquals(MicCodec.PCM, MicCodec.forLink(LinkKind.USB_DEBUGGING))
        assertEquals(MicCodec.PCM, MicCodec.forLink(LinkKind.USB_TETHERING))
        assertEquals(MicCodec.OPUS_WIFI, MicCodec.forLink(LinkKind.WIFI))
        assertEquals(MicCodec.OPUS_BLUETOOTH, MicCodec.forLink(LinkKind.BLUETOOTH))
        assertEquals(20, MicCodec.OPUS_BLUETOOTH.frameMs)
    }
}
