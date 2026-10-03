package com.owlmic.core.proto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagesTest {
    /** JSON as plain maps, lists and doubles, so equal documents compare equal whatever their number types. */
    private fun plain(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
        is JSONArray -> List(v.length()) { plain(v.get(it)) }
        is Number -> v.toDouble()
        else -> v
    }

    @Test
    fun everyVectorMessageDecodesAndEncodesBackToTheSameJson() {
        val messages = Vectors.load("messages.json").getJSONArray("messages")
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val payload = m.getJSONObject("payload")
            val decoded = Message.decode(m.getInt("type"), payload.toString().toByteArray())!!
            assertEquals(m.getString("name"), m.getInt("type"), decoded.kind)
            val back = JSONObject(String(decoded.toPayload()))
            assertEquals(m.getString("name"), plain(payload), plain(back))
        }
    }

    @Test
    fun unknownTypesAndUnknownKeysAreIgnored() {
        assertNull(Message.decode(0x7E, "{}".toByteArray()))
        assertEquals(Ping(5), Message.decode(Kind.PING, """{"t":5,"future":true}""".toByteArray()))
    }
}
