package ch.nicolab.redde.transport

import ch.nicolab.redde.model.arr
import ch.nicolab.redde.model.bool
import ch.nicolab.redde.model.get
import ch.nicolab.redde.model.parseJsonOrNull
import ch.nicolab.redde.model.str
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Probes each backend the way the transports use it, so Setup and Settings can say "reachable,
 * credentials accepted" before the person sends anything (port of ConnectionTester.swift). The
 * outcomes are typed; the app words them.
 */
class ConnectionTester(baseClient: OkHttpClient = StreamingHttp.client) {
    sealed interface Outcome {
        val isOk: Boolean get() = this is Ok

        sealed interface Ok : Outcome
        sealed interface Failed : Outcome

        /** Hermes API: connected, key accepted. */
        data object KeyAccepted : Ok
        /** Dashboard: connected and logged in ([open]: it has no sign-in). */
        data class DashboardConnected(val version: String?, val open: Boolean) : Ok
        /** OpenAI-compatible: connected, [models] listed (0 when it lists none). */
        data class ModelsListed(val models: Int) : Ok

        /** Nothing answered as that kind of server at [host]. */
        data class NoServer(val host: String) : Failed
        /** An access gate (Cloudflare Access, Pangolin, a proxy) turned the check away. [headersSent]: some were configured. */
        data class Gated(val host: String, val status: Int, val headersSent: Boolean) : Failed
        /** Reachable, but no key is set. */
        data object NeedsKey : Failed
        /** Reachable, but the key check didn't complete. */
        data object KeyCheckIncomplete : Failed
        data object KeyRejected : Failed
        /** The server answered [status] where it should have answered 200. */
        data class HttpStatus(val status: Int) : Failed
        /** Dashboard reachable, but the login failed. */
        data class LoginFailed(val version: String?, val error: Throwable) : Failed
        /** The endpoint doesn't list the chosen model. */
        data class ModelMissing(val model: String) : Failed
    }

    /** Never follows the access gate's login redirect: the status is the diagnosis. */
    private val client = baseClient.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private suspend fun get(url: HttpUrl, key: String?, headers: Map<String, String> = emptyMap()): Pair<String, Int> {
        val b = Request.Builder().url(url)
        for ((k, v) in headers) b.header(k, v)
        if (!key.isNullOrEmpty()) b.header("Authorization", "Bearer $key")
        return client.newCall(b.build()).await().use { it.body.string() to it.code }
    }

    private suspend fun tryGet(url: HttpUrl, key: String?, headers: Map<String, String> = emptyMap()) =
        runCatching { get(url, key, headers) }.getOrNull()

    /** Hermes API server: /health, then /v1/models with the key. */
    suspend fun hermesApi(url: HttpUrl, apiKey: String?, headers: Map<String, String> = emptyMap()): Outcome {
        val health = tryGet(url.resolvePath("health"), null, headers)?.second ?: return Outcome.NoServer(url.host)
        if (health in GATE_STATUSES) return Outcome.Gated(url.host, health, headers.isNotEmpty())
        if (health != 200) return Outcome.NoServer(url.host)
        if (apiKey.isNullOrEmpty()) return Outcome.NeedsKey
        val status = tryGet(url.resolvePath("v1/models"), apiKey, headers)?.second ?: return Outcome.KeyCheckIncomplete
        return when (status) {
            200 -> Outcome.KeyAccepted
            401, 403 -> Outcome.KeyRejected
            else -> Outcome.HttpStatus(status)
        }
    }

    /** Hermes Dashboard: /api/status (public), then a real login through [client]. */
    suspend fun hermesServe(url: HttpUrl, accessHeaders: Map<String, String>, client: HermesServeClient): Outcome {
        val (data, status) = tryGet(url.resolvePath("api/status"), null, accessHeaders) ?: return Outcome.NoServer(url.host)
        if (status == 403 || status == 302) return Outcome.Gated(url.host, status, accessHeaders.isNotEmpty())
        if (status != 200) return Outcome.HttpStatus(status)
        val json = parseJsonOrNull(data)
        val version = json["version"].str
        val open = json["auth_required"].bool != true
        return try {
            client.ensureLoggedIn()
            Outcome.DashboardConnected(version, open)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Outcome.LoginFailed(version, e)
        }
    }

    /**
     * Whether the Dashboard at [url] asks for a login, for the form to show the right fields; null when
     * nothing answered as a Dashboard.
     */
    suspend fun dashboardAsksForLogin(url: HttpUrl, accessHeaders: Map<String, String>): Boolean? {
        val (data, status) = tryGet(url.resolvePath("api/status"), null, accessHeaders) ?: return null
        if (status != 200) return null
        val json = parseJsonOrNull(data) ?: return null
        if (json["version"] == null && json["auth_required"] == null) return null
        return json["auth_required"].bool == true
    }

    /** OpenAI-compatible endpoint: /v1/models, with the optional key; and whether it lists [model]. */
    suspend fun openAiCompatible(url: HttpUrl, apiKey: String?, model: String): Outcome {
        val (data, status) = tryGet(url.resolvePath("v1/models"), apiKey) ?: return Outcome.NoServer(url.host)
        if (status != 200) return if (status == 401 || status == 403) Outcome.KeyRejected else Outcome.HttpStatus(status)
        val ids = parseJsonOrNull(data)["data"].arr?.mapNotNull { it["id"].str } ?: emptyList()
        if (model.isNotEmpty() && ids.isNotEmpty() && model !in ids) return Outcome.ModelMissing(model)
        return Outcome.ModelsListed(ids.size)
    }

    companion object {
        /** What a sign-in gate in front answers instead of the server. */
        val GATE_STATUSES = setOf(302, 303, 307, 401, 403)

        /** The models an OpenAI-compatible server lists (`v1/models`), and those llama-swap says are loaded. */
        fun listedModels(json: String): List<Pair<String, Boolean>> =
            parseJsonOrNull(json)["data"].arr?.mapNotNull { m -> m["id"].str?.let { it to (m["status"]["value"].str == "loaded") } } ?: emptyList()

    }
}
