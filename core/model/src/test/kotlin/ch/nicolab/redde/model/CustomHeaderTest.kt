package ch.nicolab.redde.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from EchoTests/CustomHeaderTests.swift; the request-level checks move with the transports. */
class CustomHeaderTest {
    @Test
    fun namesAreHeaderTokensAndNotTheConnectionsOwn() {
        assertNull(CustomHeader.problem("X-Proxy-Token"))
        assertNull(CustomHeader.problem("Authorization"), "a proxy's basic auth is a fair use")
        assertNotNull(CustomHeader.problem(""))
        assertNotNull(CustomHeader.problem("X Proxy"))
        assertNotNull(CustomHeader.problem("X-Proxy: abc"))
        assertNotNull(CustomHeader.problem("Host"))
        assertNotNull(CustomHeader.problem("content-length"))
        assertNotNull(CustomHeader.problem("Sec-WebSocket-Key"))
    }

    @Test
    fun theListSurvivesStorageAndAnEmptyOneIsNoEntry() {
        val headers = listOf(CustomHeader(" X-Proxy-Token ", " s3cret "), CustomHeader("X-Tenant", "home"))
        assertEquals(CustomHeader("X-Proxy-Token", "s3cret"), headers[0], "pasted with spaces around it")
        assertEquals(headers, CustomHeader.decode(CustomHeader.encode(headers)))
        assertEquals("", CustomHeader.encode(emptyList()))
        assertTrue(CustomHeader.decode(null).isEmpty())
        assertTrue(CustomHeader.decode("not json").isEmpty())
    }

    @Test
    fun theStoredFormIsTheOneTheIosAppWrote() {
        assertEquals(listOf(CustomHeader("X-A", "1")), CustomHeader.decode("""[{"name":"X-A","value":"1"}]"""))
    }

    @Test
    fun onlyHeadersThatCanBeSentBecomeFields() {
        val fields = CustomHeader.fields(listOf(
            CustomHeader("X-Proxy-Token", "s3cret"),
            CustomHeader("Host", "elsewhere"),
            CustomHeader("X-Empty", ""),
            CustomHeader("X-Split", "a\nInjected: yes"),
        ))
        assertEquals(mapOf("X-Proxy-Token" to "s3cret"), fields)
    }

    @Test
    fun addingReplacesOneByTheSameName() {
        val list = CustomHeader.adding(CustomHeader("x-tenant", "b"), listOf(CustomHeader("X-Tenant", "a"), CustomHeader("X-Other", "c")))
        assertEquals(listOf(CustomHeader("X-Other", "c"), CustomHeader("x-tenant", "b")), list)
    }
}
