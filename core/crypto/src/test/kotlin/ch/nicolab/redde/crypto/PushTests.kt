package ch.nicolab.redde.crypto

/*
 * Ported from the iOS app's `EchoTests/PushTests.swift`: everything about the crypto, the sealing,
 * pairing and reading pairing links. Not ported, because what they test is iOS plumbing that has
 * no place in this module:
 *
 * - PushNoteContentTests, except `aCommandsDigestIsThePlugins` and the digest line of
 *   `aQuestionThatCanBeAnsweredFromItsNotificationSaysWhichQuestion` (both in PushNoteDigestTests):
 *   how `PushNote.fill` writes a `UNMutableNotificationContent` and `choiceCategory` builds a
 *   `UNNotificationCategory`. Notification presentation, done by each platform's UI layer.
 * - `aFailureTheAppAlreadyShowedArrivesQuietly`: `ToldAlready` over `UserDefaults` and the app's
 *   `Notifier`; notification de-duplication, not crypto.
 * - `aKindFromANewerPluginLeavesTheRelaysWords`: the `fill` half is UI; the `kind == nil` half is
 *   kept in `aKindFromANewerPluginIsUnknown`.
 * - `pairingsAreKeptInTheSharedKeychainAndConfirmedByTheFirstNote`: the Keychain half is iOS; the
 *   storage is an interface here (`PushPairingStore`), so the confirm logic is tested against an
 *   in-memory store in `aPairingIsConfirmedByTheFirstNote`.
 * - `aPairingLinkIsNotTakenForASetupCode`: `SetupCodeOffer`, the server setup link, lives outside
 *   this module.
 * - PushServiceTests (all): `PushService` talks to the relay over `URLSession` (`RelayStub`),
 *   asks APNs/PushKit for tokens and reads `UIDevice`. What they check of the crypto (the plugin
 *   opens the answer with its own private key, both ends get the same note key) is checked here
 *   by `thePluginOpensWhatThePhoneAnswers`.
 * - UnopenedNoteTests (all): the app's `Notifier` showing a banner for a note it opened itself.
 */

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Values the plugin's own code produced (`companion/hermes-plugin/redde-push/core.py`) for fixed
 * private keys: the plugin's is bytes 0…31, the phone's bytes 32…63. If either end changes how it
 * derives or seals, these stop matching.
 */
private object Vector {
    val offered: ByteArray = PushSeal.data("j0DFrbaPJWJK5bIU6nZ6bslNgp09e14a0bpvPiE4KF8")!!
    const val ANSWERED = "NYBy1jZYgNGu6jKa35EhODhR7SGijjt16WXQ0s0WYlQ"
    val noteKey = hex("cad4112e909263b723613b895715fabcce5c9d1f2a55284ecccfd66d5abd4c51")
    val boxKey = hex("4568438b3839ff1bc281370fb9bd943edc872677c7abaff81c0795db2ea633e1")
    const val RENDEZVOUS = "45178c46725bbf53272efb845dfb22e3"
    const val KEY_ID = "ae202bee"
    const val DEVICE_ID = "dev-test-0001"

    /** {"v":1,"k":"reply","at":1791330000,"n":"studio","s":"20261006_101500_ab12cd","t":"Nightly build","b":"The build is **green**.\n\nAll 381 tests pass."} */
    const val PAYLOAD = "riAr7gcHBwcHBwcHBwcHB5XtQB18k8wLkb6EI7GxAo+pJKgpKO/vLbTOXDSqgIbsR+P5sxa1pSOLV8Ub6F3zCst82T7z4RXapXOswQWO7V4f7Y+tDMP4evQUERsSDxUg05If2LCUEpATsir0/C6EWKXnKKkLpGOu8QO7KP20rSQF7pVmX7wOtt264A2pLfXCYd/voFRUlFYwiamZBh9iBcTQOXS66E+DqQxTtifc9S2Rq0XPHBI="

    /** {"id":"dev-test-0001","send":"send-key","name":"Test phone"} sealed by the plugin's code. */
    const val BOX = "BwcHBwcHBwcHBwcHbUcsrX4qX7YAQONRQa866RdxGZ9XIMcq2b2M8GZOu3yE1Wry7tpE4H87VWuPWmBCMn8SJZs5Lcw-N2jSGmIHm1l31twhzlfKMflwHwxrkJE1"

    val phoneKey: ByteArray get() = ByteArray(32) { (32 + it).toByte() }
    val pluginKey: ByteArray get() = ByteArray(32) { it.toByte() }
    val pairing: PushPairing get() = PushPairing(id = DEVICE_ID, sendKey = "send-key", key = noteKey, relay = "https://relay.test")

    fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

private fun fields(json: ByteArray): Map<String, String> =
    Json.parseToJsonElement(json.decodeToString()).jsonObject.mapValues { it.value.jsonPrimitive.content }

class PushSealTests {
    @Test
    fun thePhoneReachesTheKeyThePluginDoes() {
        assertContentEquals(Vector.offered, PushSeal.publicKey(Vector.pluginKey))
        assertEquals(Vector.RENDEZVOUS, PushSeal.rendezvous(Vector.offered))
        val answer = PushSeal.answer(Vector.offered, Vector.DEVICE_ID, sendKey = "send-key", name = "Test phone", privateKey = Vector.phoneKey)
        assertEquals(Vector.ANSWERED, answer.pub)
        assertContentEquals(Vector.noteKey, answer.noteKey)
        assertEquals(Vector.KEY_ID, PushSeal.hex(PushSeal.keyID(answer.noteKey)))
        // The plugin opens the box with the other half of the derived key.
        val box = assertNotNull(PushSeal.data(answer.box))
        val inside = PushSeal.open(box, Vector.boxKey, PushSeal.pairingAAD)
        assertEquals(mapOf("id" to Vector.DEVICE_ID, "send" to "send-key", "name" to "Test phone"), fields(inside))
        assertFailsWith<PushSealException> { PushSeal.open(box, Vector.noteKey, PushSeal.pairingAAD) }
    }

    /**
     * A tablet or a computer says what it is, for the plugin to list it as that and never ring a
     * computer; left out, the box is what a plugin from before always took.
     */
    @Test
    fun anAnswerSaysWhatKindOfDeviceItIsFrom() {
        val answer = PushSeal.answer(Vector.offered, Vector.DEVICE_ID, sendKey = "send-key", name = "Studio", kind = "mac",
            privateKey = Vector.phoneKey)
        assertTrue(answer.pub == Vector.ANSWERED && answer.noteKey.contentEquals(Vector.noteKey), "the keys are the same whatever it is")
        val box = assertNotNull(PushSeal.data(answer.box))
        val inside = PushSeal.open(box, Vector.boxKey, PushSeal.pairingAAD)
        assertEquals(mapOf("id" to Vector.DEVICE_ID, "send" to "send-key", "name" to "Studio", "kind" to "mac"), fields(inside))
    }

    @Test
    fun aBoxThePluginSealedOpensHere() {
        val box = assertNotNull(PushSeal.data(Vector.BOX))
        val inside = PushSeal.open(box, Vector.boxKey, PushSeal.pairingAAD)
        assertTrue(inside.decodeToString().contains("\"send\": \"send-key\""))
    }

    @Test
    fun aNoteThePluginSealedOpensWithItsPairingAndNoOther() {
        val stranger = PushPairing(id = "someone-else", sendKey = "x", key = ByteArray(32) { 9 }, relay = "https://relay.test")
        val opened = assertNotNull(PushSeal.note(Vector.PAYLOAD, listOf(stranger, Vector.pairing)))
        assertEquals(Vector.DEVICE_ID, opened.pairing.id)
        assertEquals(
            PushNote(k = "reply", s = "20261006_101500_ab12cd", t = "Nightly build",
                b = "The build is **green**.\n\nAll 381 tests pass.", n = "studio", at = 1_791_330_000.0),
            opened.note,
        )
        assertEquals(PushNote.Kind.REPLY, opened.note.kind)

        assertNull(PushSeal.note(Vector.PAYLOAD, listOf(stranger)))
        // Sealed to the device: the same key under another id won't open it.
        val renamed = Vector.pairing.copy(id = "dev-test-0002")
        assertNull(PushSeal.note(Vector.PAYLOAD, listOf(renamed)))
        val tampered = Base64.getDecoder().decode(Vector.PAYLOAD)
        tampered[tampered.size - 20] = (tampered[tampered.size - 20].toInt() xor 1).toByte()
        assertNull(PushSeal.note(Base64.getEncoder().encodeToString(tampered), listOf(Vector.pairing)))
        for (junk in listOf("", "not base64 !", "AAAA", Base64.getEncoder().encodeToString(ByteArray(200)))) {
            assertNull(PushSeal.note(junk, listOf(Vector.pairing)), junk)
        }
    }

    @Test
    fun sealingAndOpeningRoundTrips() {
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val sealed = PushSeal.seal("hello".encodeToByteArray(), key, "who".encodeToByteArray())
        assertEquals(12 + 5 + 16, sealed.size)
        assertContentEquals("hello".encodeToByteArray(), PushSeal.open(sealed, key, "who".encodeToByteArray()))
        assertFailsWith<PushSealException> { PushSeal.open(sealed, key, "someone else".encodeToByteArray()) }
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xfe.toByte(), 0x01)
        assertContentEquals(bytes, PushSeal.data(PushSeal.base64URL(bytes)))
    }

    /**
     * From `PushServiceTests.pairingRegistersThisPhoneAndLeavesAnAnswerOnlyThePluginCanOpen`, its
     * crypto alone: the plugin's private key and the phone's public one give the same keys, and
     * the box opens to what the phone sealed in it.
     */
    @Test
    fun thePluginOpensWhatThePhoneAnswers() {
        val answer = PushSeal.answer(Vector.offered, "relay-id-0123456789", sendKey = "secret-send", name = "Pixel", kind = "android")
        val theirs = assertNotNull(PushSeal.data(answer.pub))
        val keys = PushSeal.keys(PushSeal.sharedSecret(Vector.pluginKey, theirs), Vector.offered, theirs)
        val inside = PushSeal.open(assertNotNull(PushSeal.data(answer.box)), keys.box, PushSeal.pairingAAD)
        val said = fields(inside)
        assertEquals("relay-id-0123456789", said["id"])
        assertEquals("secret-send", said["send"])
        assertEquals("android", said["kind"])
        assertContentEquals(keys.note, answer.noteKey, "both ends will seal notes with the same key")
        assertTrue("secret-send" !in answer.pub + answer.box, "and the relay can't read the answer")
        // A fresh key every time it isn't given one.
        assertNotEquals(answer.pub, PushSeal.answer(Vector.offered, "x", "y", "z").pub)
    }

    /** What the phone seals the plugin's way opens, through the same envelope as a real note. */
    @Test
    fun aNoteSealedHereOpensAsThePluginsDo() {
        val note = PushNote(k = "ideas", p = "default", i = "offer-1", y = listOf(PushNote.Idea(t = "Tidy", r = "tidy up")))
        val pairing = Vector.pairing
        val blob = PushSeal.keyID(pairing.key) + PushSeal.seal(note.encode().encodeToByteArray(), pairing.key, pairing.id.encodeToByteArray())
        val opened = assertNotNull(PushSeal.note(Base64.getEncoder().encodeToString(blob), listOf(pairing)))
        assertEquals(note, opened.note)
        assertEquals(PushNote.Kind.IDEAS, opened.note.kind)
        // Only the fields it has, and `v` always.
        val written = Json.parseToJsonElement(note.encode()) as JsonObject
        assertEquals(setOf("v", "k", "p", "i", "y"), written.keys)
    }

    @Test
    fun aKindFromANewerPluginIsUnknown() {
        assertNull(PushNote(k = "something-new").kind)
        assertEquals(PushNote.Kind.GROUP_WAIT, PushNote(k = "group_wait").kind)
        // And one with fields this build doesn't know still reads.
        assertEquals(PushNote(k = "reply", b = "x"), PushNote.decode("""{"v":1,"k":"reply","b":"x","zz":[1,2]}""".encodeToByteArray()))
        assertNull(PushNote.decode("""{"v":1}""".encodeToByteArray()))
        assertNull(PushNote.decode("not json".encodeToByteArray()))
    }

    /** RFC 5869, test case 1 (and 3, with no salt), for the HKDF written here. */
    @Test
    fun hkdfIsRfc5869() {
        val okm = hkdfSha256(ByteArray(22) { 0x0b }, salt = Vector.hex("000102030405060708090a0b0c"),
            info = Vector.hex("f0f1f2f3f4f5f6f7f8f9"), length = 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", PushSeal.hex(okm))
        val noSalt = hkdfSha256(ByteArray(22) { 0x0b }, salt = ByteArray(0), info = ByteArray(0), length = 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", PushSeal.hex(noSalt))
    }
}

class PushPairingTests {
    private class MemoryStore(var pairings: List<PushPairing> = emptyList()) : PushPairingStore {
        override fun load() = pairings
        override fun save(pairings: List<PushPairing>): Boolean {
            this.pairings = pairings
            return true
        }
    }

    /** From `pairingsAreKeptInTheSharedKeychainAndConfirmedByTheFirstNote`, without the Keychain. */
    @Test
    fun aPairingIsConfirmedByTheFirstNote() {
        val store = MemoryStore()
        val pairing = Vector.pairing
        assertTrue(store.load().isEmpty())
        assertTrue(store.save(listOf(pairing)))
        assertEquals(listOf(pairing), store.load())
        assertTrue(!store.load()[0].confirmed && !store.load()[0].isPaired)
        store.confirm(pairing, host = "studio")
        val kept = store.load()
        assertTrue(kept.size == 1 && kept[0].confirmed && kept[0].host == "studio" && kept[0].key.contentEquals(Vector.noteKey))
        assertTrue(kept[0].isPaired)
        store.confirm(PushPairing(id = "unknown", sendKey = "", key = ByteArray(0), relay = ""), host = "x")
        assertEquals(kept, store.load())
        // A note without a machine's name keeps the one known.
        store.confirm(pairing, host = null)
        assertEquals("studio", store.load()[0].host)
    }

    @Test
    fun pairingsReadBackAsTheyWereWritten() {
        val pairings = listOf(Vector.pairing.copy(pairedAt = 1_791_330_000, accepted = true), Vector.pairing.copy(id = "second", confirmed = true))
        val written = PushPairing.encodeList(pairings)
        assertEquals(pairings, PushPairing.decodeList(written))
        assertTrue(Base64.getEncoder().encodeToString(Vector.noteKey) in written, "the key as base64, as the Swift app kept it")
        assertTrue(PushPairing.decodeList("garbage").isEmpty())
        // A pairing saved before `accepted` existed still reads.
        val old = PushPairing.decodeList("""[{"id":"a","sendKey":"s","key":"${Base64.getEncoder().encodeToString(Vector.noteKey)}","relay":"r"}]""")
        assertEquals(null, old.single().accepted)
        assertTrue("send-key" !in Vector.pairing.toString(), "a pairing can be logged without its secrets")
    }
}

class PushNoteDigestTests {
    /** The plugin's `core.digest` gives these for the same commands. */
    @Test
    fun aCommandsDigestIsThePlugins() {
        assertEquals("17f69ae2697b61fd", PushNote.digest("rm -rf build"))
        assertEquals("d20c97f7d0825f2f", PushNote.digest("git push --force origin main"))
        assertEquals("3d490b0dae41032b", PushNote.digest("echo \"héllo\" && rm -rf ~/tmp"))
        // The plugin's `core.digest` of the question is what `h` holds.
        assertEquals("54e912433ad05e6b", PushNote.digest("Which branch should I deploy?"))
    }
}

class PushOfferTests {
    private val code = "j0DFrbaPJWJK5bIU6nZ6bslNgp09e14a0bpvPiE4KF8"

    @Test
    fun readsBothFormsOfThePairingLink() {
        val web = assertNotNull(PushOffer.fromUrl("https://redde.goosehouse.org/connect#push=$code"))
        assertContentEquals(Vector.offered, web.publicKey)
        assertNull(web.relay)
        val app = assertNotNull(PushOffer.fromUrl("redde://connect?push=$code"))
        assertEquals(web, app)
        val other = assertNotNull(PushOffer.fromUrl("https://redde.goosehouse.org/connect#push=$code&relay=http%3A%2F%2F127.0.0.1%3A18980"))
        assertEquals("http://127.0.0.1:18980", other.relay)
        assertNotEquals(web, other)
    }

    @Test
    fun findsTheLinkInWhatTheTerminalPrinted() {
        val printed = "Or open this link on the phone:\n\n  https://redde.goosehouse.org/connect#push=$code\n\nWaiting for the phone…"
        assertContentEquals(Vector.offered, PushOffer.fromText(printed)?.publicKey)
        assertNull(PushOffer.fromText("nothing here"))
    }

    @Test
    fun anythingElseIsNotAPairingLink() {
        for (link in listOf(
            "https://redde.goosehouse.org/connect#push=tooshort",
            "https://redde.goosehouse.org/connect#push=",
            "https://redde.goosehouse.org/connect#name=Home&api=https://hermes.example:8642",
            "https://example.com/connect#push=$code",
            "https://redde.goosehouse.org/elsewhere#push=$code",
            "redde://listen?push=$code",
        )) {
            assertNull(PushOffer.fromUrl(link), link)
        }
    }

    @Test
    fun aRelayIsTakenAsABaseAddress() {
        assertEquals("https://relay.example", PushOffer.fromUrl("redde://connect?push=$code&relay=https%3A%2F%2Frelay.example%2Fv1%2F")?.relay)
        assertNull(PushOffer.fromUrl("redde://connect?push=$code&relay=%2F")?.relay)
        // The first of a parameter given twice counts, whatever its case.
        assertEquals("https://a.example", PushOffer.fromUrl("redde://connect?PUSH=$code&relay=https%3A%2F%2Fa.example&relay=https%3A%2F%2Fb.example")?.relay)
        // A link longer than any the plugin prints is not read.
        assertNull(PushOffer.fromUrl("redde://connect?push=$code&pad=" + "x".repeat(600)))
    }
}
