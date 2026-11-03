package ch.nicolab.redde.transport

import ch.nicolab.redde.model.Attachment
import ch.nicolab.redde.model.CalendarChoice
import ch.nicolab.redde.model.CallNote
import ch.nicolab.redde.model.CarBrevity
import ch.nicolab.redde.model.CurrentLocation
import ch.nicolab.redde.model.LiveVoiceNote
import ch.nicolab.redde.model.MeasureUnits
import ch.nicolab.redde.model.ReddeJson
import ch.nicolab.redde.model.ReplyLanguage
import ch.nicolab.redde.model.TokenUsage
import ch.nicolab.redde.model.TurnEvent
import ch.nicolab.redde.model.TurnRequest
import ch.nicolab.redde.model.arr
import ch.nicolab.redde.model.get
import ch.nicolab.redde.model.int
import ch.nicolab.redde.model.str
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/**
 * OpenAI-compatible `/v1/chat/completions` streaming. Stateless: the full history is sent each turn.
 * Earlier turns are never rewritten so the server's prefix cache stays warm.
 */
class ChatCompletionsTransport(private val baseUrl: HttpUrl, private val apiKey: String?) : HermesTransport {

    override fun stream(request: TurnRequest): Flow<TurnEvent> =
        StreamingHttp.run(client = StreamingHttp.longClient, decode = ::decode) {
            StreamingHttp.makeRequest(baseUrl.resolvePath("v1/chat/completions"), apiKey, body = body(request, baseUrl))
        }

    companion object {
        const val TRANSPORT_NAME = "the OpenAI-compatible server"

        /** The request body for a turn. */
        fun body(request: TurnRequest, baseUrl: HttpUrl): JsonElement {
            val system = listOfNotNull(
                request.instructions?.ifEmpty { null },
                request.replyLanguage?.let(ReplyLanguage::instruction),
                request.units?.let(MeasureUnits::instruction),
                request.calendars?.let(CalendarChoice::instruction),
                request.location?.let(CurrentLocation::instruction),
                if (request.brief) CarBrevity.INSTRUCTION else null,
                request.live?.let { LiveVoiceNote.instruction(it.context) },
                request.answeringCall?.let(CallNote::instruction),
            )
            return buildJsonObject {
                put("model", request.model?.ifEmpty { null } ?: "") // no pick: the server's default
                put("messages", buildJsonArray {
                    if (system.isNotEmpty()) add(buildJsonObject { put("role", "system"); put("content", system.joinToString("\n\n")) })
                    for (m in request.history) add(buildJsonObject { put("role", m.role.name.lowercase()); put("content", m.text) })
                    add(buildJsonObject { put("role", "user"); put("content", makeContent(request.userText, request.attachments)) })
                })
                put("stream", true)
                put("stream_options", buildJsonObject { put("include_usage", true) })
                // llama.cpp: stream `prompt_progress` chunks during prefill. Only to self-hosted
                // endpoints: OpenAI rejects requests carrying unrecognized arguments.
                if (isSelfHosted(baseUrl)) put("return_progress", true)
                wireEffort(request.reasoningEffort)?.let { put("reasoning_effort", it) }
                thinkingKwargs(request.reasoningEffort, baseUrl)?.let { put("chat_template_kwargs", it) }
            }
        }

        /**
         * The level as it goes on the wire in `reasoning_effort`: null for Default and None. Cloud
         * reasoning models and local servers that grade effort honour it; most cloud models reject "none".
         */
        fun wireEffort(effort: String?): String? = effort?.ifEmpty { null }?.lowercase()?.takeIf { it != "none" }

        /**
         * Template switches for a picked level on a self-hosted endpoint, whatever the model: any level
         * turns thinking on where the template has a switch, None turns it off, and the level itself
         * goes to templates that grade it. Default leaves the server's settings alone.
         */
        fun thinkingKwargs(effort: String?, baseUrl: HttpUrl): JsonObject? {
            if (!isSelfHosted(baseUrl)) return null
            val level = effort?.ifEmpty { null }?.lowercase() ?: return null
            return buildJsonObject {
                put("enable_thinking", level != "none")
                put("reasoning_effort", level)
            }
        }

        /** Whether the endpoint looks self-hosted (localhost, LAN, tailnet, or a private-looking name). */
        fun isSelfHosted(url: HttpUrl): Boolean {
            val host = url.host.lowercase()
            if ('.' !in host && ':' !in host) return true // single-label: MagicDNS or a LAN name
            if (host == "localhost" || listOf(".local", ".ts.net", ".internal", ".lan", ".home.arpa").any(host::endsWith)) return true
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            return parts[0] == 127 || parts[0] == 10 ||
                (parts[0] == 192 && parts[1] == 168) ||
                (parts[0] == 172 && parts[1] in 16..31) ||
                (parts[0] == 100 && parts[1] in 64..127) // CGNAT, i.e. Tailscale
        }

        /** llama.cpp takes OpenAI vision parts when the model has a projector; text files are inlined. */
        fun makeContent(text: String, attachments: List<Attachment>): JsonElement =
            MultimodalInput.make(text, attachments, textType = "text", imageType = "image_url", nestedImageUrl = true, transportName = TRANSPORT_NAME)

        fun decode(sse: SseEvent): Decoded {
            if (sse.data == "[DONE]") return Decoded.FINISHED
            val json = try {
                ReddeJson.parseToJsonElement(sse.data)
            } catch (e: Exception) {
                throw TransportError.Malformed(e.message ?: "not JSON")
            }
            if (sse.event == "hermes.tool.progress") {
                // Hermes gateway extension: tool activity interleaved with content chunks.
                val name = json["tool"].str ?: json["label"].str ?: "tool"
                return when (json["status"].str) {
                    "completed" -> Decoded.of(TurnEvent.ToolFinished(name, failed = false))
                    "failed", "error" -> Decoded.of(TurnEvent.ToolFinished(name, failed = true))
                    else -> Decoded.of(TurnEvent.ToolStarted(name, json["label"].str))
                }
            }
            json["error"]["message"].str?.let { throw TransportError.Malformed(it) }
            val events = mutableListOf<TurnEvent>()
            val progress = json["prompt_progress"]
            val total = progress["total"].int
            if (total != null && total > 0) {
                events += TurnEvent.Prefill(processed = progress["processed"].int ?: 0, total = total, cached = progress["cache"].int ?: 0)
            }
            // The stream ends on `[DONE]`, not on finish_reason: providers (OpenRouter, llama.cpp)
            // send the usage chunk after the chunk that carries finish_reason.
            val usage = json["usage"]
            val input = usage["prompt_tokens"].int
            if (input != null) {
                val output = usage["completion_tokens"].int ?: 0
                events += TurnEvent.Usage(TokenUsage(input, output, usage["prompt_tokens_details"]["cached_tokens"].int, contextUsed = input + output))
            }
            for (choice in json["choices"].arr ?: emptyList()) {
                choice["delta"]["content"].str?.takeIf { it.isNotEmpty() }?.let { events += TurnEvent.TextDelta(it) }
                choice["delta"]["reasoning_content"].str?.takeIf { it.isNotEmpty() }?.let { events += TurnEvent.ReasoningDelta(it) }
            }
            return Decoded(events)
        }
    }
}

/** [path] (no leading slash) under this base, keeping the base's own path: `http://h/llm` + `v1/x` is `http://h/llm/v1/x`. */
fun HttpUrl.resolvePath(path: String): HttpUrl {
    val builder = newBuilder()
    if (encodedPath == "/") builder.encodedPath("/")
    // Drop the empty last segment of a base that ends in "/".
    if (pathSegments.lastOrNull() == "") builder.removePathSegment(pathSize - 1)
    path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
    return builder.build()
}

