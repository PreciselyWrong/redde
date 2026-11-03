package ch.nicolab.redde.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Base64
import java.util.UUID

/**
 * Where attachment bytes live once a message is stored: the transcript JSON keeps metadata only
 * (as on iOS), and the app's file store holds the bytes under the attachment's id.
 */
interface AttachmentFiles {
    fun read(id: UUID): ByteArray?
    fun write(id: UUID, data: ByteArray)
    fun delete(id: UUID)

    companion object {
        /** Set by the app at start-up; nothing is read back without it. */
        @Volatile
        var current: AttachmentFiles? = null
    }
}

/**
 * Something the person attached to a message. Images are downscaled before they get here (the
 * platform layer does it) to keep the transcript file and the request small; other files are
 * carried as they are up to [MAX_FILE_BYTES].
 */
@Serializable
data class Attachment(
    @Serializable(with = UuidSerializer::class) val id: UUID = UUID.randomUUID(),
    val kind: Kind,
    val filename: String,
    val mimeType: String,
    val byteCount: Int = 0,
    /**
     * A page the agent made for the chat (`::preview{file=…}`), shown live in its reply rather than as
     * a file: where it is on the server, as the reply named it, to read it again from.
     */
    val page: String? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("image") IMAGE,
        @SerialName("pdf") PDF,
        @SerialName("text") TEXT,
        @SerialName("other") OTHER,
    }

    /** Bytes as created; a value decoded from the transcript has none and reads the file store. */
    @Transient
    private var inline: ByteArray? = null

    /** The bytes, from memory or from the attachment store on disk. */
    val data: ByteArray
        get() = inline ?: AttachmentFiles.current?.read(id) ?: ByteArray(0)

    /** Writes in-memory bytes to the attachment store so a metadata-only record can be read back. */
    fun persistToStore() {
        inline?.let { AttachmentFiles.current?.write(id, it) }
    }

    val dataUrl: String get() = "data:$mimeType;base64," + Base64.getEncoder().encodeToString(data)

    /** A video is a file like any other to the server ([Kind.OTHER]); the app only shows it as one. */
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    /** Sound, likewise: played where it stands in the conversation. */
    val isAudio: Boolean
        get() = mimeType.startsWith("audio/") ||
            (kind == Kind.OTHER && filename.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS)

    /** Text-like files can be inlined into the prompt on transports that don't take files. */
    val inlineText: String?
        get() = if (kind == Kind.TEXT) data.decodeToString() else null

    /** A text file as it is added to a prompt on transports that don't take files. */
    val promptAddendum: String?
        get() = inlineText?.let { "--- $filename ---\n$it" }

    override fun equals(other: Any?): Boolean =
        other is Attachment && id == other.id && kind == other.kind && filename == other.filename &&
            mimeType == other.mimeType && byteCount == other.byteCount && page == other.page

    override fun hashCode(): Int = id.hashCode()

    companion object {
        /** Enough to read text off a photographed screen. */
        const val MAX_IMAGE_EDGE = 2560
        const val MAX_FILE_BYTES = 8 * 1024 * 1024

        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "wav", "aac", "ogg", "oga", "opus", "flac", "aiff", "aif", "caf")

        /** An attachment made on the device, with its bytes in memory. */
        fun of(kind: Kind, filename: String, mimeType: String, data: ByteArray, id: UUID = UUID.randomUUID()): Attachment =
            Attachment(id = id, kind = kind, filename = filename, mimeType = mimeType, byteCount = data.size).also { it.inline = data }

        /**
         * A picture's name for the JPEG it became: a photo shared as IMG_0412.HEIC is sent as
         * IMG_0412.jpg. Hermes takes an attached picture's kind from its name and turns a .heic down.
         */
        fun jpegName(filename: String): String {
            val dot = filename.lastIndexOf('.')
            val ext = if (dot > 0) filename.substring(dot + 1).lowercase() else ""
            if (ext == "jpg" || ext == "jpeg") return filename
            val stem = if (ext.isEmpty()) filename else filename.substring(0, dot)
            return (stem.ifEmpty { "photo" }) + ".jpg"
        }
    }
}

/** Why an attachment can't go. The app words these (they were localized strings on iOS). */
sealed class AttachmentError(message: String) : Exception(message) {
    class TooLarge(val name: String) : AttachmentError("$name is over the attachment limit")

    /** [kind] is "PDF", "Video" or "File"; [transport] the connection's name. */
    class Unsupported(val kind: String, val transport: String) : AttachmentError("$kind attachments aren't supported on $transport")

    /** A video that is over the limit even at the smallest size it can be re-encoded to. */
    class VideoTooLong(val name: String) : AttachmentError("$name is too long to attach")
}
