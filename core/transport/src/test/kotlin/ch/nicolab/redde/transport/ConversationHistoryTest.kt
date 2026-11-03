package ch.nicolab.redde.transport

import ch.nicolab.redde.model.Delivered
import ch.nicolab.redde.model.Message
import ch.nicolab.redde.model.ReddeJson
import ch.nicolab.redde.model.arr
import ch.nicolab.redde.model.get
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from EchoTests/StoredMessageTests.swift, plus the Dashboard rows and bot messages. */
class ConversationHistoryTest {
    private fun stored(json: String) = ReddeJson.parseToJsonElement(json)["data"].arr!!.map(StoredMessage::from)

    @Test
    fun decodesTheGatewaysRows() {
        val rows = stored("""
        {"object":"list","session_id":"s1","data":[
          {"id":101,"session_id":"s1","role":"user","content":"What's on tomorrow?","tool_call_id":null,"tool_calls":null,
           "tool_name":null,"timestamp":1790429287.5,"token_count":7,"finish_reason":null,"reasoning":null,"reasoning_content":null},
          {"id":102,"session_id":"s1","role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function",
           "function":{"name":"calendar","arguments":"{}"}}],"timestamp":1790429288,"token_count":12,"finish_reason":"tool_calls"},
          {"id":103,"session_id":"s1","role":"tool","content":"{\"events\":2}","tool_call_id":"c1","tool_name":"calendar","timestamp":1790429289},
          {"id":104,"session_id":"s1","role":"assistant","content":[{"type":"text","text":"Two things."}],"timestamp":"1790429290",
           "reasoning":"Check the calendar.","display_metadata":{"kind":"x"},"something_new":[1,2,3]}
        ],"pagination":{"limit":500,"offset":0,"order":"oldest","returned":4}}
        """)
        assertEquals(4, rows.size)
        assertEquals("101", rows[0].id)
        assertEquals(1790429290.0, rows[3].timestamp)
        val messages = ConversationHistory.mapStored(rows)
        assertEquals(listOf(Message.Role.USER, Message.Role.ASSISTANT), messages.map { it.role })
        assertEquals("What's on tomorrow?", messages[0].text)
        assertEquals("Two things.", messages[1].text)
        assertEquals(listOf("calendar"), messages[1].tools.map { it.name })
        assertEquals("""{"events":2}""", messages[1].tools[0].output, "the result joins its call, as it came")
    }

    @Test
    fun oddFieldsAreSkipped() {
        val rows = stored("""{"data":[{"id":"x","role":"user","content":{"weird":true},"timestamp":true,"tool_calls":"[]"},{"role":"assistant","content":"ok"}]}""")
        assertEquals(2, rows.size)
        assertNull(rows[0].content)
        assertEquals("ok", ConversationHistory.mapStored(rows).last().text)
    }

    @Test
    fun theNotesRedddeAddedComeOffAndHiddenRowsStayHidden() {
        val rows = stored("""{"data":[{"role":"user","content":"Hoi\n\n(Reply in Dutch (Nederlands).)"},{"role":"user","content":"x","display_kind":"hidden"},{"role":"assistant","content":"Hallo"}]}""")
        assertEquals(listOf("Hoi", "Hallo"), ConversationHistory.mapStored(rows).map { it.text })
    }

    @Test
    fun dashboardRowsFoldToolsIntoTheirReply() {
        val rows = ReddeJson.parseToJsonElement("""[
          {"role":"user","text":"Search it","timestamp":1700000000},
          {"role":"tool","name":"web_search","context":"redde","args":{"q":"redde"}},
          {"role":"assistant","text":"Found it.","reasoning":"look"},
          {"role":"tool","name":"tool_call","args":{"calls":[{"name":"memory","arguments":{"add":"x"}}]}}
        ]""").arr!!
        val messages = ConversationHistory.fromServeRows(rows)
        assertEquals(2, messages.size)
        assertEquals(Instant.ofEpochSecond(1_700_000_000), messages[0].createdAt)
        assertEquals(listOf("web_search", "memory"), messages[1].tools.map { it.name }, "a call made before the reply belongs to it; tool_call names its tool")
        assertEquals("redde", messages[1].tools[0].preview)
    }

    @Test
    fun botsAndProcessesAndSteersShowAsWhatTheyAre() {
        val roster = listOf(RosterBot(profile = "research", handle = "research-buddy", label = "Research Buddy"))
        val now = Instant.now()
        val inbound = BotMessages.userMessage("Message from 🤖 Research Buddy (@research-buddy): Done!", null, null, roster, now)!!
        assertEquals("Done!", inbound.text)
        assertEquals(Delivered(Delivered.Kind.MESSAGE, profile = "research", name = "Research Buddy"), inbound.delivered)
        val old = BotMessages.userMessage("[Message from agent 'scout'] hi", null, null, emptyList(), now)!!
        assertEquals("scout", old.delivered?.profile)
        val steer = BotMessages.userMessage("[OUT-OF-BAND USER MESSAGE from the user] also the cat [/OUT-OF-BAND USER MESSAGE]", null, null, emptyList(), now)!!
        assertTrue(steer.isSteer)
        assertEquals("also the cat", steer.text)
        val routine = BotMessages.userMessage("[Cronjob \"[bot:research] Morning\" output]\nAll quiet.", null, null, emptyList(), now)!!
        assertEquals(Delivered(Delivered.Kind.ROUTINE, title = "Morning"), routine.delivered)
        assertEquals("All quiet.", routine.text)
        val reply = BotMessages.userMessage(
            "[IMPORTANT: Background process proc_1 exited (exit code 0).\nCommand: python bot_mode_dm.py --run-delivery -p research chat hi\nOutput:\n⚠ warning\n{\"reply\":\"Here it is\"}]",
            "process_complete", null, roster, now,
        )!!
        assertEquals(Delivered.Kind.REPLY, reply.delivered?.kind)
        assertEquals("Research Buddy", reply.delivered?.name)
        assertNull(BotMessages.userMessage("[From the page in the chat] tapped", null, null, emptyList(), now), "a page's message doesn't show")
        assertEquals(listOf("research-buddy", "researchbuddy"), BotMessages.nameForms("Research Buddy"))
    }
}
