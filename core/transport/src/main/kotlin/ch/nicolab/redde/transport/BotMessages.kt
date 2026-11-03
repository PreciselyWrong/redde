package ch.nicolab.redde.transport

import ch.nicolab.redde.model.Delivered
import ch.nicolab.redde.model.Message
import ch.nicolab.redde.model.PageTurn
import ch.nicolab.redde.model.get
import ch.nicolab.redde.model.parseJsonOrNull
import ch.nicolab.redde.model.str
import java.time.Instant

/** A bot (a Hermes profile in Bot Mode) as the transcript reader needs it. */
data class RosterBot(
    /** The profile's name. */
    val profile: String,
    val handle: String,
    /** What it is called in the app. */
    val label: String,
    val title: String? = null,
    val displayName: String? = null,
) {
    /** Everything a bot answers to: its handle, its profile's name, and its friendly names. */
    val forms: Set<String>
        get() = buildSet {
            add(handle.lowercase())
            add(profile.lowercase())
            for (friendly in listOf(title, displayName)) addAll(BotMessages.nameForms(friendly))
        }
}

/** A bot a message names: its profile, and what it called itself. */
data class BotRef(val profile: String, val name: String)

/** Reading bots' messages to each other out of a transcript, as Hermes Desktop does (port of BotMessages.swift). */
object BotMessages {
    data class Inbound(val name: String, val handle: String?, val body: String)

    private val RESERVED = setOf("all", "everyone", "user", "default", "hermes")

    private val INBOUND = Regex("""Message from (?:🤖\s*)?([^:\n(]{1,64}?)(?:\s*\(@([a-z0-9][a-z0-9_-]{0,63})(?:@[A-Za-z0-9][A-Za-z0-9_-]{0,63})?\))?:\s*([\s\S]*)""")
    private val INBOUND_OLD = Regex("""\[Message from agent '([^']{1,64})'\]\s*([\s\S]*)""")
    private val PROCESS = Regex("""\[(?:IMPORTANT: )?Background process (\S+) ([^\n]*?)\.?\n(?:[^\n]*\n)?Command: ([^\n]*)\nOutput:\n([\s\S]*)\]""")
    private val PROFILE_FLAG = Regex("""(?:^|\s)-p\s+'?([A-Za-z0-9][A-Za-z0-9_-]*)""")
    private val OUT_OF_BAND = Regex("""\[OUT-OF-BAND USER MESSAGE[^\]]*\][\s\S]*?(\[/OUT-OF-BAND USER MESSAGE\]|$)""")
    private val STEER = Regex("""\s*\[OUT-OF-BAND USER MESSAGE[^\]]*\]\s*([\s\S]*?)\s*(?:\[/OUT-OF-BAND USER MESSAGE\])?\s*""")
    private val ROUTINE = Regex("""\[Cronjob "([^"\n]*)" output[^\]\n]*\]\s*([\s\S]*)""")

    /** The taggable forms of a friendly name ("Research Buddy"): "research-buddy" and "researchbuddy". */
    fun nameForms(value: String?): List<String> {
        val name = (value ?: "").trim().lowercase()
        if (name.isEmpty()) return emptyList()
        val slug = name.replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
        val collapsed = name.replace(Regex("[^a-z0-9_-]+"), "")
        return listOf(slug, collapsed).distinct().filter { it !in RESERVED && Regex("[a-z0-9][a-z0-9_-]*").matches(it) }
    }

    /**
     * "Message from 🤖 Research Buddy (@research-buddy): …" (0.21.5 and later name the bot), and the
     * older "[Message from agent 'scout'] …": Hermes Desktop's pattern.
     */
    fun inbound(text: String): Inbound? {
        INBOUND.matchEntire(text)?.let { m ->
            return Inbound(m.groupValues[1].trim(), m.groups[2]?.value, m.groupValues[3])
        }
        INBOUND_OLD.matchEntire(text)?.let { m -> return Inbound(m.groupValues[1], null, m.groupValues[2]) }
        return null
    }

    /** The bot a message names: by its handle ("hermes" is the main profile), else by what it is called. */
    fun sender(name: String, handle: String?, roster: List<RosterBot>): BotRef {
        val wanted = (handle ?: name).lowercase()
        val bot = roster.firstOrNull { wanted in it.forms } ?: roster.firstOrNull { it.label.lowercase() == name.lowercase() }
        val profile = bot?.profile ?: if (handle == "hermes") "default" else handle ?: nameForms(name).firstOrNull() ?: name.lowercase()
        return BotRef(profile, name)
    }

    /**
     * A finished background process's notice: a bot's reply when the command delivered a message to it
     * (`bot_mode_dm.py … -p <bot> chat …`), the reply alone; else the process. [processLine] words the
     * process's title when the row has none ("Background process exited (exit code 0)").
     */
    fun processNotice(text: String, title: String?, roster: List<RosterBot>, processLine: (String) -> String = { "Background process $it" }): Pair<Delivered, String>? {
        val m = PROCESS.matchEntire(text) ?: return null
        val command = m.groupValues[3]
        var output = m.groupValues[4].trim()
        val profile = if ("bot_mode_dm.py" in command) PROFILE_FLAG.find(command)?.groupValues?.get(1) else null
        if (profile != null) {
            parseJsonOrNull(output)?.let { json -> json["reply"].str?.let { output = it } }
            output = cliNoise(output.replace(OUT_OF_BAND, "\n"))
            if ("exit code 0" !in m.groupValues[2]) output = ""
            val bot = roster.firstOrNull { it.profile == profile }
            return Delivered(Delivered.Kind.REPLY, profile = profile, name = bot?.label ?: profile) to output
        }
        val line = title?.ifEmpty { null } ?: processLine(m.groupValues[2])
        return Delivered(Delivered.Kind.PROCESS, title = line) to output
    }

    /** What Hermes's command line prints besides a bot's words, on every run. */
    fun cliNoise(output: String): String =
        output.split("\n").filter { raw ->
            val line = raw.trim()
            !line.startsWith("⚠") && !Regex("""session_id: \S+""").matches(line) &&
                !Regex("""Session \S+ found but has no messages\. Starting fresh\.""").matches(line) &&
                !Regex("""Resum(ed|ing) session \S+.*""").matches(line)
        }.joinToString("\n").trim()

    /** A steer (a message sent into a turn under way), as Hermes keeps it: the words alone, or null. */
    fun steer(text: String): String? = STEER.matchEntire(text)?.groupValues?.get(1)

    /** What one of a bot's schedules came up with: its name (less Hermes Desktop's tag) and the output. */
    fun routine(text: String): Pair<String, String>? {
        val m = ROUTINE.matchEntire(text) ?: return null
        return untagged(m.groupValues[1]) to m.groupValues[2].trim()
    }

    /** A routine's name without Hermes Desktop's `[bot:…]` tag. */
    fun untagged(name: String): String {
        if (!name.startsWith("[bot:")) return name
        val end = name.indexOf(']')
        return if (end < 0) name else name.substring(end + 1).trim()
    }

    /** A user row as it should show: the person's own, or one delivered (its text the message alone, its sender beside it). */
    fun userMessage(text: String, displayKind: String?, displayText: String?, roster: List<RosterBot> = emptyList(), at: Instant): Message? {
        // Sent by a page in a reply over the Hermes API, which keeps no hidden messages.
        if (PageTurn.isMarked(text)) return null
        routine(text)?.let { (title, output) ->
            return Message(role = Message.Role.USER, text = output, createdAt = at, delivered = Delivered(Delivered.Kind.ROUTINE, title = title))
        }
        if (displayKind == "process_complete" || text.startsWith("[IMPORTANT: Background process")) {
            processNotice(text, displayText, roster)?.let { (delivered, body) ->
                return Message(role = Message.Role.USER, text = body, createdAt = at, delivered = delivered)
            }
        }
        steer(text)?.let { words ->
            return if (words.isEmpty()) null else Message(role = Message.Role.USER, text = words, createdAt = at, isSteer = true)
        }
        inbound(text)?.let { inbound ->
            val bot = sender(inbound.name, inbound.handle, roster)
            return Message(role = Message.Role.USER, text = inbound.body, createdAt = at, delivered = Delivered(Delivered.Kind.MESSAGE, profile = bot.profile, name = bot.name))
        }
        if (text.isEmpty()) return null
        return Message(role = Message.Role.USER, text = text, createdAt = at)
    }

    /** Replies are read aloud on a watch: the hint the phone added for a watch's question (iOS), taken back off. */
    private const val WATCH_HINT = "\n\n(The user is on an Apple Watch and will hear your reply read aloud. Answer in a few plain " +
        "sentences, no Markdown, lists, tables or code; if a longer answer is needed, give the " +
        "gist and say the rest is on their phone.)"

    fun withoutWatchHint(text: String): String = if (text.endsWith(WATCH_HINT)) text.dropLast(WATCH_HINT.length) else text
}
