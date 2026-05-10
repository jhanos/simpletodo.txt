package io.github.todotxt.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.todotxt.app.model.Note
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists per-task notes as a JSON file in app-private storage (filesDir/notes.json).
 * Images are referenced by their original content:// URIs — no copying.
 * Persistable URI permissions are taken on add and released on remove.
 */
object NoteStorage {

    private const val FILE_NAME = "notes.json"

    // ── Public API ────────────────────────────────────────────────────────

    fun load(context: Context, noteId: String): Note? {
        val all = loadAll(context)
        return all[noteId]
    }

    fun save(context: Context, noteId: String, note: Note) {
        val all = loadAll(context).toMutableMap()
        val existing = all[noteId]

        // Release permissions for images that were removed
        val removedUris = (existing?.images ?: emptyList()) - note.images.toSet()
        removedUris.forEach { releasePermission(context, it) }

        // Take permissions for newly added images
        val addedUris = note.images - (existing?.images ?: emptyList()).toSet()
        addedUris.forEach { takePermission(context, it) }

        all[noteId] = note
        writeAll(context, all)
    }

    fun delete(context: Context, noteId: String) {
        val all = loadAll(context).toMutableMap()
        val note = all.remove(noteId) ?: return
        note.images.forEach { releasePermission(context, it) }
        writeAll(context, all)
    }

    /** Generate a new unique 6-char hex note ID. */
    fun newId(): String {
        val bytes = ByteArray(3)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun loadAll(context: Context): Map<String, Note> {
        return try {
            val f = file(context)
            if (!f.exists()) return emptyMap()
            val json = JSONObject(f.readText())
            val map = mutableMapOf<String, Note>()
            json.keys().forEach { key ->
                val obj = json.getJSONObject(key)
                val text = obj.optString("text", "")
                val imagesArr = obj.optJSONArray("images") ?: JSONArray()
                val images = (0 until imagesArr.length()).map { imagesArr.getString(it) }
                map[key] = Note(text, images)
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeAll(context: Context, all: Map<String, Note>) {
        try {
            val json = JSONObject()
            all.forEach { (id, note) ->
                val obj = JSONObject()
                obj.put("text", note.text)
                val arr = JSONArray()
                note.images.forEach { arr.put(it) }
                obj.put("images", arr)
                json.put(id, obj)
            }
            file(context).writeText(json.toString())
        } catch (_: Exception) {}
    }

    private fun takePermission(context: Context, uriString: String) {
        try {
            val uri = Uri.parse(uriString)
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
    }

    private fun releasePermission(context: Context, uriString: String) {
        try {
            val uri = Uri.parse(uriString)
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
    }
}
