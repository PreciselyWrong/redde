package ch.nicolab.redde.crypto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.util.Base64

/** One Hermes this device is paired with. */
@Serializable
data class PushPairing(
    /** The relay's name for this device as that Hermes knows it. Not a secret. */
    val id: String,
    /**
     * What the plugin shows the relay to be let through, and the app to change or remove the
     * registration.
     */
    val sendKey: String,
    /** The key notes are sealed with. The relay never has it. */
    @Serializable(with = Base64ByteArraySerializer::class)
    val key: ByteArray,
    val relay: String,
    /** The machine's name, from the first note that arrives. Empty until then. */
    val host: String = "",
    /** When it was paired, in whole seconds since 1970, so a copy read back equals the one written. */
    val pairedAt: Long = System.currentTimeMillis() / 1000,
    /** A note has arrived and opened: the plugin took the answer and derived the same key. */
    val confirmed: Boolean = false,
    /**
     * The plugin said so itself, when the pairing was made over the Dashboard: it opened the
     * answer, so it has the key, whether or not a note has got through yet. Null for a pairing
     * saved before this existed.
     */
    val accepted: Boolean? = null,
) {
    /** The plugin has this device: by its own word, or by a note that proves it. */
    val isPaired: Boolean get() = confirmed || accepted == true

    override fun equals(other: Any?): Boolean =
        other is PushPairing && id == other.id && sendKey == other.sendKey && key.contentEquals(other.key) &&
            relay == other.relay && host == other.host && pairedAt == other.pairedAt &&
            confirmed == other.confirmed && accepted == other.accepted

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + sendKey.hashCode()
        result = 31 * result + key.contentHashCode()
        result = 31 * result + relay.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + pairedAt.hashCode()
        result = 31 * result + confirmed.hashCode()
        result = 31 * result + (accepted?.hashCode() ?: 0)
        return result
    }

    /** Leaves the secrets out, so a pairing can be logged. */
    override fun toString(): String =
        "PushPairing(id=$id, relay=$relay, host=$host, pairedAt=$pairedAt, confirmed=$confirmed, accepted=$accepted)"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val listSerializer = ListSerializer(serializer())

        /** Pairings as the JSON a store keeps (the key as standard base64). */
        fun encodeList(pairings: List<PushPairing>): String = json.encodeToString(listSerializer, pairings)

        /** Pairings from what [encodeList] wrote; empty when it can't be read. */
        fun decodeList(text: String): List<PushPairing> = try {
            json.decodeFromString(listSerializer, text)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }
}

/**
 * Where the pairings are kept. On a device: storage the notification handler can read too, and
 * readable while the device is locked, since a notification can arrive then. It never leaves
 * the device. Platforms provide [load] and [save]; [confirm] is the same everywhere.
 */
interface PushPairingStore {
    /** Every pairing kept; empty when there are none or they can't be read. */
    fun load(): List<PushPairing>

    /** Keeps exactly [pairings] (none removes what is kept). False when it couldn't. */
    fun save(pairings: List<PushPairing>): Boolean

    /** A note from this pairing arrived and opened: it works, and the machine has a name. */
    fun confirm(pairing: PushPairing, host: String?) {
        val all = load().toMutableList()
        val index = all.indexOfFirst { it.id == pairing.id }
        if (index < 0) return
        val name = host ?: all[index].host
        if (all[index].confirmed && all[index].host == name) return
        all[index] = all[index].copy(confirmed = true, host = name)
        save(all)
    }
}

/** A [ByteArray] in JSON as standard base64, as Swift's `JSONEncoder` writes `Data`. */
internal object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("ch.nicolab.redde.crypto.Base64Bytes", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.getEncoder().encodeToString(value))
    override fun deserialize(decoder: Decoder): ByteArray = Base64.getDecoder().decode(decoder.decodeString())
}
