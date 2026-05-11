package io.github.todotxt.app.storage

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Lightweight snapshot store — stores periodic snapshots of todo.txt, done.txt,
 * inbox.txt and notes.json in filesDir/snapshots/ as JSON files.
 *
 * No external dependencies. Snapshots older than MAX_DAYS are pruned automatically.
 */
object SnapshotStore {

    private const val SNAPSHOT_DIR = "snapshots"
    private const val MAX_DAYS     = 30L
    private const val MAX_COUNT    = 200
    private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    // ── Public data classes ───────────────────────────────────────────────

    data class SnapshotMeta(
        val id: String,
        val timestamp: LocalDateTime,
        val message: String
    )

    data class Snapshot(
        val id: String,
        val timestamp: LocalDateTime,
        val message: String,
        val todo: String,
        val done: String,
        val inbox: String,
        val notes: String   // raw JSON string of the notes map
    )

    // ── Save ──────────────────────────────────────────────────────────────

    /**
     * Captures the current state of todo/done/inbox (from SAF) + notes.json,
     * writes a snapshot JSON file to filesDir/snapshots/, then prunes old snapshots.
     *
     * Falls back to TASK_CACHE for todo content if SAF is unavailable.
     * Runs synchronously — call from a background thread.
     */
    fun save(context: Context, prefs: SharedPreferences, message: String) {
        try {
            val now = LocalDateTime.now()
            val id  = FILE_FMT.format(now)

            // Read todo — prefer SAF, fall back to cache
            val todoContent = readSafFile(context, prefs, Prefs.TODO_URI, "todo.txt")
                ?: prefs.getString(Prefs.TASK_CACHE, "") ?: ""

            // Read done and inbox — best effort, empty string if unavailable
            val doneContent  = readSafFile(context, prefs, Prefs.DONE_URI, "done.txt") ?: ""
            val inboxContent = readInboxSaf(context, prefs) ?: ""

            // Read notes.json
            val notesFile    = File(context.filesDir, "notes.json")
            val notesContent = if (notesFile.exists()) notesFile.readText() else "{}"

            val json = JSONObject().apply {
                put("id",        id)
                put("timestamp", TS_FMT.format(now))
                put("message",   message)
                put("todo",      todoContent)
                put("done",      doneContent)
                put("inbox",     inboxContent)
                put("notes",     notesContent)
            }

            val dir = snapshotDir(context)
            dir.mkdirs()
            File(dir, "${id}.json").writeText(json.toString())

            prune(context)
        } catch (e: Exception) {
            DebugLog.e(context, "SnapshotStore.save failed", e)
        }
    }

    // ── List ──────────────────────────────────────────────────────────────

    /** Returns all snapshots sorted newest first. */
    fun list(context: Context): List<SnapshotMeta> {
        val dir = snapshotDir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file -> metaFromFile(file) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    // ── Load ──────────────────────────────────────────────────────────────

    /** Loads the full snapshot for the given id, or null on error. */
    fun load(context: Context, id: String): Snapshot? {
        val file = File(snapshotDir(context), "${id}.json")
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            Snapshot(
                id        = json.getString("id"),
                timestamp = LocalDateTime.parse(json.getString("timestamp"), TS_FMT),
                message   = json.getString("message"),
                todo      = json.optString("todo", ""),
                done      = json.optString("done", ""),
                inbox     = json.optString("inbox", ""),
                notes     = json.optString("notes", "{}")
            )
        } catch (e: Exception) {
            DebugLog.e(context, "SnapshotStore.load failed for $id", e)
            null
        }
    }

    // ── Restore ───────────────────────────────────────────────────────────

    /**
     * Restores todo/done/inbox to SAF and notes.json to filesDir.
     * Updates TASK_CACHE. Saves a new snapshot of the pre-restore state first.
     * Returns true on success.
     * Runs synchronously — call from a background thread.
     */
    fun restore(context: Context, prefs: SharedPreferences, snapshot: Snapshot): Boolean {
        return try {
            val treeUri = prefs.getString(Prefs.TREE_URI, null)?.let { Uri.parse(it) }
                ?: return false

            // Write todo.txt
            val todoUri = resolveUri(context, prefs, Prefs.TODO_URI, treeUri, "todo.txt")
                ?: return false
            val todoLines = snapshot.todo.split('\n').filter { it.isNotBlank() }
            if (!FileStorage.writeLines(context, todoUri, todoLines)) return false

            // Write done.txt
            val doneUri = resolveUri(context, prefs, Prefs.DONE_URI, treeUri, "done.txt")
            if (doneUri != null && snapshot.done.isNotBlank()) {
                val doneLines = snapshot.done.split('\n').filter { it.isNotBlank() }
                FileStorage.writeLines(context, doneUri, doneLines)
            }

            // Write inbox.txt
            val inboxUri = FileStorage.findFile(context, treeUri, "inbox.txt")
            if (inboxUri != null && snapshot.inbox.isNotBlank()) {
                FileStorage.writeLines(context, inboxUri, snapshot.inbox.split('\n'))
            }

            // Write notes.json
            File(context.filesDir, "notes.json").writeText(snapshot.notes)

            // Update task cache
            prefs.edit()
                .putString(Prefs.TASK_CACHE, snapshot.todo)
                .putBoolean(Prefs.IS_DIRTY, false)
                .apply()

            true
        } catch (e: Exception) {
            DebugLog.e(context, "SnapshotStore.restore failed", e)
            false
        }
    }

    // ── Prune ─────────────────────────────────────────────────────────────

    private fun prune(context: Context) {
        val dir = snapshotDir(context)
        if (!dir.exists()) return
        val cutoff = LocalDateTime.now().minus(MAX_DAYS, ChronoUnit.DAYS)
        val all = dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> f to (metaFromFile(f)?.timestamp ?: return@mapNotNull null) }
            ?.sortedByDescending { it.second }
            ?: return

        all.forEachIndexed { index, (file, timestamp) ->
            if (index >= MAX_COUNT || timestamp.isBefore(cutoff)) {
                file.delete()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun snapshotDir(context: Context) = File(context.filesDir, SNAPSHOT_DIR)

    private fun metaFromFile(file: File): SnapshotMeta? {
        return try {
            val json = JSONObject(file.readText())
            SnapshotMeta(
                id        = json.getString("id"),
                timestamp = LocalDateTime.parse(json.getString("timestamp"), TS_FMT),
                message   = json.getString("message")
            )
        } catch (_: Exception) { null }
    }

    private fun readSafFile(
        context: Context,
        prefs: SharedPreferences,
        uriPrefKey: String,
        filename: String
    ): String? {
        return try {
            val treeUri = prefs.getString(Prefs.TREE_URI, null)?.let { Uri.parse(it) }
                ?: return null
            val uri = resolveUri(context, prefs, uriPrefKey, treeUri, filename) ?: return null
            FileStorage.readLines(context, uri)?.joinToString("\n")
        } catch (_: Exception) { null }
    }

    private fun readInboxSaf(context: Context, prefs: SharedPreferences): String? {
        return try {
            val treeUri = prefs.getString(Prefs.TREE_URI, null)?.let { Uri.parse(it) }
                ?: return null
            val uri = FileStorage.findFile(context, treeUri, "inbox.txt") ?: return null
            FileStorage.readLines(context, uri)?.joinToString("\n")
        } catch (_: Exception) { null }
    }

    private fun resolveUri(
        context: Context,
        prefs: SharedPreferences,
        uriPrefKey: String,
        treeUri: Uri,
        filename: String
    ): Uri? {
        return prefs.getString(uriPrefKey, null)?.let { Uri.parse(it) }
            ?: FileStorage.findFile(context, treeUri, filename)?.also { resolved ->
                prefs.edit().putString(uriPrefKey, resolved.toString()).apply()
            }
    }
}
