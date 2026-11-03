package ch.nicolab.redde.transport

/** One Server-Sent Event. [data] has multi-line `data:` fields joined with "\n" per the spec. */
data class SseEvent(val event: String? = null, val data: String, val id: String? = null)

/**
 * Incremental SSE parser. Feed it text or lines as they arrive; it yields complete events. Handles
 * CRLF/LF, comment lines (`:`), and the optional space after the colon.
 */
class SseParser {
    private val buffer = StringBuilder()
    private var pendingEvent: String? = null
    private val pendingData = mutableListOf<String>()
    private var pendingId: String? = null

    fun feed(chunk: String): List<SseEvent> {
        buffer.append(chunk)
        val events = mutableListOf<SseEvent>()
        while (true) {
            val newline = buffer.indexOf("\n")
            if (newline < 0) break
            var line = buffer.substring(0, newline)
            buffer.delete(0, newline + 1)
            if (line.endsWith("\r")) line = line.dropLast(1)
            consume(line)?.let(events::add)
        }
        return events
    }

    /** One line as [LineBuffer] yields it (trailing newline included). */
    fun feedLine(line: String): SseEvent? {
        var l = line
        if (l.endsWith("\n")) l = l.dropLast(1)
        if (l.endsWith("\r")) l = l.dropLast(1)
        return consume(l)
    }

    /** Call at end of stream to flush a final event that wasn't terminated by a blank line. */
    fun finish(): SseEvent? {
        val events = mutableListOf<SseEvent>()
        if (buffer.isNotEmpty()) {
            val line = buffer.toString().removeSuffix("\r")
            buffer.setLength(0)
            consume(line)?.let(events::add)
        }
        consume("")?.let(events::add)
        return events.lastOrNull()
    }

    private fun consume(line: String): SseEvent? {
        if (line.isEmpty()) {
            if (pendingData.isEmpty() && pendingEvent == null) return null
            val event = SseEvent(pendingEvent, pendingData.joinToString("\n"), pendingId)
            pendingEvent = null
            pendingData.clear()
            return event
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field: String
        var value: String
        if (colon >= 0) {
            field = line.substring(0, colon)
            value = line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
        } else {
            field = line
            value = ""
        }
        when (field) {
            "event" -> pendingEvent = value
            "data" -> pendingData += value
            "id" -> pendingId = value
            else -> Unit // "retry" and unknown fields are ignored
        }
        return null
    }
}

/**
 * Splits a byte stream into lines, keeping the trailing newline so SSE's blank-line event
 * delimiters survive. Splitting bytes, not characters, keeps a multi-byte UTF-8 character that a
 * chunk boundary cuts in two whole.
 */
class LineBuffer {
    private var pending = ByteArray(0)

    /** Feed a chunk; returns every complete line it closes (each ending in "\n"). */
    fun append(data: ByteArray, length: Int = data.size): List<String> {
        val joined = ByteArray(pending.size + length)
        pending.copyInto(joined)
        data.copyInto(joined, pending.size, 0, length)
        val lines = mutableListOf<String>()
        var start = 0
        for (i in joined.indices) {
            if (joined[i] == '\n'.code.toByte()) {
                lines += String(joined, start, i + 1 - start, Charsets.UTF_8)
                start = i + 1
            }
        }
        pending = joined.copyOfRange(start, joined.size)
        return lines
    }

    /** Whatever is left without a newline (a final line the server didn't terminate). */
    fun flush(): String? {
        if (pending.isEmpty()) return null
        return String(pending, Charsets.UTF_8).also { pending = ByteArray(0) }
    }
}
