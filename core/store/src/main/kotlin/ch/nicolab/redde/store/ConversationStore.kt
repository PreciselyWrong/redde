package ch.nicolab.redde.store

import ch.nicolab.redde.model.AttachmentFiles
import ch.nicolab.redde.model.ReddeJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * One JSON file per conversation under `conversations/`, plus a small index with what the list
 * needs (port of ConversationStore.swift, same files as the iOS app). Start-up reads the index only;
 * a transcript is decoded when it is opened and written only when it changes, 250 ms after the last
 * change, or at once with [saveNow] (the app going to the background).
 */
class ConversationStore(
    private val directory: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val debounceMs: Long = 250,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val _summaries = MutableStateFlow<List<ConversationSummary>>(emptyList())

    /** Newest first. */
    val summaries: StateFlow<List<ConversationSummary>> = _summaries

    /** Records read or written this session. */
    private val cache = HashMap<UUID, ConversationRecord>()

    /** Records changed since the last write. */
    private val pending = LinkedHashMap<UUID, ConversationRecord>()
    private var saveJob: Job? = null

    init {
        directory.mkdirs()
        load()
    }

    /** The full record, read from disk the first time it is asked for (on the caller's thread). */
    fun record(id: UUID): ConversationRecord? {
        synchronized(lock) { cache[id]?.let { return it } }
        val read = read(recordFile(id)) ?: return null
        synchronized(lock) { return cache.getOrPut(id) { read } }
    }

    /** The record if it has already been read or written this session; never touches disk. */
    fun cachedRecord(id: UUID): ConversationRecord? = synchronized(lock) { cache[id] }

    /** The full record, decoded off the caller's thread. A write that landed meanwhile wins. */
    suspend fun loadRecord(id: UUID): ConversationRecord? = withContext(Dispatchers.IO) { record(id) }

    fun upsert(record: ConversationRecord) {
        synchronized(lock) {
            cache[record.id] = record
            pending[record.id] = record
            val summary = ConversationSummary.of(record)
            val list = _summaries.value.filter { it.id != record.id }.toMutableList()
            // Newest first, by stamp rather than blindly at the front: imported records can carry old dates.
            val at = list.indexOfFirst { it.updatedAt < summary.updatedAt }.let { if (it < 0) list.size else it }
            list.add(at, summary)
            _summaries.value = list
        }
        scheduleSave()
    }

    /** Gone from the list and the store at once, with its attachments. */
    fun delete(id: UUID) {
        val held: ConversationRecord?
        synchronized(lock) {
            held = cache.remove(id) ?: pending[id]
            pending.remove(id)
            _summaries.value = _summaries.value.filter { it.id != id }
        }
        val file = recordFile(id)
        val record = held ?: read(file)
        file.delete()
        record?.messages?.forEach { m -> m.attachments.forEach { AttachmentFiles.current?.delete(it.id) } }
        scheduleSave()
    }

    /** Removes every conversation (the attachment store is the caller's to empty). */
    fun deleteAll() {
        synchronized(lock) {
            saveJob?.cancel(); saveJob = null
            _summaries.value = emptyList()
            cache.clear()
            pending.clear()
        }
        directory.deleteRecursively()
        directory.mkdirs()
    }

    /** Writes everything outstanding now. */
    fun saveNow() {
        val (records, index) = synchronized(lock) {
            saveJob?.cancel(); saveJob = null
            val r = pending.values.toList()
            pending.clear()
            r to _summaries.value
        }
        write(records, index)
    }

    /** Waits for a pending debounced save to finish writing. Tests use it instead of sleeping. */
    suspend fun flush() {
        synchronized(lock) { saveJob }?.join()
    }

    private fun scheduleSave() {
        synchronized(lock) {
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(debounceMs)
                val (records, index) = synchronized(lock) { pending.values.toList() to _summaries.value }
                write(records, index)
                synchronized(lock) { for (r in records) if (pending[r.id] == r) pending.remove(r.id) }
            }
        }
    }

    private fun load() {
        val index = File(directory, "index.json")
        if (!index.exists()) return
        try {
            _summaries.value = ReddeJson.decodeFromString(ListSerializer(ConversationSummary.serializer()), index.readText())
                .sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            log("could not read the conversation index: ${e.message}")
        }
    }

    private fun recordFile(id: UUID) = File(directory, id.toString().uppercase() + ".json")

    private fun read(file: File): ConversationRecord? {
        if (!file.exists()) return null
        return try {
            ReddeJson.decodeFromString(ConversationRecord.serializer(), file.readText())
        } catch (e: Exception) {
            log("could not read conversation ${file.nameWithoutExtension}: ${e.message}")
            null
        }
    }

    private fun write(records: List<ConversationRecord>, index: List<ConversationSummary>) {
        try {
            for (record in records) {
                // Attachment bytes go to the store explicitly (the record keeps metadata only).
                record.messages.forEach { m -> m.attachments.forEach { it.persistToStore() } }
                atomicWrite(recordFile(record.id), ReddeJson.encodeToString(ConversationRecord.serializer(), record))
            }
            atomicWrite(File(directory, "index.json"), ReddeJson.encodeToString(ListSerializer(ConversationSummary.serializer()), index))
        } catch (e: Exception) {
            log("could not save conversations: ${e.message}")
        }
    }

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/** Attachment bytes in a folder, one file per attachment id (port of AttachmentFiles). */
class FolderAttachmentFiles(private val directory: File) : AttachmentFiles {
    init {
        directory.mkdirs()
    }

    private fun file(id: UUID) = File(directory, id.toString().uppercase())

    override fun read(id: UUID): ByteArray? = file(id).takeIf { it.exists() }?.readBytes()

    /** Skips a file already there: an attachment's bytes never change. */
    override fun write(id: UUID, data: ByteArray) {
        val f = file(id)
        if (!f.exists()) f.writeBytes(data)
    }

    override fun delete(id: UUID) {
        file(id).delete()
    }

    fun deleteAll() {
        directory.deleteRecursively()
        directory.mkdirs()
    }
}
