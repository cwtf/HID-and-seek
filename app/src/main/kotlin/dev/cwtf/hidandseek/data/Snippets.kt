package dev.cwtf.hidandseek.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A saved buffer.
 *
 * Sensitive snippets keep their [content] empty here and hold the real text in
 * the encrypted store instead — passwords and licence keys are exactly what
 * people save snippets for, and DataStore is plain JSON on disk.
 */
@Serializable
data class Snippet(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val content: String = "",
    val sensitive: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    val secretAlias: String get() = "snippet_$id"
}

@Serializable
data class Snippets(
    val items: List<Snippet> = emptyList(),
) {
    fun find(id: String) = items.firstOrNull { it.id == id }

    fun upsert(snippet: Snippet): Snippets {
        val index = items.indexOfFirst { it.id == snippet.id }
        return copy(
            items = if (index >= 0) {
                items.toMutableList().apply { this[index] = snippet }
            } else {
                items + snippet
            },
        )
    }

    fun remove(id: String) = copy(items = items.filterNot { it.id == id })
}
