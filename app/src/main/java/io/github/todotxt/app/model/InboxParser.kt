package io.github.todotxt.app.model

/**
 * Parses and serialises inbox.txt.
 *
 * Format:
 *   # Project title
 *   Description lines (may be multi-line or empty)
 *
 *   # Another project
 *   …
 *
 * Rules:
 *  - Lines starting with `#` begin a new project (title = text after `# `).
 *  - All non-heading lines until the next heading (or EOF) form the description.
 *  - Blank lines between the heading and description, or at the end, are preserved
 *    in the description as-is but trimmed at the edges when displaying.
 */
object InboxParser {

    fun parse(lines: List<String>): List<InboxItem> {
        val items = mutableListOf<InboxItem>()
        var currentTitle: String? = null
        val currentDesc = mutableListOf<String>()

        fun flush() {
            val t = currentTitle ?: return
            items += InboxItem(
                title = t,
                description = currentDesc.joinToString("\n").trim()
            )
        }

        for (line in lines) {
            if (line.startsWith("#")) {
                flush()
                currentTitle = line.removePrefix("#").trim()
                currentDesc.clear()
            } else {
                if (currentTitle != null) currentDesc += line
            }
        }
        flush()
        return items
    }

    fun serialize(items: List<InboxItem>): List<String> {
        val lines = mutableListOf<String>()
        items.forEachIndexed { index, item ->
            if (index > 0) lines += ""          // blank line between projects
            lines += "# ${item.title}"
            if (item.description.isNotBlank()) {
                item.description.lines().forEach { lines += it }
            }
        }
        return lines
    }
}
