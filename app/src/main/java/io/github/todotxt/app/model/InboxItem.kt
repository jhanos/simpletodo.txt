package io.github.todotxt.app.model

/** A single project entry from inbox.txt — one `# Title` heading plus its description lines. */
data class InboxItem(
    val title: String,
    val description: String
)
