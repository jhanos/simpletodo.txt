package io.github.todotxt.app.model

data class Note(
    val text: String = "",
    val images: List<String> = emptyList()  // content:// URIs with persisted permissions
)
