package io.github.todotxt.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import io.github.todotxt.app.R
import io.github.todotxt.app.storage.Prefs
import io.github.todotxt.app.storage.SnapshotStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class HistoryActivity : Activity() {

    companion object {
        /** Set on RESULT_OK when a restore was performed — MainActivity should reload. */
        const val EXTRA_RESTORED = "history_restored"
        private val DISPLAY_FMT  = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val DAY_FMT      = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private var snapshots: List<SnapshotStore.SnapshotMeta> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        setTitle(R.string.history_title)

        listView  = findViewById(R.id.historyList)
        emptyView = findViewById(R.id.historyEmptyView)

        listView.setOnItemClickListener { _, _, position, _ ->
            onSnapshotTapped(snapshots[position])
        }

        loadSnapshots()
    }

    private fun loadSnapshots() {
        Thread {
            val list = SnapshotStore.list(this)
            runOnUiThread {
                snapshots = list
                if (list.isEmpty()) {
                    listView.visibility  = View.GONE
                    emptyView.visibility = View.VISIBLE
                } else {
                    emptyView.visibility = View.GONE
                    listView.visibility  = View.VISIBLE
                    listView.adapter     = SnapshotAdapter(this, list)
                }
            }
        }.start()
    }

    private fun onSnapshotTapped(meta: SnapshotStore.SnapshotMeta) {
        Thread {
            val snapshot = SnapshotStore.load(this, meta.id)
            runOnUiThread {
                if (snapshot == null) {
                    Toast.makeText(this, R.string.history_load_error, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                showPreviewDialog(snapshot)
            }
        }.start()
    }

    private fun showPreviewDialog(snapshot: SnapshotStore.Snapshot) {
        val preview = snapshot.todo
            .split('\n')
            .filter { it.isNotBlank() }
            .take(10)
            .joinToString("\n")
            .ifBlank { getString(R.string.history_empty_todo) }

        val title = DISPLAY_FMT.format(snapshot.timestamp)
        val body  = "${snapshot.message}\n\n${getString(R.string.history_preview_label)}\n$preview"

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(R.string.history_restore) { _, _ -> confirmRestore(snapshot) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRestore(snapshot: SnapshotStore.Snapshot) {
        AlertDialog.Builder(this)
            .setMessage(R.string.history_restore_confirm)
            .setPositiveButton(R.string.yes) { _, _ -> doRestore(snapshot) }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun doRestore(snapshot: SnapshotStore.Snapshot) {
        Toast.makeText(this, R.string.history_restoring, Toast.LENGTH_SHORT).show()
        Thread {
            // Save a snapshot of the current state before restoring (so the restore is undoable)
            val restoreMsg = getString(
                R.string.history_restore_snapshot_msg,
                DISPLAY_FMT.format(snapshot.timestamp)
            )
            SnapshotStore.save(this, prefs, restoreMsg)

            val ok = SnapshotStore.restore(this, prefs, snapshot)
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, R.string.history_restored_ok, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_RESTORED, true))
                    finish()
                } else {
                    Toast.makeText(this, R.string.history_restore_error, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    private inner class SnapshotAdapter(
        private val ctx: Context,
        private val items: List<SnapshotStore.SnapshotMeta>
    ) : BaseAdapter() {

        private val today     = LocalDate.now()
        private val yesterday = today.minusDays(1)

        override fun getCount(): Int = items.size
        override fun getItem(pos: Int): Any = items[pos]
        override fun getItemId(pos: Int): Long = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.item_snapshot, parent, false)
            val meta = items[pos]
            view.findViewById<TextView>(R.id.snapshotMessage).text   = meta.message
            view.findViewById<TextView>(R.id.snapshotTimestamp).text  = formatTimestamp(meta)
            return view
        }

        private fun formatTimestamp(meta: SnapshotStore.SnapshotMeta): String {
            val date = meta.timestamp.toLocalDate()
            val time = meta.timestamp.toLocalTime()
                .format(DateTimeFormatter.ofPattern("HH:mm"))
            val day = when (date) {
                today     -> ctx.getString(R.string.history_today)
                yesterday -> ctx.getString(R.string.history_yesterday)
                else      -> DAY_FMT.format(date)
            }
            return "$day  $time"
        }
    }
}
