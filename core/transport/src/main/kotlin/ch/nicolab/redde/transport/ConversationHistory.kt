package ch.nicolab.redde.transport

import ch.nicolab.redde.model.AsideNote
import ch.nicolab.redde.model.CardNote
import ch.nicolab.redde.model.Delivered
import ch.nicolab.redde.model.Message
import ch.nicolab.redde.model.ReplyLanguage
import ch.nicolab.redde.model.ToolActivity
import ch.nicolab.redde.model.displayText
import ch.nicolab.redde.model.get
import ch.nicolab.redde.model.num
import ch.nicolab.redde.model.parseJsonOrNull
import ch.nicolab.redde.model.str
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * Server transcript rows mapped into the app's [Message] model (port of ConversationHistory.swift).
 * [fromServeRows] reads the Dashboard's history rows; [mapStored] reads the Hermes API's stored
 * messages. [onCardAnswer] hears of each card a message answered (the app records it).
 */
object ConversationHistory {
    private fun at(seconds: Double?): Instant = seconds?.let { Instant.ofEpochMilli((it * 1000).toLong()) } ?: Instant.now()

    /** The asides told with a message, as they showed, then the message itself as it should show. */
    private fun userRows(raw: String, displayKind: String?, displayText: String?, at: Instant, roster: List<RosterBot>, onCardAnswer: (String, String) -> Unit): List<Message> {
        CardNote.find(raw)?.let { (key, said) -> onCardAnswer(key, said) }
        val (text, asides) = AsideNote.split(BotMessages.withoutWatchHint(ReplyLanguage.stripNote(raw, keepingAsides = true)))
        val out = mutableListOf<Message>()
        for (aside in asides) {
            out += Message(role = Message.Role.USER, text = aside.asked, createdAt = at.minusSeconds(2),
                delivered = Delivered(Delivered.Kind.ASKED, profile = aside.agent, name = aside.name, noted = true))
            out += Message(role = Message.Role.USER, text = aside.answer, createdAt = at.minusSeconds(1),
                delivered = Delivered(Delivered.Kind.ANSWER, profile = aside.agent, name = aside.name, noted = true))
        }
        BotMessages.userMessage(text, displayKind, displayText, roster, at)?.let(out::add)
        return out
    }

    fun fromServeRows(rows: List<JsonElement>, roster: List<RosterBot> = emptyList(), onCardAnswer: (String, String) -> Unit = { _, _ -> }): List<Message> {
        val out = mutableListOf<Message>()
        /** Tool rows that came before the reply they belong to. */
        var waiting = mutableListOf<ToolActivity>()
        for (row in rows) {
            if (row["display_kind"].str == "hidden") continue
            val time = at(row["timestamp"].num)
            val text = row["text"].str ?: row["content"]?.displayText ?: ""
            when (row["role"].str) {
                "user" -> {
                    waiting = mutableListOf() // the tools of a turn that never got its reply
                    out += userRows(text, row["display_kind"].str, row["display_metadata"]["display_text"].str, time, roster, onCardAnswer)
                }
                "assistant" -> {
                    val reasoning = row["reasoning"].str ?: ""
                    if (text.isEmpty() && reasoning.isEmpty()) continue
                    out += Message(role = Message.Role.ASSISTANT, text = text, createdAt = time, reasoning = reasoning, tools = waiting)
                    waiting = mutableListOf()
                }
                "tool" -> {
                    val name = row["name"].str ?: continue
                    // The row carries the call; its result only for the edit tools.
                    val (callName, args) = ToolActivity.unwrapped(name, row["args"])
                    val tool = ToolActivity(name = callName, preview = row["context"].str?.ifEmpty { null }, status = ToolActivity.Status.COMPLETED,
                        args = ToolActivity.args(callName, args), output = ToolActivity.detail(row["content"]))
                    val last = out.lastOrNull()
                    if (last != null && last.role == Message.Role.ASSISTANT) {
                        out[out.lastIndex] = last.copy(tools = last.tools + tool)
                    } else {
                        // A model that calls a tool without a word first: the host lists the call before the reply.
                        waiting += tool
                    }
                }
            }
        }
        return out
    }

    fun mapStored(stored: List<StoredMessage>, roster: List<RosterBot> = emptyList(), onCardAnswer: (String, String) -> Unit = { _, _ -> }): List<Message> {
        val out = mutableListOf<Message>()
        for (row in stored) {
            if (row.displayKind == "hidden") continue
            val time = at(row.timestamp)
            when (row.role) {
                "user" -> out += userRows(row.content ?: "", row.displayKind, null, time, roster, onCardAnswer)
                "assistant" -> {
                    val tools = row.toolCalls.mapNotNull { call ->
                        val name = call.name ?: return@mapNotNull null
                        // Stored arguments are JSON, usually as text.
                        val arguments = call.arguments.str?.let(::parseJsonOrNull) ?: call.arguments
                        val (inner, args) = ToolActivity.unwrapped(name, arguments)
                        ToolActivity(name = inner, status = ToolActivity.Status.COMPLETED, args = ToolActivity.args(inner, args))
                    }
                    val message = Message(role = Message.Role.ASSISTANT, text = row.content ?: "", createdAt = time,
                        reasoning = row.reasoning ?: row.reasoningContent ?: "", tools = tools)
                    // Tool-call-only assistant rows fold into the next assistant text: one bubble with its
                    // tool chips, as it looked live.
                    val last = out.lastOrNull()
                    if (last != null && last.role == Message.Role.ASSISTANT && last.text.isEmpty() && last.tools.isNotEmpty()) {
                        val reasoning = if (message.reasoning.isEmpty()) last.reasoning
                        else last.reasoning + (if (last.reasoning.isEmpty()) "" else "\n\n") + message.reasoning
                        out[out.lastIndex] = last.copy(tools = last.tools + message.tools, text = message.text, reasoning = reasoning)
                    } else if (message.text.isNotEmpty() || message.tools.isNotEmpty()) {
                        out += message
                    }
                }
                "tool" -> {
                    // A tool's result belongs to the first call in the reply above still without one.
                    val last = out.lastOrNull() ?: continue
                    if (last.role != Message.Role.ASSISTANT) continue
                    val i = last.tools.indexOfFirst { it.output == null && (row.toolName == null || it.name == row.toolName) }
                        .takeIf { it >= 0 } ?: last.tools.indexOfFirst { it.output == null }.takeIf { it >= 0 } ?: continue
                    val tools = last.tools.toMutableList()
                    tools[i] = tools[i].copy(output = ToolActivity.detail(row.content))
                    out[out.lastIndex] = last.copy(tools = tools)
                }
            }
        }
        // Drop trailing tool-only assistant rows that never produced text (interrupted runs).
        return out.filterNot { it.role == Message.Role.ASSISTANT && it.text.isEmpty() && it.tools.isEmpty() }
    }
}
