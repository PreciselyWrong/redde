package ch.nicolab.redde.transport

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionBackendTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    @Test
    fun theLedgerReadsATranscriptAndForks() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"data":[{"role":"user","content":"Hi"},{"role":"assistant","content":"Hello"}]}""").build())
        server.enqueue(MockResponse.Builder().body("""{"session":{"id":"s2","title":"Copy"}}""").build())
        val backend: SessionBackend = SessionBackend.Ledger(HermesSessionsApi(server.url("/"), "k"))
        assertEquals(listOf("Hi", "Hello"), backend.messages("s1").map { it.text })
        assertEquals("s2", backend.fork("s1", "Copy")?.id)
        assertEquals("/api/sessions/s1/messages?order=latest&limit=500", server.takeRequest().target)
        assertEquals("""{"title":"Copy"}""", server.takeRequest().body!!.utf8())
    }
}
