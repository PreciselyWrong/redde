package ch.nicolab.redde.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The agent's task list (Hermes's to-do tool). Part of TodoList.swift: the snapshot the Dashboard
 * sends after every change (`todo.updated`, `todo_state` on a resume). Reading the list out of the
 * tool calls themselves (the Hermes API, transcripts read back) is still to port.
 */
object TodoList {
    /** `todo_list` from Hermes 0.21.3; `todo` before. */
    fun isTool(name: String) = name == "todo_list" || name == "todo"

    /** The list out of a snapshot the host sends. */
    fun list(snapshot: JsonElement?): List<TodoItem>? {
        val listed = snapshot["todos"].arr ?: return null
        return sanitized(listed.mapNotNull(::item))
    }

    private fun item(value: JsonElement): TodoItem? {
        val o = value as? JsonObject ?: return null
        val id = (o["id"].str ?: o["id"].int?.toString() ?: "").trim()
        if (id.isEmpty()) return null
        val content = o["content"].str?.trim()?.ifEmpty { null }
        val status = o["status"].str?.trim()?.lowercase()?.let { s -> TodoItem.Status.entries.firstOrNull { it.wire == s } }
        val parent = (o["parent"].str ?: o["parent"].int?.toString())?.trim()?.ifEmpty { null }
        return TodoItem(id, content ?: "(no description)", status ?: TodoItem.Status.PENDING, if (parent == id) null else parent)
    }

    /** No item under one that isn't there, and no loop of parents. */
    private fun sanitized(items: List<TodoItem>): List<TodoItem> {
        val ids = items.map { it.id }.toSet()
        val fixed = items.map { if (it.parent != null && (it.parent !in ids || it.parent == it.id)) it.copy(parent = null) else it }.toMutableList()
        val parents = HashMap<String, String?>()
        for (i in fixed) parents.putIfAbsent(i.id, i.parent)
        for (i in fixed.indices) {
            val seen = mutableSetOf(fixed[i].id)
            var node = fixed[i].parent
            while (node != null) {
                if (!seen.add(node)) {
                    fixed[i] = fixed[i].copy(parent = null)
                    break
                }
                node = parents[node]
            }
        }
        return fixed
    }
}

private val TodoItem.Status.wire: String
    get() = when (this) {
        TodoItem.Status.PENDING -> "pending"
        TodoItem.Status.IN_PROGRESS -> "in_progress"
        TodoItem.Status.COMPLETED -> "completed"
        TodoItem.Status.CANCELLED -> "cancelled"
    }
