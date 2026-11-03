package ch.nicolab.redde.transport

import ch.nicolab.redde.model.Message
import ch.nicolab.redde.model.ModelChoice

/**
 * One seam for "which server owns the sessions": the Dashboard's RPCs or the Hermes API ledger's
 * REST (port of SessionBackend.swift's operations). Choosing the backend for the active connection,
 * and the All Profiles list, need the app's settings and move with them (phase 2).
 */
sealed interface SessionBackend {
    data class Serve(val client: HermesServeClient) : SessionBackend
    data class Ledger(val api: HermesSessionsApi) : SessionBackend

    suspend fun listSessions(): List<SessionSummary> = when (this) {
        is Serve -> client.listSessions()
        is Ledger -> api.listSessions()
    }

    /** A session's transcript, mapped into the app's messages. */
    suspend fun messages(id: String, roster: List<RosterBot> = emptyList()): List<Message> = when (this) {
        is Serve -> ConversationHistory.fromServeRows(client.history(id).first, roster)
        is Ledger -> ConversationHistory.mapStored(api.messages(id), roster)
    }

    suspend fun rename(id: String, title: String) = when (this) {
        is Serve -> client.updateSession(id, title = title)
        is Ledger -> api.updateSession(id, title = title)
    }

    suspend fun setPinned(id: String, pinned: Boolean) = when (this) {
        is Serve -> client.updateSession(id, pinned = pinned)
        is Ledger -> api.updateSession(id, pinned = pinned)
    }

    /** Marks a conversation read up to now (false), or unread. */
    suspend fun setUnread(id: String, unread: Boolean) = when (this) {
        is Serve -> client.updateSession(id, unread = unread)
        is Ledger -> api.updateSession(id, unread = unread)
    }

    suspend fun archive(id: String) = when (this) {
        is Serve -> client.updateSession(id, archived = true)
        is Ledger -> api.updateSession(id, archived = true)
    }

    suspend fun delete(id: String) = when (this) {
        is Serve -> client.deleteSession(id)
        is Ledger -> api.deleteSession(id)
    }

    /**
     * Fork a session's transcript. The ledger returns the new session for the caller to open; the
     * Dashboard branches in place and the refreshed list shows it.
     */
    suspend fun fork(id: String, title: String): SessionSummary? = when (this) {
        is Serve -> {
            client.branchSession(id, title)
            null
        }
        is Ledger -> api.forkSession(id, title)
    }

    suspend fun modelOptions(): List<ModelChoice> = when (this) {
        is Serve -> client.modelOptions()
        is Ledger -> api.modelOptions()
    }
}
