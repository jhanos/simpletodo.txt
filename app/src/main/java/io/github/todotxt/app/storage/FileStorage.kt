package io.github.todotxt.app.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.BufferedReader
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object FileStorage {

    private const val TAG = "FileStorage"

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

            val msg0 = "findFile: treeUri=$treeUri treeDocId=$treeDocId looking for=$displayName"
            Log.d(TAG, msg0)
            DebugLog.d(context, msg0)

            val cursor = context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )
            if (cursor == null) {
                val msg = "  query returned null cursor"
                Log.e(TAG, msg); DebugLog.e(context, msg)
                return null
            }
            cursor.use {
                val count = it.count
                DebugLog.d(context, "  cursor rows=$count")
                while (it.moveToNext()) {
                    val docId = it.getString(0)
                    val name  = it.getString(1)
                    val msg = "  child docId=$docId displayName=$name"
                    Log.d(TAG, msg); DebugLog.d(context, msg)
                    // Nextcloud may return "subdir/filename" — match on last segment
                    val lastName = name?.substringAfterLast('/') ?: name
                    if (lastName == displayName || name == displayName) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val found = "  FOUND: $uri"
                        Log.d(TAG, found); DebugLog.d(context, found)
                        return uri
                    }
                }
            }
            DebugLog.e(context, "  NOT FOUND: $displayName")
            null
        } catch (e: Exception) {
            Log.e(TAG, "findFile failed for $displayName", e)
            DebugLog.e(context, "findFile EXCEPTION for $displayName", e)
            null
        }
    }

    /** Read all non-blank lines from a document Uri. Returns null on error, empty list for an empty file. */
    fun readLines(context: Context, uri: Uri): List<String>? {
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
            Log.e(TAG, "readLines failed for $uri", e)
            DebugLog.e(context, "readLines EXCEPTION for $uri", e)
            null
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
            Log.e(TAG, "writeLines failed for $uri", e)
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
            Log.e(TAG, "appendLines failed for $uri", e)
            false
        }
    }
}
