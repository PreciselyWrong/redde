package ch.nicolab.redde.transport

import ch.nicolab.redde.model.ApprovalRequest
import ch.nicolab.redde.model.Interrupt
import ch.nicolab.redde.model.SubagentUpdate
import ch.nicolab.redde.model.TokenUsage
import ch.nicolab.redde.model.ToolActivity
import ch.nicolab.redde.model.TurnEvent
import ch.nicolab.redde.model.bool
import ch.nicolab.redde.model.get
import ch.nicolab.redde.model.int
import ch.nicolab.redde.model.parseJsonOrNull
import ch.nicolab.redde.model.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Hermes API turns as runs: started in the conversation's session (`POST /v1/runs`) and followed
 * through their events (`GET /v1/runs/{id}/events`). A run goes on whatever becomes of the app; the
 * chat stream's turn doesn't (Hermes stops it the moment its stream is cut), so the app can let go
 * of a run (another conversation opened, the app closed) and take it up again.
 *
 * Hermes 0.22 keeps a run's events for whoever asks again, from where they left off (`seq`, sent
 * back as `Last-Event-ID`). 0.21.x keeps them for the first listener only: after that the run is
 * asked after by its status (`GET /v1/runs/{id}`) until it ends, and the reply comes whole.
 */
class HermesRuns(
    private val baseUrl: HttpUrl,
    private val apiKey: String,
    private val headers: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = StreamingHttp.client,
    /** How often the status is looked at beside the events, and the waits; shorter in tests. */
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val watchEveryMs: Long = 5_000,
        val settleMs: Long = 3_000,
        /** A stretch of trouble reaching the host longer than this ends the following. */
        val patienceMs: Long = 30 * 60 * 1000L,
        val pollStartMs: Long = 1_000,
        val pollMaxMs: Long = 4_000,
    )

    /** How a run ended, by its own word. */
    sealed interface Ending {
        data object Completed : Ending
        /** Stopped, or interrupted (from another app, or Hermes restarting). */
        data object Stopped : Ending
        data class Failed(val message: String) : Ending
    }

    /** One event of a run, read. */
    data class RunDecoded(
        val events: List<TurnEvent> = emptyList(),
        /** Its number in the run's events (0.22). */
        val seq: Int? = null,
        val ending: Ending? = null,
    )

    private val api = HermesSessionsApi(baseUrl, apiKey, headers, client)

    /**
     * Starts a run; its id. Null when the gateway has no runs (the turn goes by the chat stream).
     * [key] is the message's own id: sent again, it finds the run it started instead of starting another.
     */
    suspend fun start(body: JsonElement, key: String?): String? {
        fun request(withKey: Boolean): Request {
            val r = StreamingHttp.makeRequest(baseUrl.resolvePath("v1/runs"), apiKey, headers, body).newBuilder()
                .header("Accept", "application/json")
            if (withKey && key != null) r.header("Idempotency-Key", key)
            return r.build()
        }
        val quick = client.newBuilder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        var (status, text) = quick.newCall(request(true)).await().use { it.code to it.body.string() }
        if (status == 409 && "idempotency" in text) {
            // Sent again with something changed meanwhile (another model picked): Hermes refuses the
            // key, so it goes as a message of its own.
            quick.newCall(request(false)).await().use { status = it.code; text = it.body.string() }
        }
        if (status == 404 || status == 405) return null
        if (status !in 200..299) throw TransportError.Http(status, text.take(300))
        return parseJsonOrNull(text)["run_id"].str ?: throw TransportError.Malformed("Hermes started no run")
    }

    /**
     * Follows a run to its end, handing on what it says: its events while they last; when they stop
     * short, its events again from where they were (0.22), else its status until it ends. Returns
     * when the run is over, or when the host no longer knows it; throws when it failed.
     */
    suspend fun follow(run: String, yield: (TurnEvent) -> Unit) {
        val progress = RunProgress(yield)
        var replayable = true
        var troubleSince: Long? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val ending: Ending? = if (replayable) watched(run, progress) else poll(run, progress) ?: return
                if (ending != null) return finish(ending)
                troubleSince = null
                // The events stopped short of the end. 0.22 numbers them and has them again from where
                // they stopped; 0.21.x doesn't, and a second listener may hear nothing.
                if (!progress.numbered) replayable = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportError.Http) {
                if (e.status == 404 && replayable) {
                    replayable = false // 0.21.x: the run's events were for the first listener only
                    continue
                }
                troubleSince = waitOut(e, troubleSince)
            } catch (e: Exception) {
                troubleSince = waitOut(e, troubleSince)
            }
        }
    }

    private suspend fun waitOut(error: Exception, troubleSince: Long?): Long {
        if (!isTransient(error)) throw error
        val now = System.currentTimeMillis()
        val since = troubleSince ?: now
        if (now - since >= timing.patienceMs) {
            throw TransportError.StreamLost("Hermes couldn't be reached for half an hour; the reply will be there when it can.")
        }
        delay(((now - since) / 2).coerceIn(timing.pollStartMs, 15_000))
        return since
    }

    /** Stops the run on the host (Stop), without waiting for the answer. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun stop(run: String) {
        GlobalScope.launch(Dispatchers.IO) { runCatching { api.requestJson("POST", "v1/runs/$run/stop") } }
    }

    /**
     * The run's events, with its status looked at every few seconds beside them: on 0.21.x a listener
     * that comes after another can be left with events that never come, so a run its status says is
     * over, and its events don't, ends there.
     */
    private suspend fun watched(run: String, progress: RunProgress): Ending? = coroutineScope {
        val events = async { events(run, progress) }
        val status = async { watchStatus(run, progress) }
        try {
            select {
                events.onAwait { it }
                status.onAwait { it }
            }
        } finally {
            events.cancel()
            status.cancel()
        }
    }

    private suspend fun watchStatus(run: String, progress: RunProgress): Ending? {
        while (true) {
            delay(timing.watchEveryMs)
            val state = try {
                api.requestJson("GET", "v1/runs/$run")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            val decoded = decodeStatus(state, run)
            if (decoded.ending == null) {
                // What it waits on a person for, should the events not say.
                decoded.events.forEach(progress::hand)
                continue
            }
            // Over: the events have a moment to say so themselves, with all they have.
            delay(timing.settleMs)
            decoded.events.forEach(progress::hand)
            return decoded.ending
        }
    }

    /** The run's events, from after the last one handed on. Null when they stopped before the run said how it ended. */
    private suspend fun events(run: String, progress: RunProgress): Ending? {
        val builder = Request.Builder().url(baseUrl.resolvePath("v1/runs/$run/events"))
        for ((name, value) in headers) builder.header(name, value)
        builder.header("Authorization", "Bearer $apiKey")
        builder.header("Accept", "text/event-stream")
        progress.cursor?.let { builder.header("Last-Event-ID", it.toString()) }
        return StreamingHttp.longClient.newCall(builder.build()).readBlocking { r ->
            if (!r.isSuccessful) throw TransportError.Http(r.code, r.bodyPrefix())
            val parser = SseParser()
            val lines = LineBuffer()
            val source = r.body.source()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = source.read(chunk)
                if (n < 0) break
                for (line in lines.append(chunk, n)) {
                    val sse = parser.feedLine(line) ?: continue
                    val decoded = decode(sse, run) ?: continue
                    if (!progress.take(decoded.seq)) continue
                    decoded.events.forEach(progress::hand)
                    decoded.ending?.let { return@readBlocking it }
                }
            }
            null
        }
    }

    /** The run's status, every second or few, until it ends. Null when the host doesn't know it. */
    private suspend fun poll(run: String, progress: RunProgress): Ending? {
        var wait = timing.pollStartMs.toDouble()
        while (true) {
            currentCoroutineContext().ensureActive()
            val state = try {
                api.requestJson("GET", "v1/runs/$run")
            } catch (e: TransportError.Http) {
                if (e.status == 404) return null else throw e
            }
            val decoded = decodeStatus(state, run)
            decoded.events.forEach(progress::hand)
            decoded.ending?.let { return it }
            delay(wait.toLong())
            wait = minOf(wait * 1.5, timing.pollMaxMs.toDouble())
        }
    }

    /**
     * Where following a run has got to, shared by its events and its status: the last event handed
     * on, and the approvals already put to the person.
     */
    class RunProgress(private val yield: (TurnEvent) -> Unit) {
        private var lastSeq: Int? = null
        private val asked = HashSet<String>()

        /** The last numbered event handed on (0.22). */
        val cursor: Int? @Synchronized get() = lastSeq

        /** The events came numbered: Hermes 0.22, which has them again from where they stopped. */
        val numbered: Boolean get() = cursor != null

        /** Whether an event numbered [seq] is new; an event asked for again from before isn't. */
        @Synchronized
        fun take(seq: Int?): Boolean {
            if (seq == null) return true
            lastSeq?.let { if (seq <= it) return false }
            lastSeq = seq
            return true
        }

        /** Hands an event on; an approval only once, however often the host says it waits. */
        fun hand(event: TurnEvent) {
            if (event is TurnEvent.Interrupted && event.interrupt is Interrupt.Approval) {
                val fresh = synchronized(this) { asked.add(event.interrupt.id) }
                if (!fresh) return
            }
            yield(event)
        }
    }

    companion object {
        private fun finish(ending: Ending) {
            when (ending) {
                Ending.Completed -> return
                Ending.Stopped -> throw TransportError.Malformed("The agent stopped before it finished (stopped elsewhere, or Hermes restarted).")
                is Ending.Failed -> throw TransportError.Malformed(ending.message)
            }
        }

        /** Trouble that passes: the connection, the host restarting behind a proxy, a timeout. */
        fun isTransient(error: Throwable): Boolean {
            if (NetworkFailure.isConnectivity(error)) return true
            if (error is TransportError.StreamLost) return true
            if (error is TransportError.Http) return error.status in setOf(408, 429, 500, 502, 503, 504)
            if (error is java.io.InterruptedIOException || error is javax.net.ssl.SSLException) return true
            return false
        }

        /** One event of a run's stream (`data: {"event": …}`; 0.22 numbers them). */
        fun decode(sse: SseEvent, run: String): RunDecoded? {
            val json = parseJsonOrNull(sse.data) ?: return null
            if (json !is kotlinx.serialization.json.JsonObject) return null
            val name = json["event"].str ?: sse.event ?: return null
            val seq = json["seq"].int ?: sse.id?.toIntOrNull()
            val events: List<TurnEvent> = when (name) {
                "message.delta" -> listOfNotNull(json["delta"].str?.takeIf { it.isNotEmpty() }?.let { TurnEvent.TextDelta(it) })
                "reasoning.available" -> listOfNotNull(json["text"].str?.takeIf { it.isNotEmpty() }?.let { TurnEvent.ReasoningDelta(it) })
                "tool.started" -> {
                    val (tool, args) = ToolActivity.unwrapped(json["tool"].str ?: json["tool_name"].str ?: "tool", json["args"])
                    listOf(TurnEvent.ToolStarted(tool, json["preview"].str?.ifEmpty { null }, ToolActivity.args(tool, args)))
                }
                "tool.completed", "tool.failed" -> {
                    val tool = ToolActivity.unwrapped(json["tool"].str ?: json["tool_name"].str ?: "", json["args"]).first
                    listOf(TurnEvent.ToolFinished(
                        name = if (tool == "tool_call") "" else tool,
                        failed = name == "tool.failed" || json["error"].bool == true || json["is_error"].bool == true,
                        output = json["preview"].str?.ifEmpty { null },
                    ))
                }
                "subagent.start", "subagent.tool", "subagent.progress", "subagent.complete" ->
                    listOfNotNull(SubagentUpdate.fromEvent(name, json)?.let { TurnEvent.Subagent(it) })
                "approval.request" -> {
                    val request = ApprovalRequest.fromPayload(json)
                    if (request.id.isEmpty()) emptyList() else listOf(TurnEvent.Interrupted(Interrupt.Approval(request), run))
                }
                "run.completed", "run.failed", "run.cancelled", "run.interrupted" ->
                    return decodeStatus(json, run, ending = name).copy(seq = seq)
                else -> emptyList()
            }
            return RunDecoded(events, seq)
        }

        /**
         * A run's status (`GET /v1/runs/{id}`), or one of its closing events: the reply whole, what it
         * cost, and how it ended; or what it waits on a person for.
         */
        fun decodeStatus(json: JsonElement?, run: String, ending: String? = null): RunDecoded {
            val status = ending?.removePrefix("run.") ?: json["status"].str ?: ""
            val events = mutableListOf<TurnEvent>()
            var end: Ending? = null
            when (status) {
                "completed" -> {
                    json["output"].str?.takeIf { it.isNotEmpty() }?.let { events += TurnEvent.TextFinal(it) }
                    val usage = json["usage"]
                    val input = (usage["input_tokens"] ?: usage["prompt_tokens"]).int
                    if (input != null) events += TurnEvent.Usage(TokenUsage(input, (usage["output_tokens"] ?: usage["completion_tokens"]).int ?: 0))
                    end = Ending.Completed
                }
                "failed" -> {
                    val message = json["error"].str ?: "The run failed"
                    if (HermesSessionsTransport.isLockBucketMismatch(message)) {
                        // The pre-0.21.4 check on a named endpoint, wrong about a turn that ran where it should.
                        json["output"].str?.takeIf { it.isNotEmpty() }?.let { events += TurnEvent.TextFinal(it) }
                        end = Ending.Completed
                    } else {
                        end = Ending.Failed(message)
                    }
                }
                "cancelled", "interrupted" -> end = Ending.Stopped
                "waiting_for_approval" -> json["approval"]?.let { approval ->
                    val request = ApprovalRequest.fromPayload(approval)
                    if (request.id.isNotEmpty()) events += TurnEvent.Interrupted(Interrupt.Approval(request), run)
                }
            }
            return RunDecoded(events, ending = end)
        }
    }
}
