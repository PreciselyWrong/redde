package ch.nicolab.redde.crypto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the plugin tells the phone, as it is sealed: short keys, since every byte rides in a
 * notification capped at 4 KB. Fields left out of the JSON are null; keys this build doesn't
 * know are ignored.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PushNote(
    @EncodeDefault val v: Int = 1,
    /** The note's kind, as sent; [kind] reads it. */
    val k: String,
    /** The Hermes session the note is about. */
    val s: String? = null,
    /** The conversation's title. */
    val t: String? = null,
    val b: String? = null,
    val d: String? = null,
    /** The name of the machine Hermes runs on. */
    val n: String? = null,
    val at: Double? = null,
    /**
     * On an approval that can be answered from the notification: [digest] of the command, so
     * the answer is given to that command and no other. Absent when it can't be (a turn the
     * Dashboard has no hand in: over the Hermes API, or at a terminal). On a question that can
     * be answered from it: the same of the question's text.
     */
    val h: String? = null,
    /** A question's choices, to make buttons of. Absent for one answered in words. */
    val c: List<String>? = null,
    /**
     * On a note for the widget: from when it shows and until when it holds, when the agent
     * said, as the agent wrote them, so a time without a zone is the device's.
     */
    val f: String? = null,
    val u: String? = null,
    /**
     * On a group chat's note: the room, what it waits on ("approval", "retry"), why a command
     * needs approval, and the task of a turn to try again.
     */
    val g: String? = null,
    val w: String? = null,
    val r: String? = null,
    val x: String? = null,
    /** On a bot's schedule's, and a call's: the bot's profile ("default", the main agent). */
    val p: String? = null,
    /** On a call: its own id. On ideas: the offer's. */
    val i: String? = null,
    /** On ideas: each one's heading (`t`), why now (`w`) and the request it would carry out (`r`). */
    val y: List<Idea>? = null,
) {
    /** One thing an agent could do for the person. */
    @Serializable
    data class Idea(val t: String? = null, val w: String? = null, val r: String? = null)

    /** What a note is about. */
    enum class Kind(val raw: String) {
        /** The agent finished a reply (`b` is how it starts). */
        REPLY("reply"),
        /** A command waits for a yes or no (`b` is the command, `d` why it was stopped). */
        APPROVAL("approval"),
        /**
         * The agent asked the person something (`b` is the question, `d` its choices on a line,
         * `c` the choices one by one when it can be answered from the notification).
         */
        QUESTION("question"),
        /** A command waits for the sudo password (`b` is the command, when Hermes says which). */
        SUDO("sudo"),
        /** A skill waits for a secret (`b` is what it asks for, `d` the variable it is for). */
        SECRET("secret"),
        /** The turn ended without a reply (`b` is why). */
        FAILED("failed"),
        /**
         * A task the agent had handed off came back. `b` is what the agent says about it, in a
         * reply nobody asked for just now, or what the task itself reported when no such reply
         * follows (`d` then says how it ended, when not well).
         */
        TASK("task"),
        /** The first note after pairing: the plugin has the key too. */
        PAIRED("paired"),
        /** `hermes redde-push test`. */
        TEST("test"),
        /**
         * What the agent wrote for its widget in a turn the device wasn't following (`t` and `b`
         * are the title and the text, `f` and `u` from and until when; neither `t` nor `b`
         * clears it). Sent only to a device that has the widget's tool turned on.
         */
        WIDGET("widget"),
        /** A bot's message in a group chat the device follows (`g` the room, `t` its name, `d` the bot, `b` the message). */
        GROUP("group"),
        /**
         * A bot waits on the person in a group chat: `w` "approval" (`b` the command, `r` why it
         * needs approval, `h` its digest) or "retry" (`x` the turn's task).
         */
        GROUP_WAIT("group_wait"),
        /**
         * One of a bot's schedules ran (`p` the bot's profile, `d` its name, `t` the schedule's,
         * `b` what it came up with; `w` "failed" when it didn't finish). A `[redde-notify]` job's,
         * `x` "brief" and `i` the job, is a briefing.
         */
        ROUTINE("routine"),
        /**
         * An agent is calling (`p` its profile, `d` its name, `b` what it says first, `s` the
         * conversation it called from, `i` the call's id). A call not answered is shown with
         * this note's words.
         */
        CALL("call"),
        /** Things an agent could do for the person (`p` its profile, `d` its name, `i` the offer's id, `y` the ideas). */
        IDEAS("ideas"),
        /**
         * A watch saw something change (`p` the agent's profile, `d` its name, `t` what is
         * watched, `b` what changed, or why it couldn't look with `w` "failed", `i` the watch).
         */
        WATCH("watch"),
        /**
         * A bot asks for a key (`request_secret`): `i` the request, `t` the variable, `b` why,
         * `g` the profile whose .env it goes in, `p` and `d` the asking agent, `r` "1" when it
         * replaces one. Never a value: the person pastes it into Redde.
         */
        KEY("key"),
        /** Time to check the traffic for a Leave-By Alert (`i` the alert's opaque id, `t` the event's title). */
        LEAVE("leave");

        companion object {
            /** The kind sent as [raw]; null for one this build doesn't know. */
            fun of(raw: String): Kind? = entries.firstOrNull { it.raw == raw }
        }
    }

    /** Null for a kind a newer plugin sends and this build doesn't know. */
    val kind: Kind? get() = Kind.of(k)

    /** The note as JSON, with only the fields it has. */
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** The note in [plain] (UTF-8 JSON), or null when it isn't one. */
        fun decode(plain: ByteArray): PushNote? = try {
            json.decodeFromString(serializer(), plain.decodeToString(throwOnInvalidSequence = true))
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }

        /**
         * What stands for a command in a note: sixteen hex digits of its SHA-256 (of its UTF-8).
         * The plugin's `core.digest` is the same.
         */
        fun digest(command: String): String = PushSeal.hex(PushSeal.sha256(command.encodeToByteArray()).copyOf(8))
    }
}
