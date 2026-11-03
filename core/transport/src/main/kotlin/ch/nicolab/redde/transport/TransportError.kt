package ch.nicolab.redde.transport

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Why a turn or a request failed. The app words these for the person (they were localized strings
 * on iOS); [message] is a plain English fallback for logs.
 */
sealed class TransportError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class BadUrl : TransportError("The endpoint URL is invalid.")

    /** No key is stored. */
    class MissingApiKey : TransportError("No API key is stored.")

    /** The key may be there, but the secret store can't be read yet (the device hasn't been unlocked). */
    class KeystoreLocked : TransportError("The secret store is locked.")

    class Http(val status: Int, val body: String) : TransportError("Server returned HTTP $status." + if (body.isEmpty()) "" else " " + body.take(300)) {
        /** The Hermes API's /p/<profile>/ routes: the gateway doesn't serve that profile. */
        val isUnknownProfile get() = status == 404 && "Unknown or unconfigured profile" in body

        /** The key was refused (also what a profile's routes answer to the main key). */
        val isKeyRejected get() = status == 401 && "gateway_auth_failed" in body
    }

    class Malformed(val detail: String) : TransportError("Unexpected response: $detail")

    /** The server couldn't be reached before the turn was handed over; safe to send again later. */
    class Unreachable(val detail: String, cause: Throwable? = null) : TransportError(detail, cause)

    /** The connection dropped after the server had the request; sending again could repeat it. */
    class StreamLost(val detail: String, cause: Throwable? = null) : TransportError("The connection dropped mid-reply: $detail", cause)
}

/**
 * Tells a connection that never reached the server apart from every other failure, so the
 * conversation can hold the message and send it later instead of reporting an error.
 */
object NetworkFailure {
    fun isConnectivity(error: Throwable): Boolean {
        var e: Throwable? = error
        while (e != null) {
            when (e) {
                is TransportError.Unreachable -> return true
                is TransportError -> return false
                is UnknownHostException, is ConnectException, is NoRouteToHostException,
                is SocketTimeoutException, is SocketException -> return true
                is IOException -> if (e.message?.let { m -> CONNECTIVITY_WORDS.any { it in m } } == true) return true
            }
            e = e.cause
        }
        return false
    }

    private val CONNECTIVITY_WORDS = listOf("unexpected end of stream", "Connection reset", "Broken pipe")
}
