package ch.nicolab.redde.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from EchoTests/SetupCodeTests.swift (iOS 37a2db7): the reading and making of links. The
 * tests of installing a code into Settings and the Keychain move with Settings (phase 2).
 */
class SetupCodeTest {
    private fun read(link: String) = SetupCode.read(link)

    @Test
    fun readsEveryParameter() {
        val code = assertNotNull(read(
            "redde://connect?v=1&name=Home&dashboard=http://hermes.home.test:9119/&user=redde&password=pw" +
                "&api=https://hermes.home.test:8642/v1&key=k1&profile=work&profile-key=k2&access-id=id.access&access-secret=s" +
                "&model-url=http://llama.home.test:8080&model-key=mk&model=qwen&use=api",
        ))
        assertEquals("Home", code.name)
        assertEquals("http://hermes.home.test:9119", code.dashboardUrl, "as Setup stores it: no trailing slash")
        assertEquals("redde", code.dashboardUser)
        assertEquals("pw", code.dashboardPassword)
        assertEquals("https://hermes.home.test:8642", code.apiUrl, "and no /v1")
        assertEquals("k1", code.apiKey)
        assertEquals("work", code.profile)
        assertEquals("k2", code.profileKey)
        assertEquals("id.access", code.accessId)
        assertEquals("s", code.accessSecret)
        assertEquals("http://llama.home.test:8080", code.modelUrl)
        assertEquals("mk", code.modelKey)
        assertEquals("qwen", code.model)
        assertEquals(Transport.HERMES_SESSIONS, code.use)
        assertEquals(Transport.HERMES_SESSIONS, code.transport)
        assertTrue(code.hasServer)
        assertTrue(code.hasSecrets)
    }

    @Test
    fun otherLinksArentSetupLinks() {
        val others = listOf(
            "echo://listen", "echo://connect?api=http://a.test", "redde://listen",
            "https://redde.goosehouse.org/connect", "https://redde.goosehouse.org/connect/", "https://redde.goosehouse.org/#api=http://a.test",
            "https://redde.goosehouse.org/connected#api=http://a.test", "http://redde.goosehouse.org/connect#api=http://a.test",
            "https://redde.goosehouse.org.evil.test/connect#api=http://a.test", "https://evil.test/connect#api=http://a.test",
            "https://redde.goosehouse.org:8443/connect#api=http://a.test",
        )
        for (link in others) {
            assertNull(read(link), link)
            assertNull(SetupCodeOffer.fromLink(link))
        }
        assertEquals("http://a.test", read("REDDE://Connect?api=http://a.test")?.apiUrl, "the scheme and host in any case")
    }

    @Test
    fun theWebFormReadsTheSame() {
        val parameters = "name=Home&dashboard=http://hermes.home.test:9119&user=redde&password=p%26w%2B1&api=https://hermes.home.test:8642&key=k&use=api"
        val app = assertNotNull(read("redde://connect?$parameters"))
        assertEquals(app, read("https://redde.goosehouse.org/connect#$parameters"))
        assertEquals(app, read("HTTPS://Redde.Goosehouse.org/connect/#$parameters"))
        assertEquals("p&w+1", app.dashboardPassword)
        assertTrue(app.webLink.startsWith("https://redde.goosehouse.org/connect#name=Home&"))
        assertFalse('?' in app.webLink.substringBefore('#'), "nothing may ride in the part of the address a server sees")
        assertEquals(app, read(app.webLink))
        // Parameters after a "?" went to the server; they aren't read, and the link says why not.
        assertFailsWith<SetupCode.ParseError.NoAddress> { read("https://redde.goosehouse.org/connect?$parameters") }
        assertEquals(app, read("https://redde.goosehouse.org/connect?api=http://evil.test#$parameters"))
    }

    @Test
    fun aBadlyEscapedValueDoesntSpoilTheRest() {
        for (prefix in listOf("redde://connect?", "https://redde.goosehouse.org/connect#")) {
            val code = assertNotNull(read(prefix + "key=%zz&name=%E0%A4%A&user&api=http://a.test&profile=work"))
            assertEquals("http://a.test", code.apiUrl)
            assertEquals("work", code.profile)
            assertEquals("", code.dashboardUser)
        }
    }

    @Test
    fun refusesWhatItCantUse() {
        assertFailsWith<SetupCode.ParseError.NewerVersion> { read("redde://connect?v=2&api=http://a.test") }
        assertFailsWith<SetupCode.ParseError.NoAddress> { read("redde://connect?name=Home&user=redde&password=pw") }
        assertFailsWith<SetupCode.ParseError.NoAddress> { read("redde://connect") }
        for (bad in listOf("ftp://a.test", "javascript:alert(1)", "hermes.home.test:9119", "file:///etc/hosts", "http://my-server@elsewhere.test", "http://")) {
            val encoded = bad.toByteArray().joinToString("") { b ->
                val c = (b.toInt() and 0xFF).toChar()
                if (c.isLetterOrDigit() && c.code < 128) c.toString() else "%%%02X".format(b.toInt() and 0xFF)
            }
            val error = assertFailsWith<SetupCode.ParseError.BadAddress>(bad) { read("redde://connect?dashboard=$encoded") }
            assertEquals(bad, error.text)
        }
        val error = assertFailsWith<SetupCode.ParseError.BadAddress> { read("redde://connect?api=http://a.test&model-url=ftp://b.test") }
        assertEquals("ftp://b.test", error.text, "one bad address spoils the code, even beside a good one")
        assertFailsWith<SetupCode.ParseError.TooLong> {
            read("redde://connect?api=http://a.test&key=" + "k".repeat(SetupCode.MAXIMUM_LENGTH))
        }
    }

    @Test
    fun aLinkThatCantBeUsedIsStillAnOffer() {
        val offer = assertNotNull(SetupCodeOffer.fromLink("redde://connect?v=9&api=http://a.test"))
        assertTrue(offer.result.exceptionOrNull() is SetupCode.ParseError.NewerVersion)
    }

    @Test
    fun theFirstOfARepeatedParameterCounts() {
        val code = assertNotNull(read("redde://connect?api=http://a.test&api=http://b.test&key=&key=second"))
        assertEquals("http://a.test", code.apiUrl)
        assertEquals("second", code.apiKey, "a blank one is no value")
    }

    @Test
    fun carriesTheHeadersAReverseProxyAsksFor() {
        val link = "redde://connect?api=http://a.test:8642&key=k&header=X-Proxy-Token%3A%20s3cret%20value%2F%2B%26%3D" +
            "&header=X-Tenant:home&header=x-tenant:second&header=Host:elsewhere&header=NoColon&header=Empty:&header=Bad%20Name:x"
        val code = assertNotNull(read(link))
        assertEquals(
            listOf(CustomHeader("X-Proxy-Token", "s3cret value/+&="), CustomHeader("X-Tenant", "home")),
            code.headers,
            "the first of two by one name counts, and what couldn't be sent is left out",
        )
        assertTrue(code.hasSecrets, "a header's value is a secret")
        assertTrue(code.withoutSecrets.headers.isEmpty())
        assertEquals(code, read(code.appLink))
        assertEquals(code, read(code.webLink))
        assertTrue("header=X-Proxy-Token" in code.appLink && "&=" !in code.appLink, "the value's own & and = are escaped")
        val many = "redde://connect?api=http://a.test" + (1..12).joinToString("") { "&header=X-H$it:v" }
        assertEquals(SetupCode.MAXIMUM_HEADERS, assertNotNull(read(many)).headers.size)
        assertTrue(assertNotNull(read("redde://connect?model-url=http://m.test&header=X-Tenant:home")).headers.isEmpty(),
            "a model endpoint is sent no headers, so a code for one alone carries none")
        assertEquals("a:b", SetupCode.header("X-Token:a:b")?.value, "only the first colon divides")
    }

    @Test
    fun useFallsBackToWhatTheCodeCarries() {
        assertEquals(Transport.HERMES_SESSIONS, read("redde://connect?api=http://a.test&use=dashboard")?.transport)
        assertEquals(Transport.HERMES_SERVE, read("redde://connect?api=http://a.test&dashboard=http://d.test")?.transport)
        assertEquals(Transport.HERMES_SESSIONS, read("redde://connect?api=http://a.test&dashboard=http://d.test&use=api")?.transport)
        val model = assertNotNull(read("redde://connect?model-url=http://m.test&model=qwen"))
        assertEquals(Transport.CHAT_COMPLETIONS, model.transport)
        assertFalse(model.hasServer)
    }

    @Test
    fun awkwardCharactersSurviveTheLink() {
        val code = SetupCode(
            name = "Sol's house & café",
            dashboardUrl = "http://hermes.home.test:9119",
            dashboardUser = "redde+phone@home",
            dashboardPassword = "p&ss=w+rd #1% /?é 🔑",
            apiUrl = "https://hermes.home.test:8642",
            apiKey = "sk-AbC/+==&x",
            use = Transport.HERMES_SERVE,
        )
        for (link in listOf(code.appLink, code.webLink)) {
            assertFalse('+' in link, "a plus sign must be escaped: other readers take it for a space")
            assertFalse(' ' in link)
            assertEquals(code, read(link))
        }
        assertEquals(false, read(code.withoutSecrets.appLink)?.hasSecrets)
    }

    /** The output of `scripts/setup-code.py` for these values, in both forms. */
    @Test
    fun aLinkMadeByTheScriptReads() {
        val parameters = "name=Sol%27s%20house%20%26%20caf%C3%A9&dashboard=http%3A%2F%2Fhermes.home.test%3A9119" +
            "&user=redde%2Bphone%40home&password=p%26ss%3Dw%2Brd%20%231%25%20%2F%3F%C3%A9&api=https%3A%2F%2Fhermes.home.test%3A8642" +
            "&key=sk-AbC%2F%2B%3D%3D%26x&use=dashboard"
        for (link in listOf("https://redde.goosehouse.org/connect#$parameters", "redde://connect?$parameters")) {
            val code = assertNotNull(read(link))
            assertEquals("Sol's house & café", code.name)
            assertEquals("http://hermes.home.test:9119", code.dashboardUrl)
            assertEquals("redde+phone@home", code.dashboardUser)
            assertEquals("p&ss=w+rd #1% /?é", code.dashboardPassword)
            assertEquals("https://hermes.home.test:8642", code.apiUrl)
            assertEquals("sk-AbC/+==&x", code.apiKey)
            assertEquals(Transport.HERMES_SERVE, code.transport)
        }
    }

    @Test
    fun findsTheLinkInText() {
        val link = "redde://connect?api=http://a.test&key=k"
        assertEquals(link, SetupCode.link(link))
        assertEquals(link, SetupCode.link("Here you go:\n$link\nSee you"))
        assertEquals(link, SetupCode.link("<$link>"))
        assertNull(SetupCode.link("https://example.com/?q=redde"))
        val web = "https://redde.goosehouse.org/connect#api=http://a.test&key=k"
        assertEquals(web, SetupCode.link("Scan this or tap: $web\n"))
        assertNull(SetupCode.link("See https://redde.goosehouse.org/connect for how"), "the page, not a code")
        assertNull(SetupCodeOffer.fromText("nothing here"))
        assertEquals("k", SetupCodeOffer.fromText("  $link  ")?.result?.getOrNull()?.apiKey)
    }

    @Test
    fun pangolinTokensGoAlongAsSecrets() {
        val link = "https%3A%2F%2Fapi.test%2F%3Fp_token%3Dapiid.apitoken"
        val code = assertNotNull(read("redde://connect?dashboard=http://d.test:9119&api=http://a.test:8642&key=k&pangolin=dashid.dashtoken&pangolin-api=$link"))
        assertEquals("dashid.dashtoken", code.pangolin)
        assertEquals("apiid.apitoken", code.pangolinApi)
        assertTrue(code.hasSecrets)
        assertTrue(code.withoutSecrets.pangolin.isEmpty() && code.withoutSecrets.pangolinApi.isEmpty())
        assertTrue(assertNotNull(read("redde://connect?dashboard=http://d.test:9119&pangolin=nonsense")).pangolin.isEmpty(), "not a token: left out")
    }

    @Test
    fun aNotificationPairingLinkIsNotASetupCode() {
        assertNull(read("https://redde.goosehouse.org/connect#push=AAAA"))
    }

    @Test
    fun anOpenDashboardSaysSoAndOnlyWithoutALogin() {
        assertTrue(assertNotNull(read("redde://connect?dashboard=http://d.test:9119&sign-in=none")).dashboardOpen)
        assertFalse(assertNotNull(read("redde://connect?dashboard=http://d.test:9119&user=u&sign-in=none")).dashboardOpen)
        val open = SetupCode(dashboardUrl = "http://d.test:9119", dashboardOpen = true)
        assertTrue("sign-in=none" in open.appLink)
        assertEquals(open, read(open.appLink))
    }
}
