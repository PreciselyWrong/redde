package ch.nicolab.redde.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The JSON settings every server response and stored record is read with: servers add fields
 * freely and older records lack newer ones, so unknown keys are ignored and defaults fill gaps.
 */
val ReddeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

// Dynamic JSON access, the Kotlin side of the iOS `JSONValue`: a frame or reply is poked at
// field by field, and a field of the wrong type reads as absent rather than failing the frame.

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

/** A string field; a number or bool is not a string. */
val JsonElement?.str: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

val JsonElement?.bool: Boolean?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

val JsonElement?.num: Double?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/** Truncates like `Int(Double)`, but a non-finite or out-of-range number (a corrupt frame) is null. */
val JsonElement?.int: Int?
    get() {
        val n = num ?: return null
        if (!n.isFinite() || n <= Int.MIN_VALUE.toDouble() - 1 || n >= Int.MAX_VALUE.toDouble() + 1) return null
        return n.toInt()
    }

val JsonElement?.long: Long?
    get() {
        val n = num ?: return null
        if (!n.isFinite() || n <= -9.2e18 || n >= 9.2e18) return null
        return n.toLong()
    }

val JsonElement?.arr: JsonArray? get() = this as? JsonArray

val JsonElement?.obj: JsonObject? get() = this as? JsonObject

val JsonElement?.isNull: Boolean get() = this == null || this is JsonNull

/** Best-effort text for display: strings as they are, everything else as compact JSON. */
val JsonElement.displayText: String
    get() = str ?: toString()

/** Parses JSON text, or null when it isn't JSON. */
fun parseJsonOrNull(text: String): JsonElement? =
    try {
        ReddeJson.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

/**
 * Dates as the iOS app stored them (`JSONEncoder.dateEncodingStrategy = .iso8601`): ISO-8601 in UTC
 * with no fraction, e.g. `2026-10-31T09:12:44Z`. Reading accepts any ISO-8601 instant.
 */
object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)))

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
