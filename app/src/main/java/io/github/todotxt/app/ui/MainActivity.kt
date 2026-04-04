package io.github.todotxt.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import io.github.todotxt.app.R
import io.github.todotxt.app.model.SortField
import io.github.todotxt.app.model.Task
import io.github.todotxt.app.model.TaskItem
import io.github.todotxt.app.model.TodoList
import io.github.todotxt.app.storage.DebugLog
import io.github.todotxt.app.storage.FileStorage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    companion object {
        private const val REQ_ADD_TASK     = 1
        private const val REQ_EDIT_TASK    = 2
        private const val REQ_FILTER       = 3
        private const val REQ_SETTINGS     = 4

        private const val PREF_TREE_URI        = "pref_tree_uri"
        private const val PREF_SORT_FIELD      = "pref_sort_field"
        private const val PREF_SHOW_FUTURE     = "pref_show_future"
        private const val PREF_FILTER_CONTEXTS = "pref_filter_contexts"
        private const val PREF_FILTER_PROJECTS = "pref_filter_projects"
        private const val PREF_FILTER_TEXT     = "pref_filter_text"

        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private val todoList = TodoList()
    private lateinit var adapter: TaskAdapter
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView

    // True while a background write is in flight — suppress reloads during that window
    private val isSaving  = AtomicBoolean(false)
    // True while a background load is already in flight — drop duplicate requests
    private val isLoading = AtomicBoolean(false)

    // Current filter/sort state
    private var sortField      = SortField.PRIORITY
    private var showFuture     = false
    private var filterContexts = emptySet<String>()
    private var filterProjects = emptySet<String>()
    private var filterText     = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        DebugLog.clear(this)
        DebugLog.d(this, "=== MainActivity onCreate ===")

        listView  = findViewById(R.id.listView)
        emptyView = findViewById(R.id.emptyView)

        adapter = TaskAdapter(
            context          = this,
            onToggleComplete = { item -> toggleComplete(item) },
            onEdit           = { item -> openEdit(item) },
            onDelete         = { item -> confirmDelete(item) }
        )
        listView.adapter = adapter

        findViewById<Button>(R.id.fab).setOnClickListener {
            startActivityForResult(
                Intent(this, AddEditActivity::class.java).apply {
                    putStringArrayListExtra(AddEditActivity.EXTRA_ALL_CONTEXTS, ArrayList(todoList.allContexts))
                    putStringArrayListExtra(AddEditActivity.EXTRA_ALL_PROJECTS, ArrayList(todoList.allProjects))
                },
                REQ_ADD_TASK
            )
        }

        loadPrefs()
        loadTodoFile()
    }

    override fun onResume() {
        super.onResume()
        DebugLog.d(this, "=== MainActivity onResume ===")
        // Re-read from disk on every resume so Nextcloud-synced changes are picked up.
        loadTodoFile()
    }

    // ── Options menu ──────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_filter   -> { openFilter(); true }
            R.id.action_archive  -> { archiveCompleted(); true }
            R.id.action_settings -> { openSettings(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Activity results ──────────────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_ADD_TASK -> if (resultCode == RESULT_OK) {
                val raw = data?.getStringExtra(AddEditActivity.EXTRA_TASK_TEXT) ?: return
                todoList.add(Task(raw))
                saveTodoFile()
                refreshList()
            }
            REQ_EDIT_TASK -> if (resultCode == RESULT_OK) {
                val raw     = data?.getStringExtra(AddEditActivity.EXTRA_TASK_TEXT) ?: return
                val oldText = data.getStringExtra(AddEditActivity.EXTRA_OLD_TASK_TEXT) ?: return
                val oldTask = todoList.getAll().firstOrNull { it.text == oldText } ?: return
                todoList.update(oldTask, Task(raw))
                saveTodoFile()
                refreshList()
            }
            REQ_FILTER -> if (resultCode == RESULT_OK && data != null) {
                sortField      = SortField.valueOf(
                    data.getStringExtra(FilterActivity.EXTRA_SORT_FIELD) ?: SortField.PRIORITY.name
                )
                showFuture     = data.getBooleanExtra(FilterActivity.EXTRA_SHOW_FUTURE, false)
                filterContexts = data.getStringArrayExtra(FilterActivity.EXTRA_CONTEXTS)
                    ?.toSet() ?: emptySet()
                filterProjects = data.getStringArrayExtra(FilterActivity.EXTRA_PROJECTS)
                    ?.toSet() ?: emptySet()
                filterText     = data.getStringExtra(FilterActivity.EXTRA_FILTER_TEXT) ?: ""
                savePrefs()
                refreshList()
            }
            REQ_SETTINGS -> loadTodoFile()
        }
    }

    // ── Core operations ───────────────────────────────────────────────────

    private fun toggleComplete(item: TaskItem) {
        val today = LocalDate.now().format(DATE_FMT)
        if (item.task.completed) {
            todoList.markIncomplete(item.task)
        } else {
            todoList.markComplete(item.task, today)
        }
        saveTodoFile()
        refreshList()
    }

    private fun openEdit(item: TaskItem) {
        startActivityForResult(
            Intent(this, AddEditActivity::class.java).apply {
                putExtra(AddEditActivity.EXTRA_TASK_TEXT, item.task.text)
                putStringArrayListExtra(AddEditActivity.EXTRA_ALL_CONTEXTS, ArrayList(todoList.allContexts))
                putStringArrayListExtra(AddEditActivity.EXTRA_ALL_PROJECTS, ArrayList(todoList.allProjects))
            },
            REQ_EDIT_TASK
        )
    }

    private fun confirmDelete(item: TaskItem) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.yes) { _, _ ->
                todoList.remove(item.task)
                saveTodoFile()
                refreshList()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun archiveCompleted() {
        val archived = todoList.archiveCompleted()
        if (archived.isEmpty()) return
        // Snapshot both line lists on the main thread before handing off,
        // so a concurrent loadTodoFile() cannot race with our write.
        val archivedLines = archived.map { it.text }
        val todoLines = todoList.toLines()
        isSaving.set(true)
        Thread {
            try {
                val treeUri = prefs().getString(PREF_TREE_URI, null)?.let { Uri.parse(it) }
                if (treeUri == null) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.archive_no_folder, Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val doneUri = FileStorage.findFile(this, treeUri, "done.txt")
                if (doneUri == null) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.archive_no_folder, Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                FileStorage.appendLines(this, doneUri, archivedLines)
                val todoUri = FileStorage.findFile(this, treeUri, "todo.txt")
                if (todoUri != null) {
                    FileStorage.writeLines(this, todoUri, todoLines)
                }
                runOnUiThread {
                    refreshList()
                    Toast.makeText(this, getString(R.string.archive_done, archived.size), Toast.LENGTH_SHORT).show()
                }
            } finally {
                isSaving.set(false)
            }
        }.start()
    }

    private fun openFilter() {
        startActivityForResult(
            Intent(this, FilterActivity::class.java).apply {
                putExtra(FilterActivity.EXTRA_SORT_FIELD,     sortField.name)
                putExtra(FilterActivity.EXTRA_SHOW_FUTURE,    showFuture)
                putStringArrayListExtra(FilterActivity.EXTRA_ALL_CONTEXTS,
                    ArrayList(todoList.allContexts))
                putStringArrayListExtra(FilterActivity.EXTRA_ALL_PROJECTS,
                    ArrayList(todoList.allProjects))
                putExtra(FilterActivity.EXTRA_CONTEXTS, filterContexts.toTypedArray())
                putExtra(FilterActivity.EXTRA_PROJECTS, filterProjects.toTypedArray())
                putExtra(FilterActivity.EXTRA_FILTER_TEXT, filterText)
            },
            REQ_FILTER
        )
    }

    private fun openSettings() {
        startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
    }

    // ── File I/O (always on a background thread) ──────────────────────────

    private fun loadTodoFile() {
        if (isSaving.get()) {
            DebugLog.d(this, "loadTodoFile: skipped — write in flight")
            return
        }
        if (!isLoading.compareAndSet(false, true)) {
            DebugLog.d(this, "loadTodoFile: skipped — load already in flight")
            return
        }
        val treeUri = prefs().getString(PREF_TREE_URI, null)?.let { Uri.parse(it) }
        if (treeUri == null) {
            DebugLog.d(this, "loadTodoFile: no tree URI set yet")
            isLoading.set(false)
            return
        }
        Thread {
            try {
                if (isSaving.get()) {
                    DebugLog.d(this, "loadTodoFile: skipped on thread — write in flight")
                    return@Thread
                }
                val uri = FileStorage.findFile(this, treeUri, "todo.txt")
                if (uri == null) {
                    DebugLog.e(this, "loadTodoFile: findFile returned null")
                    runOnUiThread {
                        Toast.makeText(this, R.string.load_error, Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                DebugLog.d(this, "loadTodoFile: resolved uri=$uri")
                val lines = FileStorage.readLines(this, uri)
                if (lines == null) {
                    DebugLog.e(this, "loadTodoFile: readLines returned null")
                    runOnUiThread {
                        Toast.makeText(this, R.string.load_error, Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                DebugLog.d(this, "loadTodoFile: loaded ${lines.size} lines")
                todoList.loadFromLines(lines)
                runOnUiThread { refreshList() }
            } finally {
                isLoading.set(false)
            }
        }.start()
    }

    private fun saveTodoFile() {
        // Snapshot the lines on the main thread before handing off
        val lines = todoList.toLines()
        val treeUri = prefs().getString(PREF_TREE_URI, null)?.let { Uri.parse(it) } ?: return
        isSaving.set(true)
        Thread {
            try {
                val uri = FileStorage.findFile(this, treeUri, "todo.txt") ?: return@Thread
                val ok = FileStorage.writeLines(this, uri, lines)
                if (!ok) runOnUiThread {
                    Toast.makeText(this, R.string.save_error, Toast.LENGTH_SHORT).show()
                }
            } finally {
                isSaving.set(false)
            }
        }.start()
    }

    private fun refreshList() {
        val today = LocalDate.now().format(DATE_FMT)
        val items = todoList.filteredAndGrouped(
            showFuture     = showFuture,
            today          = today,
            filterContexts = filterContexts,
            filterProjects = filterProjects,
            filterText     = filterText,
            sortField      = sortField
        )
        adapter.setItems(items)
        emptyView.visibility = if (items.isEmpty()) android.view.View.VISIBLE
                               else android.view.View.GONE
    }

    // ── Preferences ───────────────────────────────────────────────────────

    private fun prefs() = getSharedPreferences("todotxt", MODE_PRIVATE)

    private fun loadPrefs() {
        val p = prefs()
        sortField      = SortField.valueOf(p.getString(PREF_SORT_FIELD, SortField.PRIORITY.name)!!)
        showFuture     = p.getBoolean(PREF_SHOW_FUTURE, false)
        filterContexts = p.getStringSet(PREF_FILTER_CONTEXTS, emptySet())!!
        filterProjects = p.getStringSet(PREF_FILTER_PROJECTS, emptySet())!!
        filterText     = p.getString(PREF_FILTER_TEXT, "") ?: ""
    }

    private fun savePrefs() {
        prefs().edit().apply {
            putString(PREF_SORT_FIELD,      sortField.name)
            putBoolean(PREF_SHOW_FUTURE,    showFuture)
            putStringSet(PREF_FILTER_CONTEXTS, filterContexts)
            putStringSet(PREF_FILTER_PROJECTS, filterProjects)
            putString(PREF_FILTER_TEXT,     filterText)
            apply()
        }
    }
}
