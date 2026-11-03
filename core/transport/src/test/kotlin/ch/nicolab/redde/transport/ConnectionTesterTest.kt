package ch.nicolab.redde.transport

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionTesterTest {
    private val server = MockWebServer()
    private val tester = ConnectionTester()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun answer(code: Int, body: String = "{}") = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun theHermesApiWithAGoodKey() = runBlocking {
        answer(200); answer(200, """{"data":[]}""")
        assertEquals(ConnectionTester.Outcome.KeyAccepted, tester.hermesApi(server.url("/"), "k"))
        assertEquals("/health", server.takeRequest().target)
        assertEquals("Bearer k", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun aRejectedKeyAndAMissingOne() = runBlocking {
        answer(200); answer(401)
        assertEquals(ConnectionTester.Outcome.KeyRejected, tester.hermesApi(server.url("/"), "bad"))
        answer(200)
        assertEquals(ConnectionTester.Outcome.NeedsKey, tester.hermesApi(server.url("/"), null))
    }

    @Test
    fun anAccessGateIsSaidToBeOneAndItsRedirectIsntFollowed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://login.example/").build())
        val outcome = tester.hermesApi(server.url("/"), "k", mapOf("CF-Access-Client-Id" to "x"))
        assertEquals(ConnectionTester.Outcome.Gated(server.url("/").host, 302, headersSent = true), outcome)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun nothingThereIsNoServer() = runBlocking {
        val url = server.url("/")
        server.close()
        assertTrue(tester.hermesApi(url, "k") is ConnectionTester.Outcome.NoServer)
    }

    @Test
    fun anOpenAiCompatibleServerListsItsModels() = runBlocking {
        answer(200, """{"data":[{"id":"qwen"},{"id":"llama"}]}""")
        assertEquals(ConnectionTester.Outcome.ModelsListed(2), tester.openAiCompatible(server.url("/"), null, "qwen"))
        answer(200, """{"data":[{"id":"qwen"}]}""")
        assertEquals(ConnectionTester.Outcome.ModelMissing("gpt"), tester.openAiCompatible(server.url("/"), null, "gpt"))
        assertEquals(listOf("a" to true, "b" to false),
            ConnectionTester.listedModels("""{"data":[{"id":"a","status":{"value":"loaded"}},{"id":"b"}]}"""))
    }

    @Test
    fun whetherADashboardAsksForALogin() = runBlocking {
        answer(200, """{"version":"0.22.0","auth_required":true}""")
        assertEquals(true, tester.dashboardAsksForLogin(server.url("/"), emptyMap()))
        answer(200, """{"something":"else"}""")
        assertEquals(null, tester.dashboardAsksForLogin(server.url("/"), emptyMap()), "not a Dashboard")
    }
}
