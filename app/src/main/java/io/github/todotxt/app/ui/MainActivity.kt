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
        private const val PREF_TODO_URI        = "pref_todo_uri"    // cached document URI
        private const val PREF_DONE_URI        = "pref_done_uri"    // cached document URI
        private const val PREF_TASK_CACHE      = "pref_task_cache"  // last-known task lines
        private const val PREF_SORT_FIELD      = "pref_sort_field"
        private const val PREF_SHOW_FUTURE     = "pref_show_future"
        private const val PREF_FILTER_CONTEXTS = "pref_filter_contexts"
        private const val PREF_FILTER_PROJECTS = "pref_filter_projects"
        private const val PREF_FILTER_TEXT     = "pref_filter_text"

        private const val RESUME_RELOAD_DEBOUNCE_MS = 5_000L

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

    // Timestamp of the last successful load completion (ms); 0 = never loaded
    private var lastLoadMs: Long = 0

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
        showCachedTasksIfAvailable()
        loadTodoFile()
    }

    override fun onResume() {
        super.onResume()
        DebugLog.d(this, "=== MainActivity onResume ===")
        // Skip reload if we loaded successfully very recently (e.g. just returned from
        // AddEdit/Filter which calls onResume right after onActivityResult).
        val age = System.currentTimeMillis() - lastLoadMs
        if (age < RESUME_RELOAD_DEBOUNCE_MS) {
            DebugLog.d(this, "onResume: skipping reload — last load was ${age}ms ago")
            return
        }
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
            REQ_SETTINGS -> {
                // User may have changed the folder — invalidate cached document URIs
                // so the next load re-resolves them via findFile.
                prefs().edit().remove(PREF_TODO_URI).remove(PREF_DONE_URI).apply()
                lastLoadMs = 0
                loadTodoFile()
            }
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
        val archivedLines = archived.map { it.text }
        val todoLines = todoList.toLines()
        val treeUri = prefs().getString(PREF_TREE_URI, null)?.let { Uri.parse(it) }
        val cachedTodoUriStr = prefs().getString(PREF_TODO_URI, null)
        val cachedDoneUriStr = prefs().getString(PREF_DONE_URI, null)
        isSaving.set(true)
        Thread {
            try {
                if (treeUri == null) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.archive_no_folder, Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                val doneUri: Uri? = if (cachedDoneUriStr != null) {
                    Uri.parse(cachedDoneUriStr)
                } else {
                    val resolved = FileStorage.findFile(this, treeUri, "done.txt")
                    if (resolved != null) {
                        prefs().edit().putString(PREF_DONE_URI, resolved.toString()).apply()
                    }
                    resolved
                }
                if (doneUri == null) {
                    runOnUiThread {
                        Toast.makeText(this, R.string.archive_no_folder, Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                FileStorage.appendLines(this, doneUri, archivedLines)
                val todoUri: Uri? = if (cachedTodoUriStr != null) {
                    Uri.parse(cachedTodoUriStr)
                } else {
                    val resolved = FileStorage.findFile(this, treeUri, "todo.txt")
                    if (resolved != null) {
                        prefs().edit().putString(PREF_TODO_URI, resolved.toString()).apply()
                    }
                    resolved
                }
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
        // Read cached document URI — avoids a ContentResolver directory query on every load
        val cachedUriStr = prefs().getString(PREF_TODO_URI, null)
        Thread {
            try {
                if (isSaving.get()) {
                    DebugLog.d(this, "loadTodoFile: skipped on thread — write in flight")
                    return@Thread
                }
                // Try the cached URI first; fall back to findFile if it's missing or stale
                val uri: Uri? = if (cachedUriStr != null) {
                    DebugLog.d(this, "loadTodoFile: using cached uri=$cachedUriStr")
                    Uri.parse(cachedUriStr)
                } else {
                    DebugLog.d(this, "loadTodoFile: no cached uri — resolving via findFile")
                    val resolved = FileStorage.findFile(this, treeUri, "todo.txt")
                    if (resolved != null) {
                        prefs().edit().putString(PREF_TODO_URI, resolved.toString()).apply()
                        DebugLog.d(this, "loadTodoFile: cached uri=$resolved")
                    }
                    resolved
                }
                if (uri == null) {
                    DebugLog.e(this, "loadTodoFile: could not resolve todo.txt uri")
                    runOnUiThread {
                        Toast.makeText(this, R.string.load_error, Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val lines = FileStorage.readLines(this, uri)
                if (lines == null) {
                    // Cached URI may be stale — clear it and let the next load re-resolve
                    if (cachedUriStr != null) {
                        DebugLog.d(this, "loadTodoFile: read failed on cached uri — clearing cache")
                        prefs().edit().remove(PREF_TODO_URI).apply()
                    }
                    DebugLog.e(this, "loadTodoFile: readLines returned null")
                    runOnUiThread {
                        Toast.makeText(this, R.string.load_error, Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                DebugLog.d(this, "loadTodoFile: loaded ${lines.size} lines")
                todoList.loadFromLines(lines)
                persistTaskCache(lines)
                lastLoadMs = System.currentTimeMillis()
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
        val cachedUriStr = prefs().getString(PREF_TODO_URI, null)
        isSaving.set(true)
        Thread {
            try {
                val uri: Uri? = if (cachedUriStr != null) {
                    Uri.parse(cachedUriStr)
                } else {
                    val resolved = FileStorage.findFile(this, treeUri, "todo.txt")
                    if (resolved != null) {
                        prefs().edit().putString(PREF_TODO_URI, resolved.toString()).apply()
                    }
                    resolved
                }
                if (uri == null) return@Thread
                val ok = FileStorage.writeLines(this, uri, lines)
                if (!ok) {
                    // Write failed — cached URI may be stale, clear it
                    prefs().edit().remove(PREF_TODO_URI).apply()
                    runOnUiThread {
                        Toast.makeText(this, R.string.save_error, Toast.LENGTH_SHORT).show()
                    }
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

    // ── Task cache (instant startup display) ─────────────────────────────

    /**
     * If we have a previously persisted task list, load it into [todoList] and
     * render it immediately on the main thread — before the SAF background load
     * completes. The background load will overwrite this once it finishes.
     */
    private fun showCachedTasksIfAvailable() {
        val cached = prefs().getString(PREF_TASK_CACHE, null) ?: return
        val lines = cached.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        DebugLog.d(this, "showCachedTasksIfAvailable: rendering ${lines.size} cached lines")
        todoList.loadFromLines(lines)
        refreshList()
    }

    /** Persist the current task lines so they can be shown instantly next launch. */
    private fun persistTaskCache(lines: List<String>) {
        prefs().edit().putString(PREF_TASK_CACHE, lines.joinToString("\n")).apply()
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
