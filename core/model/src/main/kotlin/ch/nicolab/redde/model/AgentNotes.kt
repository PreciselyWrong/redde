package ch.nicolab.redde.model

import java.text.Normalizer
import java.util.Locale

// The words Redde adds for the agent with a message. Over the Hermes API and an OpenAI-compatible
// server they go among the turn's instructions (`instruction`); the Hermes Dashboard takes no
// instructions with a message, so there they ride on the message itself (`note`), and Redde hides
// them when the message is read back (`strip`). Words to the model are English, as on iOS, and must
// stay byte for byte the same: messages written by the iOS app are read back here.

/** Reply language: ask the agent to answer in one language, whatever the person writes or speaks in. */
object ReplyLanguage {
    /** The languages offered, as ISO codes. Most models write these well. */
    val CODES = listOf(
        "ar", "bg", "ca", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi", "fr", "he", "hi", "hr", "hu",
        "id", "it", "ja", "ko", "lt", "lv", "ms", "nb", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sr", "sv",
        "sw", "ta", "th", "tl", "tr", "uk", "ur", "vi", "zh",
    )

    /** For the model: the name in English, and in the language itself ("Dutch (Nederlands)"). */
    fun name(code: String): String {
        val locale = Locale.forLanguageTag(code)
        val english = locale.getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }
        val native = locale.getDisplayLanguage(locale)
        return if (native.isEmpty() || native.lowercase() == english.lowercase()) english else "$english ($native)"
    }

    fun instruction(code: String) =
        "Always reply in ${name(code)}, whatever language the user writes or speaks in, unless they ask for another language."

    fun note(code: String) = "\n\n(Reply in ${name(code)}.)"

    /**
     * A message loaded back from the server, without the notes Redde put on it: this one and every
     * other, in the order they were added; the asides too unless [keepingAsides].
     */
    fun stripNote(text: String, keepingAsides: Boolean = false): String {
        var t = MeasureUnits.strip(CalendarChoice.strip(CurrentLocation.strip(CarBrevity.strip(IncognitoNote.strip(LiveVoiceNote.strip(CallNote.strip(text)))))))
        if (t.endsWith(".)")) {
            val at = t.lastIndexOf("\n\n(Reply in ")
            if (at >= 0 && '\n' !in t.substring(at + "\n\n(Reply in ".length)) t = t.substring(0, at)
        }
        val plain = CardNote.strip(RoutineNote.strip(MentionNote.strip(AttachedFileNote.strip(t))))
        return if (keepingAsides) plain else AsideNote.strip(plain)
    }
}

/** US or metric units. Automatic follows the device's region; the app decides what to send. */
enum class MeasureUnits(val raw: String) {
    AUTOMATIC(""), US("us"), METRIC("metric");

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: AUTOMATIC

        /** US units, as chosen, or by the region (the US, Liberia and Myanmar measure in US units). */
        fun usesUs(chosen: MeasureUnits, locale: Locale = Locale.getDefault()): Boolean = when (chosen) {
            US -> true
            METRIC -> false
            AUTOMATIC -> locale.country in setOf("US", "LR", "MM")
        }

        /** What the agent is told: US, or metric when chosen; nothing for metric by the region. */
        fun forAgent(chosen: MeasureUnits, locale: Locale = Locale.getDefault()): MeasureUnits? =
            if (usesUs(chosen, locale)) US else if (chosen == METRIC) METRIC else null

        fun instruction(units: MeasureUnits) =
            if (units == US) "Give measurements in US customary units (miles, feet, °F, mph, pounds, ounces), unless the user asks for others."
            else "Give measurements in metric units (kilometres, metres, °C, km/h, kilograms, grams), unless the user asks for others."

        private const val US_NOTE = "\n\n(Use US units.)"
        private const val METRIC_NOTE = "\n\n(Use metric units.)"

        fun note(units: MeasureUnits) = if (units == US) US_NOTE else METRIC_NOTE

        fun strip(text: String): String {
            for (note in listOf(US_NOTE, METRIC_NOTE)) if (text.endsWith(note)) return text.dropLast(note.length)
            return text
        }
    }
}

/** The calendars the person chose, for the agent to look at only, on a question about the calendar. */
object CalendarChoice {
    fun instruction(names: List<String>) =
        "Look only at these calendars: ${names.joinToString(", ")} (pass them as calendar_ids to the calendar tool)."

    private const val NOTE_OPENING = "\n\n(Look only at these calendars: "
    private const val NOTE_CLOSING = "; pass them as calendar_ids.)"

    fun note(names: List<String>) = NOTE_OPENING + names.joinToString(", ") + NOTE_CLOSING

    fun strip(text: String): String = stripTail(text, NOTE_OPENING, NOTE_CLOSING)
}

/** Roughly where the person is, for a question about places, directions, maps or the weather. */
object CurrentLocation {
    /** A place as the agent hears it: words, and coordinates rounded to three places (about 100 m). */
    @kotlinx.serialization.Serializable
    data class Here(val words: String?, val latitude: Double, val longitude: Double, val at: Long) {
        companion object {
            fun of(words: String?, latitude: Double, longitude: Double, at: Long) =
                Here(words?.let(::tidy)?.ifEmpty { null }, rounded(latitude), rounded(longitude), at)
        }
    }

    /** How old a kept place may be and still be sent, in milliseconds. */
    const val FRESH_FOR_MS = 30 * 60 * 1000L

    fun rounded(degrees: Double) = Math.round(degrees * 1000) / 1000.0

    /** One line, not too long: what goes in a note must not break it. */
    fun tidy(text: String) = text.lines().joinToString(" ").trim().take(120)

    fun fresh(here: Here?, now: Long = System.currentTimeMillis()): Here? =
        here?.takeIf { now - it.at < FRESH_FOR_MS && now - it.at > -60_000 }

    /** A question about places, directions, maps or the weather, in English, as the plugin's own tests are. */
    private val ASKS = Regex(
        "\\bnear (?:me|here|us|by)\\b|\\bnearby\\b|\\bnearest\\b|\\bclosest\\b|\\baround here\\b" +
        "|\\bclose (?:to|by) (?:me|here|us)\\b|\\bin (?:the|this|my|our) (?:area|neighbou?rhood)\\b|\\bwhere am i\\b|\\bwhere are we\\b" +
        "|\\bdirections?\\b|\\bhow (?:far|long (?:is it|does it take|will it take|would it take) to (?:get|drive|walk))\\b" +
        "|\\bhow do (?:i|we) get (?:to|there|home)\\b|\\b(?:drive|walk|commute|route) (?:to|there|home)\\b|\\bmaps?\\b" +
        "|\\b(?:restaurants?|caf[eé]s|coffee shops?|pharmac(?:y|ies)|drugstores?|gas stations?|petrol stations?" +
        "|charging stations?|ev chargers?|supermarkets?|grocery stores?|urgent care|takeout|take-away|places to eat" +
        "|somewhere to eat|things to do)\\b" +
        "|\\b(?:where|find|any|good|best|recommend|open now|go for)\\b[^.?!]*\\b(?:coffee|caf[eé]|bars?|pubs?|brunch|lunch|dinner" +
        "|food|pizza|sushi|tacos|gas|groceries|stores?|shops?|atms?|banks?|parks?|gyms?|hotels?|museums?|cinemas?|movies" +
        "|baker(?:y|ies)|parking|hospital|vet|dentist|doctor)\\b" +
        "|\\bweather\\b|\\bforecast\\b|\\btemperatures?\\b|\\bdegrees\\b|\\bumbrella\\b|\\bsun(?:rise|set)\\b" +
        "|\\b(?:rain(?:ing|y)?|snow(?:ing)?|sunny|humid(?:ity)?|windy)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun asksPlace(text: String) = ASKS.containsMatchIn(text)

    private const val OPENING = "The user is near "
    private const val CLOSING = " Use it for places, directions and weather unless they name somewhere else."

    fun instruction(here: Here): String {
        val at = String.format(Locale.ROOT, "%.3f, %.3f", here.latitude, here.longitude)
        val place = here.words?.let { "$it ($at)" } ?: at
        return "$OPENING$place.$CLOSING"
    }

    fun note(here: Here) = "\n\n(" + instruction(here) + ")"

    fun strip(text: String): String = stripTail(text, "\n\n($OPENING", "$CLOSING)")
}

/** "Short answers in the car": a question asked by voice at the wheel asks for a short spoken answer. */
object CarBrevity {
    const val INSTRUCTION = "The user is driving and hears this reply read aloud through the car; they can't look at a " +
        "screen. Answer in two or three short spoken sentences, without lists, tables, code or links. " +
        "If there is more worth hearing, end by asking whether they want it, and when they say \"more\" " +
        "or \"go on\", carry on from where you stopped. If they ask for something to be read out in " +
        "full, read it."

    const val NOTE = "\n\n(I'm driving and will hear this read aloud: answer in two or three short spoken sentences, and ask whether I want more.)"

    fun strip(text: String) = if (text.endsWith(NOTE)) text.dropLast(NOTE.length) else text
}

/** What an incognito chat tells the agent. */
object IncognitoNote {
    const val INSTRUCTION = "This is an incognito chat: it is deleted when the person leaves it. Don't save anything from it to memory, notes, files or skills, and don't bring it up in other conversations."
    const val NOTE = "\n\n[Incognito chat: deleted when the person leaves it. Save nothing from it to memory, notes, files or skills.]"

    fun strip(text: String) = if (text.endsWith(NOTE)) text.dropLast(NOTE.length) else text
}

/** The note after a message to a bot that @mentions another bot, saying which bot each tag means. */
object MentionNote {
    const val OPENING = "\n\n[@mentions resolved from the Bot Mode roster — "

    fun strip(text: String): String = stripBracketTail(text, OPENING)
}

/**
 * A file sent where only text can go: it goes to the server through the Dashboard's login, and the
 * message says where it is, for the agent to read or look at with its own tools.
 */
object AttachedFileNote {
    const val OPENING = "\n\n[The user attached a file, saved on your machine at "
    const val PICTURE_OPENING = "\n\n[The user attached a picture, saved on your machine at "

    fun note(path: String) = "$OPENING$path — read it with your tools.]"
    fun notePicture(path: String) = "$PICTURE_OPENING$path — look at it with your tools.]"

    fun strip(text: String) = split(text).first
    fun paths(text: String) = split(text).second

    /** What was typed, and the paths its notes name, in the order sent. */
    fun split(text: String): Pair<String, List<String>> {
        var t = text
        val paths = ArrayDeque<String>()
        val openings = listOf(OPENING, PICTURE_OPENING)
        while (t.endsWith("]")) {
            // The last note: after a blank line, or at the very start (Hermes trims the blank line).
            val candidates = openings.mapNotNull { o -> t.lastIndexOf(o).takeIf { it >= 0 }?.let { it to it + o.length } } +
                openings.mapNotNull { o -> val bare = o.drop(2); if (t.startsWith(bare)) 0 to bare.length else null }
            val (start, bodyStart) = candidates.maxByOrNull { it.first } ?: break
            val body = t.substring(bodyStart)
            if ('\n' in body) break
            val end = body.lastIndexOf(" — ")
            if (end < 0) break
            paths.addFirst(body.substring(0, end))
            t = t.substring(0, start)
        }
        return t to paths.toList()
    }
}

/** A turn GPT-Live handed to the agent: what it is and how to answer it. */
object LiveVoiceNote {
    const val CONTEXT_LIMIT = 6_000

    const val LOOK_AGAIN = "Whether anything is on or off, open or shut, where it is or what it reads right now: look it up " +
        "with your tools before you answer, every time, even if you checked or changed it a moment ago. Never answer " +
        "that from earlier in this conversation: things change outside it."

    const val INSTRUCTION = "This message comes from a live spoken conversation: a voice model is talking with the user " +
        "and handed this request to you. The text is a voice transcript; it may contain mishearings, " +
        "hesitations and later corrections, so go by the latest intent. Your reply will be spoken " +
        "aloud in that voice, often word for word as you write it, so write the words to say to the " +
        "user, addressed to them: answer in plain conversational sentences, keep it short (a few sentences unless the user asked for detail, or for a story or " +
        "the like, which you write in full, with what you know about them), with no markdown, no lists, " +
        "no code blocks and no links read out. Do the work with your tools as usual; only the final " +
        "facts need to be spoken. Don't say an action succeeded before it actually did." + " " + LOOK_AGAIN

    const val CAVEAT = "(The voice assistant's lines are its own words: it has no tools and can see nothing, " +
        "so what it says about the state of anything is not a fact. Check with your tools, and do what the user asks.)"

    fun spoken(context: String, limit: Int = CONTEXT_LIMIT): String =
        if ("Voice assistant:" !in context) context.takeLast(limit)
        else CAVEAT + "\n" + context.takeLast(limit - CAVEAT.length - 1)

    /** Hermes's `voice_context` (0.21.3 and later): [LOOK_AGAIN] first, then the exchange. */
    fun voiceContext(context: String): String {
        val rule = "($LOOK_AGAIN)"
        return if (context.isEmpty()) rule else rule + "\n" + spoken(context, CONTEXT_LIMIT - rule.length - 1)
    }

    fun instruction(context: String): String =
        if (context.isEmpty()) INSTRUCTION else INSTRUCTION + "\n\nThe recent spoken conversation, newest last:\n" + spoken(context)

    const val OPENING = "\n\n[Spoken live. "

    fun note(context: String) = OPENING + instruction(context) + "]"

    fun strip(text: String): String {
        val at = text.indexOf(OPENING)
        return if (at >= 0 && text.endsWith("]")) text.substring(0, at) else text
    }
}

/** The person answered a call the agent made: what they say first goes with a note saying so. */
object CallNote {
    const val LINE_LIMIT = 500

    fun instruction(line: String) =
        "The user just answered your phone call (call_user) and is talking with you by voice. You opened with: \"" +
            line.take(LINE_LIMIT) + "\" This message is their answer: carry on from there, briefly, as on a call."

    const val OPENING = "\n\n[Answering your call. "

    fun note(line: String) = OPENING + "You opened with: \"" + line.take(LINE_LIMIT).replace("\n", " ") + "\"]"

    fun strip(text: String): String = if (!text.endsWith("\"]")) text else stripBracketTail(text, OPENING)
}

/** The asides not yet told to the main agent, as a note after the person's next message to it. */
object AsideNote {
    const val OPENING = "\n\n[Redde: apart from you, the user asked other agents here, and their answers were shown to them: "
    const val MOST = 4
    const val ASKED_LENGTH = 500
    const val ANSWER_LENGTH = 1500

    @kotlinx.serialization.Serializable
    data class Aside(val agent: String, val name: String, val asked: String, val answer: String)

    private val listSerializer = kotlinx.serialization.builtins.ListSerializer(Aside.serializer())

    fun note(asides: List<Aside>): String? {
        val kept = asides.takeLast(MOST).map { it.copy(asked = it.asked.take(ASKED_LENGTH), answer = it.answer.take(ANSWER_LENGTH)) }
        if (kept.isEmpty()) return null
        return OPENING + ReddeJson.encodeToString(listSerializer, kept) + "]"
    }

    fun split(text: String): Pair<String, List<Aside>> {
        if (!text.endsWith("]")) return text to emptyList()
        val at = text.lastIndexOf(OPENING)
        if (at < 0) return text to emptyList()
        val json = text.substring(at + OPENING.length, text.length - 1)
        val asides = runCatching { ReddeJson.decodeFromString(listSerializer, json) }.getOrNull() ?: return text to emptyList()
        return text.substring(0, at) to asides
    }

    fun strip(text: String) = split(text).first
}

/** "Make This a Routine": the note asking the agent to keep what it did as a Hermes skill. */
object RoutineNote {
    const val OPENING = "\n\n[Redde: the user wants this to become a routine named "

    /** A skill's name for a routine's ("Morning Report" → "morning-report"). */
    fun slug(name: String): String {
        val folded = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        val slug = folded.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return slug.ifEmpty { "routine" }.take(64).trim('-')
    }

    fun note(name: String, skill: String, whenText: String?) =
        OPENING + "\"$name\". Save what you did in this conversation as a skill named \"$skill\" with your skill tool " +
            "(skill_manage; find it with tool_search if it isn't among your tools): the steps to do it again on your own, " +
            "which tools and sources you used, and what to give back at the end; leave out the details that change each time. " +
            "Don't do the task again now. Then say in one line that it is saved" +
            (whenText?.let { " and that it will run $it, its answer coming to them as a notification" } ?: "") + ".]"

    fun strip(text: String): String {
        if (!text.endsWith(".]")) return text
        val at = text.lastIndexOf(OPENING)
        return if (at >= 0) text.substring(0, at) else text
    }

    fun jobPrompt(name: String, skill: String) =
        "Routine \"$name\": use your skill \"$skill\" (open it with skill_view first) and do what it says, for now. " +
            "Your answer is what the user reads, as a notification: keep it short. If the skill isn't there, say so in one line."
}

/** A tap on a card the agent asked with: the note on the person's message saying what they picked. */
object CardNote {
    const val OPENING = "\n\n[Redde card "

    fun note(key: String, said: String) = "$OPENING$key: $said]"

    /** A message loaded back: its own words, and the answered card's key and what was said. */
    fun split(text: String): Triple<String, String?, String?> {
        if (!text.endsWith("]")) return Triple(text, null, null)
        val at = text.lastIndexOf(OPENING)
        if (at < 0) return Triple(text, null, null)
        val body = text.substring(at + OPENING.length, text.length - 1)
        val colon = body.indexOf(": ")
        if (colon < 0) return Triple(text, null, null)
        return Triple(text.substring(0, at), body.substring(0, colon), body.substring(colon + 2))
    }

    fun strip(text: String) = split(text).first

    /** The card a message answered, wherever the note sits among the message's others. */
    fun find(text: String): Pair<String, String>? {
        val at = text.indexOf(OPENING)
        if (at < 0) return null
        val rest = text.substring(at + OPENING.length)
        val end = rest.indexOf("]\n\n[").takeIf { it >= 0 } ?: rest.lastIndexOf(']').takeIf { it >= 0 } ?: rest.length
        val body = rest.substring(0, end)
        val colon = body.indexOf(": ")
        if (colon < 0) return null
        return body.substring(0, colon) to body.substring(colon + 2)
    }
}

/** Text without a final note that opens with [opening] and ends with [closing], on one line. */
private fun stripTail(text: String, opening: String, closing: String): String {
    if (!text.endsWith(closing)) return text
    val at = text.lastIndexOf(opening)
    if (at < 0 || '\n' in text.substring(at + opening.length)) return text
    return text.substring(0, at)
}

/** Text without a final `[…]` note that opens with [opening], on one line. */
private fun stripBracketTail(text: String, opening: String): String = stripTail(text, opening, "]")
