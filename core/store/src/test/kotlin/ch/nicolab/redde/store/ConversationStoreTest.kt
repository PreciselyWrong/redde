package ch.nicolab.redde.store

import ch.nicolab.redde.model.Attachment
import ch.nicolab.redde.model.AttachmentFiles
import ch.nicolab.redde.model.Message
import ch.nicolab.redde.model.ReddeJson
import ch.nicolab.redde.model.Transport
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationStoreTest {
    private val root: File = Files.createTempDirectory("redde-store").toFile()

    private fun record(title: String, updated: Long, vararg texts: String) = ConversationRecord(
        id = UUID.randomUUID(), title = title, createdAt = Instant.ofEpochSecond(updated), updatedAt = Instant.ofEpochSecond(updated),
        transport = Transport.HERMES_SERVE, messages = texts.mapIndexed { i, t -> Message(role = if (i % 2 == 0) Message.Role.USER else Message.Role.ASSISTANT, text = t, createdAt = Instant.ofEpochSecond(updated + i)) },
    )

    @Test
    fun recordsSurviveARestartNewestFirst() = runBlocking {
        val store = ConversationStore(File(root, "conversations"), debounceMs = 10)
        val old = record("Old", 1_700_000_000, "a", "b")
        val new = record("New", 1_800_000_000, "c")
        store.upsert(old)
        store.upsert(new)
        store.flush()
        val again = ConversationStore(File(root, "conversations"))
        assertEquals(listOf("New", "Old"), again.summaries.value.map { it.title })
        assertEquals(1, again.summaries.value.last().turnCount)
        assertEquals(old, again.record(old.id))
        again.delete(old.id)
        again.saveNow()
        assertNull(ConversationStore(File(root, "conversations")).record(old.id))
    }

    /** A record the iOS app wrote reads as it is. */
    @Test
    fun readsTheIosFormat() {
        val dir = File(root, "ios").apply { mkdirs() }
        val id = "0D1E2F30-4A5B-6C7D-8E9F-A0B1C2D3E4F5"
        File(dir, "$id.json").writeText("""
            {"id":"$id","title":"Plans","createdAt":"2026-10-31T09:12:44Z","updatedAt":"2026-10-31T09:20:00Z",
             "transport":"hermesSessions","serverSessionID":"s1","serverID":"11111111-2222-3333-4444-555555555555",
             "messages":[{"id":"AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE","role":"user","text":"Hi","createdAt":"2026-10-31T09:12:44Z"},
               {"id":"BBBBBBBB-BBBB-CCCC-DDDD-EEEEEEEEEEEE","role":"assistant","text":"Hello","createdAt":"2026-10-31T09:12:50Z",
                "tools":[{"id":"CCCCCCCC-BBBB-CCCC-DDDD-EEEEEEEEEEEE","name":"web","status":"completed"}],
                "metrics":{"sentAt":"2026-10-31T09:12:44Z","characters":5},"somethingNew":true}],
             "outbox":[{"message":{"id":"DDDDDDDD-BBBB-CCCC-DDDD-EEEEEEEEEEEE","role":"user","text":"later","createdAt":"2026-10-31T09:21:00Z"},
                        "state":"waitingForConnection","queuedAt":"2026-10-31T09:21:00Z"}],
             "bot":{"profile":"research","name":"Research Buddy"}}
        """.trimIndent())
        val record = ConversationStore(dir).record(UUID.fromString(id))!!
        assertEquals(Transport.HERMES_SESSIONS, record.transport)
        assertEquals("s1", record.serverSessionId)
        assertEquals(listOf("Hi", "Hello"), record.messages.map { it.text })
        assertEquals("web", record.messages[1].tools.single().name)
        assertEquals(OutboxItem.State.WAITING_FOR_CONNECTION, record.outbox!!.single().state)
        assertEquals("research", record.bot?.profile)
        // Written back the same way: upper-case ids, seconds-only dates, the iOS key names.
        val json = ReddeJson.encodeToString(ConversationRecord.serializer(), record)
        assertTrue("\"serverSessionID\":\"s1\"" in json && "\"createdAt\":\"2026-10-31T09:12:44Z\"" in json && "\"id\":\"$id\"" in json, json)
    }

    @Test
    fun attachmentBytesGoToTheirOwnFiles() = runBlocking {
        val files = FolderAttachmentFiles(File(root, "attachments"))
        AttachmentFiles.current = files
        val picture = Attachment.of(Attachment.Kind.IMAGE, "p.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        val store = ConversationStore(File(root, "c2"), debounceMs = 1)
        val r = record("Pic", 1, "look").let { it.copy(messages = listOf(it.messages[0].copy(attachments = listOf(picture)))) }
        store.upsert(r)
        store.flush()
        val stored = ConversationStore(File(root, "c2")).record(r.id)!!.messages[0].attachments[0]
        assertEquals(3, stored.byteCount)
        assertEquals(listOf<Byte>(1, 2, 3), stored.data.toList(), "read back from the attachment store")
        assertTrue("data" !in File(root, "c2/${r.id.toString().uppercase()}.json").readText().substringAfter("attachments"), "no bytes in the record")
        AttachmentFiles.current = null
    }

    @Test
    fun aServerReadsWhateverItLacksAndSecretsAreNamedAsOnIos() {
        val server = ReddeJson.decodeFromString(HermesServer.serializer(), """{"id":"11111111-2222-3333-4444-555555555555","serveURL":"http://hermes.home:9119"}""")
        assertEquals("hermes.home", server.title)
        assertEquals(Transport.HERMES_SERVE, server.transport)
        assertEquals("gateway-api-key@11111111-2222-3333-4444-555555555555", SecretStore.account(SecretStore.Item.GATEWAY_API_KEY, server.id))
        assertEquals("gateway-api-key.work@11111111-2222-3333-4444-555555555555", SecretStore.profileAccount("work", server.id))
        val stale = OutboxItem(Message(role = Message.Role.USER, text = "x"), OutboxItem.State.QUEUED, Instant.now().minusSeconds(7200))
        assertTrue(stale.isStale())
    }
}
