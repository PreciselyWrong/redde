package ch.nicolab.redde.transport

import ch.nicolab.redde.model.TurnEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What a transport's decoder makes of one SSE event: the turn's events, and whether the stream is logically complete. */
data class Decoded(val events: List<TurnEvent> = emptyList(), val finished: Boolean = false) {
    companion object {
        val NOTHING = Decoded()
        val FINISHED = Decoded(finished = true)
        fun of(vararg events: TurnEvent) = Decoded(events.toList())
    }
}

/**
 * Shared plumbing for streamed replies: build a request, open the byte stream, surface non-2xx
 * bodies as errors, and hand SSE events to a per-transport decoder.
 */
object StreamingHttp {
    /** "Redde/<version> Android"; the app sets the version at start-up. */
    @Volatile
    var userAgent: String = "Redde/0 Android"

    val JSON = "application/json".toMediaType()

    /**
     * The client for agent turns: two minutes between bytes (agent tool calls can be slow), ten
     * minutes for the whole request, and no retry of a request the server may already have.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", userAgent).build() else request)
            }
            .build()
    }

    /**
     * Direct OpenAI-compatible servers: a local model with a long thread can think for well past ten
     * minutes, so the whole-request cap is an hour. The idle limit stays at two minutes.
     */
    val longClient: OkHttpClient by lazy { client.newBuilder().callTimeout(3600, TimeUnit.SECONDS).build() }

    /**
     * A streamed POST. [headers] are the person's own, for a reverse proxy in front of the server;
     * the request's own fields are set after them and win.
     */
    fun makeRequest(url: HttpUrl, apiKey: String?, headers: Map<String, String> = emptyMap(), body: JsonElement): Request {
        val builder = Request.Builder().url(url)
        for ((name, value) in headers) builder.header(name, value)
        builder.header("Content-Type", "application/json")
        builder.header("Accept", "text/event-stream")
        if (!apiKey.isNullOrEmpty()) builder.header("Authorization", "Bearer $apiKey")
        return builder.post(body.toString().toRequestBody(JSON)).build()
    }

    /**
     * Runs the request and emits decoded events, then [TurnEvent.Done]. [makeRequest] builds the body
     * on the network thread, not the caller's: base64 of a photo and the JSON of a long history are
     * not something to do on the UI thread. Cancelling the collector cancels the request.
     */
    fun run(
        client: OkHttpClient = this.client,
        decode: (SseEvent) -> Decoded,
        makeRequest: () -> Request,
    ): Flow<TurnEvent> = callbackFlow {
        val call = java.util.concurrent.atomic.AtomicReference<Call?>(null)
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread {
            var headersArrived = false
            try {
                val c = client.newCall(makeRequest())
                call.set(c)
                if (stopped.get()) c.cancel()
                val response: Response = try {
                    c.execute()
                } catch (e: IOException) {
                    throw if (c.isCanceled()) e else TransportError.Unreachable(e.message ?: e.toString(), e)
                }
                headersArrived = true
                response.use { r ->
                    if (!r.isSuccessful) {
                        val body = r.body.source().let { source ->
                            source.request(2000)
                            source.buffer.readUtf8(minOf(2000, source.buffer.size))
                        }
                        throw TransportError.Http(r.code, body)
                    }
                    val parser = SseParser()
                    val lines = LineBuffer()
                    val source = r.body.source()
                    val chunk = ByteArray(16 * 1024)
                    var finished = false
                    outer@ while (true) {
                        val n = source.read(chunk)
                        if (n < 0) break
                        for (line in lines.append(chunk, n)) {
                            val event = parser.feedLine(line) ?: continue
                            val decoded = decode(event)
                            decoded.events.forEach { trySendBlocking(it) }
                            if (decoded.finished) {
                                finished = true
                                break@outer
                            }
                        }
                    }
                    if (!finished) {
                        lines.flush()?.let { rest ->
                            for (event in parser.feed(rest + "\n")) decode(event).events.forEach { trySendBlocking(it) }
                        }
                        parser.finish()?.let { decode(it).events.forEach { e -> trySendBlocking(e) } }
                    }
                }
                trySendBlocking(TurnEvent.Done)
                close()
            } catch (e: Throwable) {
                if (call.get()?.isCanceled() == true || stopped.get()) {
                    close()
                } else if (headersArrived && e !is TransportError && NetworkFailure.isConnectivity(e)) {
                    // Once headers are back the server has the turn: a dropped connection now is not
                    // something to send again.
                    close(TransportError.StreamLost(e.message ?: e.toString(), e))
                } else {
                    close(e)
                }
            }
        }
        worker.isDaemon = true
        worker.name = "redde-stream"
        worker.start()
        awaitClose {
            stopped.set(true)
            call.get()?.cancel()
        }
    }
}
