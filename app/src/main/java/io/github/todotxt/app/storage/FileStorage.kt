package io.github.todotxt.app.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object FileStorage {

    /**
     * Find a document with [displayName] inside [treeUri].
     * Returns the document Uri, or null if not found or on error.
     *
     * Note: Nextcloud's SAF provider does not support creating documents via
     * DocumentsContract.createDocument — the files must already exist in Nextcloud
     * before selecting the folder in this app.
     */
    fun findFile(
        context: Context,
        treeUri: Uri,
        displayName: String
    ): Uri? {
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

            DebugLog.d(context, "findFile: looking for=$displayName in treeDocId=$treeDocId")

            val cursor = context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )
            if (cursor == null) {
                DebugLog.e(context, "findFile: query returned null cursor for $displayName")
                return null
            }
            cursor.use {
                while (it.moveToNext()) {
                    val docId = it.getString(0)
                    val name  = it.getString(1)
                    // Nextcloud may return "subdir/filename" — match on last segment
                    val lastName = name?.substringAfterLast('/') ?: name
                    if (lastName == displayName || name == displayName) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        DebugLog.d(context, "findFile: FOUND $displayName -> $uri")
                        return uri
                    }
                }
            }
            DebugLog.e(context, "findFile: NOT FOUND: $displayName")
            null
        } catch (e: Exception) {
            DebugLog.e(context, "findFile EXCEPTION for $displayName", e)
            null
        }
    }

    /** Read all non-blank lines from a document Uri. Returns null on error, empty list for an empty file. */
    fun readLines(context: Context, uri: Uri): List<String>? {
        return readLinesAttempt(context, uri, retryOnFailure = true)
    }

    private fun readLinesAttempt(context: Context, uri: Uri, retryOnFailure: Boolean): List<String>? {
        return try {
            DebugLog.d(context, "readLines: opening $uri")
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                DebugLog.e(context, "readLines: openInputStream returned null for $uri")
                return emptyList()
            }
            val lines = stream.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8))
                    .readLines()
                    .filter { l -> l.isNotBlank() }
            }
            DebugLog.d(context, "readLines: got ${lines.size} lines from $uri")
            lines
        } catch (e: Exception) {
            DebugLog.e(context, "readLines EXCEPTION for $uri", e)
            if (retryOnFailure) {
                DebugLog.d(context, "readLines: retrying after 2s delay")
                Thread.sleep(2000)
                readLinesAttempt(context, uri, retryOnFailure = false)
            } else {
                null
            }
        }
    }

    /**
     * Overwrite the file at [uri] with [lines] joined by "\n".
     * Returns true on success.
     *
     * Uses openFileDescriptor("wt") to force truncation before writing.
     * This is more reliable than openOutputStream("w") on Nextcloud, which
     * appends rather than replacing existing content.
     */
    fun writeLines(context: Context, uri: Uri, lines: List<String>): Boolean {
        return try {
            val content = buildString {
                lines.forEach { append(it); append('\n') }
            }
            val bytes = content.toByteArray(Charsets.UTF_8)
            DebugLog.d(context, "writeLines: writing ${lines.size} lines (${bytes.size} bytes) to $uri")
            val pfd = context.contentResolver.openFileDescriptor(uri, "wt")
                ?: run {
                    DebugLog.e(context, "writeLines: openFileDescriptor returned null for $uri")
                    return false
                }
            pfd.use {
                java.io.FileOutputStream(it.fileDescriptor).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                }
            }
            DebugLog.d(context, "writeLines: done")
            true
        } catch (e: Exception) {
            DebugLog.e(context, "writeLines EXCEPTION for $uri", e)
            false
        }
    }

    /**
     * Append [lines] to [uri] (used when archiving to done.txt).
     * Returns true on success.
     */
    fun appendLines(context: Context, uri: Uri, lines: List<String>): Boolean {
        if (lines.isEmpty()) return true
        return try {
            context.contentResolver.openOutputStream(uri, "wa")?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write(lines.joinToString("\n"))
                    writer.write("\n")
                }
            }
            true
        } catch (e: Exception) {
            DebugLog.e(context, "appendLines EXCEPTION for $uri", e)
            false
        }
    }

    /**
     * Read inbox.txt from [treeUri]. Returns parsed lines (including blank lines),
     * or an empty list if the file is not found or on error.
     */
    fun readInboxLines(context: Context, treeUri: Uri): List<String> {
        return try {
            val uri = findFile(context, treeUri, "inbox.txt") ?: return emptyList()
            val stream = context.contentResolver.openInputStream(uri) ?: return emptyList()
            stream.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readLines()
            }
        } catch (e: Exception) {
            DebugLog.e(context, "readInboxLines EXCEPTION", e)
            emptyList()
        }
    }

    /**
     * Write [lines] to inbox.txt in [treeUri]. Returns true on success.
     */
    fun writeInboxLines(context: Context, treeUri: Uri, lines: List<String>): Boolean {
        return try {
            val uri = findFile(context, treeUri, "inbox.txt") ?: run {
                DebugLog.e(context, "writeInboxLines: inbox.txt not found in tree")
                return false
            }
            writeLines(context, uri, lines)
        } catch (e: Exception) {
            DebugLog.e(context, "writeInboxLines EXCEPTION", e)
            false
        }
    }
}
