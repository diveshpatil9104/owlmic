package com.owlmic

import org.junit.Assert.assertEquals
import org.junit.Test

class NotifierTest {
    @Test
    fun featureNamesJoinLikeThePcTray() {
        assertEquals("Mic", Notifier.joinNames(listOf("Mic"), "and"))
        assertEquals("Mic and Camera", Notifier.joinNames(listOf("Mic", "Camera"), "and"))
        assertEquals("Mic, Camera and Speaker", Notifier.joinNames(listOf("Mic", "Camera", "Speaker"), "and"))
    }
}
