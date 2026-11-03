package ch.nicolab.redde.transport

import ch.nicolab.redde.model.Attachment
import ch.nicolab.redde.model.AttachmentError
import ch.nicolab.redde.model.Message
import ch.nicolab.redde.model.TurnEvent
import ch.nicolab.redde.model.TurnRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A turn the app joins part-way ([HermesTransport.rejoin]). */
class RejoinedTurn(
    /** What was asked, when the host says. */
    val question: String,
    /** The turn from here on: what the reply says so far, then the rest. Ending it stops listening, not the turn. */
    val events: Flow<TurnEvent>,
    /** Stops the turn on the host. */
    val stop: () -> Unit,
    /** The conversation as the host has it, for once the turn has ended. */
    val transcript: suspend () -> List<Message>,
)

/** One way of running a turn against a backend. */
interface HermesTransport {
    /** Streams one turn. Cancelling the collection stops it (on the host too, where the host allows). */
    fun stream(request: TurnRequest): Flow<TurnEvent>

    /**
     * The turn under way in a stored session on the host, when the app isn't following it. Null when
     * nothing is running or waiting there. Only the Dashboard keeps a turn that can be joined.
     */
    suspend fun rejoin(stored: String): RejoinedTurn? = null

    /**
     * Calls [started] when the host starts a turn of its own in a stored session the app has open and
     * isn't following. Returns what ends the watch; null where the host starts no turns the app could
     * join (only the Dashboard does).
     */
    suspend fun watchHost(stored: String, started: () -> Unit): (() -> Unit)? = null
}

/**
 * OpenAI-style multimodal input: text alone stays a string; with attachments it becomes parts. Both
 * parts dialects (sessions `input_*`, chat-completions bare names with a nested image_url object)
 * share this: text files inline into the prose, and anything else is refused up front with the
 * transport's name in the error.
 */
object MultimodalInput {
    fun make(
        text: String,
        attachments: List<Attachment>,
        textType: String,
        imageType: String,
        nestedImageUrl: Boolean,
        transportName: String,
    ): JsonElement {
        if (attachments.isEmpty()) return JsonPrimitive(text)
        val parts = mutableListOf<JsonElement>()
        var prose = text
        for (att in attachments) {
            when (att.kind) {
                Attachment.Kind.IMAGE -> parts += buildJsonObject {
                    put("type", imageType)
                    if (nestedImageUrl) put("image_url", buildJsonObject { put("url", att.dataUrl) }) else put("image_url", att.dataUrl)
                }
                Attachment.Kind.TEXT -> att.promptAddendum?.let { prose += "\n\n$it" }
                Attachment.Kind.PDF, Attachment.Kind.OTHER -> throw AttachmentError.Unsupported(
                    kind = if (att.kind == Attachment.Kind.PDF) "PDF" else if (att.isVideo) "Video" else "File",
                    transport = transportName,
                )
            }
        }
        if (prose.isNotEmpty()) parts.add(0, buildJsonObject { put("type", textType); put("text", prose) })
        return JsonArray(parts)
    }
}
