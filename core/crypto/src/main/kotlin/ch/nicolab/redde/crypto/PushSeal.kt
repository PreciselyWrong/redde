package ch.nicolab.redde.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Notifications when Redde isn't running come from the person's own Hermes: a plugin there
 * (`companion/hermes-plugin/redde-push`) writes a short note when a reply finishes, when the
 * agent waits on a person (an approval, a question, a password, a secret) or when a turn fails,
 * seals it with a key only it and this device hold, and hands it to a relay that passes it on
 * unread.
 *
 * The key comes from pairing. `hermes redde-push pair` shows a link with a fresh public key; the
 * app answers with its own through the relay, and both derive the same secret (X25519, then
 * HKDF-SHA256). The relay sees two public keys and a box it can't open. `docs/push.md` has the
 * design; `core.py` in the plugin is the other end of everything in this file, byte for byte.
 */

/** Thrown when something sealed can't be opened: too short, the wrong key, or tampered with. */
class PushSealException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The two keys a pairing derives: the one notes are sealed with, and the one the answer is. */
class PushKeys(
    /** The key notes are sealed with, kept by both ends. */
    val note: ByteArray,
    /** The key the phone's pairing answer is sealed with, used once. */
    val box: ByteArray,
)

/** The phone's half of a pairing, as it is left for the plugin. */
class PushAnswer(
    /** This device's X25519 public key, base64url without padding. */
    val pub: String,
    /** The device's relay id, send key and name, sealed for the plugin, base64url without padding. */
    val box: String,
    /** What both ends will seal notes with. */
    val noteKey: ByteArray,
)

/** A note that opened, and the pairing it was sealed for. */
data class OpenedNote(val note: PushNote, val pairing: PushPairing)

/** X25519 pairing, key derivation and AES-256-GCM sealing, compatible with the plugin's `core.py`. */
object PushSeal {
    /** The associated data the pairing answer is sealed with. */
    val pairingAAD: ByteArray get() = "redde-push pairing".encodeToByteArray()

    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private const val KEY_ID_BYTES = 4

    private val random = SecureRandom()

    /** A fresh X25519 private key (32 bytes). */
    fun generatePrivateKey(): ByteArray = ByteArray(X25519.SCALAR_SIZE).also { X25519.generatePrivateKey(random, it) }

    /** The X25519 public key of [privateKey], as its 32 raw bytes. */
    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == X25519.SCALAR_SIZE) { "an X25519 private key is 32 bytes" }
        return ByteArray(X25519.POINT_SIZE).also { X25519.generatePublicKey(privateKey, 0, it, 0) }
    }

    /**
     * The X25519 shared secret of [privateKey] and [publicKey]. Throws [PushSealException] for a
     * public key of the wrong size, or one that gives the all-zero secret (a low-order point).
     */
    fun sharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == X25519.SCALAR_SIZE) { "an X25519 private key is 32 bytes" }
        if (publicKey.size != X25519.POINT_SIZE) throw PushSealException("an X25519 public key is 32 bytes")
        val shared = ByteArray(X25519.POINT_SIZE)
        if (!X25519.calculateAgreement(privateKey, 0, publicKey, 0, shared, 0)) {
            throw PushSealException("the public key gives no shared secret")
        }
        return shared
    }

    /**
     * Where on the relay the answer to an offer is left: named after the offered key, so the
     * plugin and the phone find the same place without telling the relay anything else.
     */
    fun rendezvous(offered: ByteArray): String =
        hex(sha256("redde-push rendezvous v1".encodeToByteArray() + offered).copyOf(16))

    /**
     * The key notes are sealed with and the key the pairing answer is sealed with: HKDF-SHA256
     * of the shared secret, salted with both public keys (offered, then answered), 64 bytes split
     * in two.
     */
    fun keys(shared: ByteArray, offered: ByteArray, answered: ByteArray): PushKeys {
        val material = hkdfSha256(shared, salt = offered + answered, info = "redde-push v1".encodeToByteArray(), length = 64)
        return PushKeys(note = material.copyOfRange(0, 32), box = material.copyOfRange(32, 64)).also { material.fill(0) }
    }

    /** Four bytes that say which pairing a note is from, and nothing about the key. */
    fun keyID(key: ByteArray): ByteArray = sha256("redde-push key id".encodeToByteArray() + key).copyOf(KEY_ID_BYTES)

    /** AES-256-GCM, as nonce, ciphertext, tag: what the plugin's `seal` writes. */
    fun seal(plaintext: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
                updateAAD(aad)
            }
        } catch (e: GeneralSecurityException) {
            throw PushSealException("can't seal with this key", e)
        } catch (e: IllegalArgumentException) {
            throw PushSealException("can't seal with this key", e)
        }
        return nonce + cipher.doFinal(plaintext)
    }

    /** Opens what [seal] (or the plugin's `seal`) wrote. Throws [PushSealException] when it can't. */
    fun open(sealed: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < NONCE_BYTES + TAG_BYTES) throw PushSealException("too short to be sealed")
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, sealed, 0, NONCE_BYTES))
                updateAAD(aad)
                doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
            }
        } catch (e: GeneralSecurityException) {
            throw PushSealException("doesn't open", e)
        } catch (e: IllegalArgumentException) {
            throw PushSealException("doesn't open", e)
        }
    }

    /**
     * The phone's half of a pairing: its public key, and its relay id and send key sealed for
     * the plugin. `noteKey` is what both ends will seal notes with. [kind]: what this device is
     * ("iphone", "ipad", "mac", "android"), so the plugin lists it as that and never rings a
     * computer; a plugin from before takes no notice. [privateKey] is a fresh one unless given.
     */
    fun answer(
        offered: ByteArray,
        deviceID: String,
        sendKey: String,
        name: String,
        kind: String? = null,
        privateKey: ByteArray = generatePrivateKey(),
    ): PushAnswer {
        val shared = sharedSecret(privateKey, offered)
        val mine = publicKey(privateKey)
        val keys = keys(shared, offered, mine)
        shared.fill(0)
        val said = buildMap {
            put("id", JsonPrimitive(deviceID))
            put("send", JsonPrimitive(sendKey))
            put("name", JsonPrimitive(name))
            if (kind != null) put("kind", JsonPrimitive(kind))
        }
        val inside = JsonObject(said).toString().encodeToByteArray()
        val box = seal(inside, keys.box, pairingAAD)
        keys.box.fill(0)
        return PushAnswer(pub = base64URL(mine), box = base64URL(box), noteKey = keys.note)
    }

    /**
     * Opens what a notification carries (`e`), with whichever pairing it was sealed for: four
     * bytes of key id, then the note sealed to that pairing's id. Null when none opens it, or
     * what opens isn't a note.
     */
    fun note(payload: String, pairings: List<PushPairing>): OpenedNote? {
        val blob = try {
            Base64.getDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (blob.size <= KEY_ID_BYTES + NONCE_BYTES + TAG_BYTES) return null
        val id = blob.copyOfRange(0, KEY_ID_BYTES)
        val sealed = blob.copyOfRange(KEY_ID_BYTES, blob.size)
        for (pairing in pairings) {
            if (!keyID(pairing.key).contentEquals(id)) continue
            val plain = try {
                open(sealed, pairing.key, pairing.id.encodeToByteArray())
            } catch (_: PushSealException) {
                continue
            }
            val note = PushNote.decode(plain) ?: continue
            return OpenedNote(note, pairing)
        }
        return null
    }

    /** Base64url without padding, as the plugin's `b64u`. */
    fun base64URL(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** The bytes of base64url text, padded or not (standard base64 reads too). Null when it isn't. */
    fun data(base64URL: String): ByteArray? {
        var standard = base64URL.replace('-', '+').replace('_', '/')
        standard += "=".repeat((4 - standard.length % 4) % 4)
        return try {
            Base64.getDecoder().decode(standard)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Lowercase hex. */
    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val v = byte.toInt() and 0xff
            append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
    }

    private const val HEX = "0123456789abcdef"

    internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}

/**
 * HKDF with HMAC-SHA256 (RFC 5869): extract with [salt] (zeros when empty), then expand with
 * [info] to [length] bytes.
 */
internal fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val hashLength = 32
    require(length in 0..255 * hashLength) { "HKDF-SHA256 gives at most ${255 * hashLength} bytes" }
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(hashLength) else salt, "HmacSHA256"))
    val prk = mac.doFinal(ikm)
    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    val out = ByteArray(length)
    var previous = ByteArray(0)
    var offset = 0
    var counter = 1
    while (offset < length) {
        mac.update(previous)
        mac.update(info)
        mac.update(counter.toByte())
        val block = mac.doFinal()
        val take = minOf(block.size, length - offset)
        block.copyInto(out, offset, 0, take)
        previous.fill(0)
        previous = block
        offset += take
        counter++
    }
    previous.fill(0)
    prk.fill(0)
    return out
}
