package ch.nicolab.redde.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Runs the call without blocking the caller; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

/**
 * Runs the call and reads its response with [block] on an IO thread (a stream read line by line);
 * cancelling the coroutine cancels the call, which ends a read that is waiting.
 */
suspend fun <T> Call.readBlocking(block: (Response) -> T): T = coroutineScope {
    val work = async(Dispatchers.IO) { execute().use(block) }
    try {
        work.await()
    } catch (e: CancellationException) {
        cancel()
        throw e
    }
}

/** [path] with query items, under this base ([resolvePath]). */
fun HttpUrl.resolve(path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl {
    val builder = resolvePath(path).newBuilder()
    for ((k, v) in query) builder.addQueryParameter(k, v)
    return builder.build()
}

/** At most [max] characters of a response body, for an error. */
fun Response.bodyPrefix(max: Int = 300): String {
    val source = body.source()
    source.request(max.toLong())
    return source.buffer.readUtf8(minOf(max.toLong(), source.buffer.size))
}
