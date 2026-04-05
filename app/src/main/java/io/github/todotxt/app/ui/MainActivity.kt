package io.github.todotxt.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import io.github.todotxt.app.R
import io.github.todotxt.app.model.InboxItem
import io.github.todotxt.app.model.InboxParser
import io.github.todotxt.app.model.SortField
import io.github.todotxt.app.model.Task
import io.github.todotxt.app.model.TaskItem
import io.github.todotxt.app.model.TodoList
import io.github.todotxt.app.storage.DebugLog
import io.github.todotxt.app.storage.FileStorage
import io.github.todotxt.app.storage.ReminderScheduler
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

enum class ActiveView { INBOX, NEXT, FROZEN, SCHEDULED, SOMEDAY }

class MainActivity : Activity() {

    companion object {
        private const val REQ_ADD_TASK     = 1
        private const val REQ_EDIT_TASK    = 2
        private const val REQ_FILTER       = 3
        private const val REQ_SETTINGS     = 4
        private const val REQ_INBOX_ADD    = 5
        private const val REQ_INBOX_EDIT   = 6

        private const val PREF_TREE_URI        = "pref_tree_uri"
        private const val PREF_TODO_URI        = "pref_todo_uri"
        private const val PREF_DONE_URI        = "pref_done_uri"
        private const val PREF_TASK_CACHE      = "pref_task_cache"
        private const val PREF_SORT_FIELD      = "pref_sort_field"
        private const val PREF_SHOW_FUTURE     = "pref_show_future"
        private const val PREF_FILTER_CONTEXTS = "pref_filter_contexts"
        private const val PREF_FILTER_PROJECTS = "pref_filter_projects"
        private const val PREF_FILTER_TEXT     = "pref_filter_text"
        private const val PREF_ACTIVE_VIEW     = "pref_active_view"

        private const val RESUME_RELOAD_DEBOUNCE_MS = 5_000L

        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private val todoList = TodoList()
    private lateinit var adapter: TaskAdapter
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var drawerList: ListView
    private lateinit var drawerScrim: View
    private lateinit var fab: Button
    private val prefs by lazy { getSharedPreferences("todotxt", MODE_PRIVATE) }

    private val isSaving  = AtomicBoolean(false)
    private val isLoading = AtomicBoolean(false)
    private var lastLoadMs: Long = 0

    // Current filter/sort state (used for non-inbox views)
    private var sortField      = SortField.PRIORITY
    private var showFuture     = false
    private var filterContexts = emptySet<String>()
    private var filterProjects = emptySet<String>()
    private var filterText     = ""

    // Active navigation view
    private var activeView = ActiveView.NEXT

    // Inbox data
    private var inboxItems = mutableListOf<InboxItem>()
    private lateinit var inboxAdapter: InboxAdapter

    // Drawer navigation entries
    private val navEntries by lazy {
        listOf(
            getString(R.string.nav_inbox),
            getString(R.string.nav_next),
            getString(R.string.nav_frozen),
            getString(R.string.nav_scheduled),
            getString(R.string.nav_someday)
        )
    }
    private val navViews = listOf(
        ActiveView.INBOX,
        ActiveView.NEXT,
        ActiveView.FROZEN,
        ActiveView.SCHEDULED,
        ActiveView.SOMEDAY
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        DebugLog.clear(this)
        DebugLog.d(this, "=== MainActivity onCreate ===")

        drawerList   = findViewById(R.id.drawerList)
        drawerScrim  = findViewById(R.id.drawerScrim)
        listView     = findViewById(R.id.listView)
        emptyView    = findViewById(R.id.emptyView)
        fab          = findViewById(R.id.fab)

        // ── Task list adapter ──
        adapter = TaskAdapter(
            context          = this,
            onToggleComplete = { item -> toggleComplete(item) },
            onEdit           = { item -> openEdit(item) },
            onDelete         = { item -> confirmDelete(item) },
            onToggleFreeze   = { item -> toggleFreeze(item) },
            onToggleSomeday  = { item -> toggleSomeday(item) }
        )
        listView.adapter = adapter

        // ── Inbox adapter ──
        inboxAdapter = InboxAdapter(
            context   = this,
            items     = inboxItems,
            onEdit    = { index -> openInboxEdit(index) }
        )

        // ── Drawer ──
        val drawerAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, navEntries)
        drawerList.adapter = drawerAdapter
        drawerList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            switchView(navViews[position])
            closeDrawer()
        }

        // Scrim closes the drawer when tapped
        drawerScrim.setOnClickListener { closeDrawer() }

        // ── Hamburger / home button ──
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_hamburger)
        }

        // ── FAB ──
        fab.setOnClickListener { onFabClicked() }

        loadPrefs()
        showCachedTasksIfAvailable()
        loadTodoFile()
        // Inbox is loaded on demand when switching to that view
    }

    override fun onResume() {
        super.onResume()
        DebugLog.d(this, "=== MainActivity onResume ===")
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
            android.R.id.home    -> { toggleDrawer(); true }
            R.id.action_filter   -> { openFilter(); true }
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
                prefs.edit().remove(PREF_TODO_URI).remove(PREF_DONE_URI).apply()
                lastLoadMs = 0
                loadTodoFile()
            }
            REQ_INBOX_ADD, REQ_INBOX_EDIT -> if (resultCode == RESULT_OK && data != null) {
                handleInboxResult(data)
            }
        }
    }

    // ── Drawer helpers ────────────────────────────────────────────────────

    private fun openDrawer() {
        drawerList.visibility = View.VISIBLE
        drawerScrim.visibility = View.VISIBLE
    }

    private fun closeDrawer() {
        drawerList.visibility = View.GONE
        drawerScrim.visibility = View.GONE
    }

    private fun toggleDrawer() {
        if (drawerList.visibility == View.VISIBLE) closeDrawer() else openDrawer()
    }

    // ── Navigation ────────────────────────────────────────────────────────

    private fun switchView(view: ActiveView) {
        activeView = view
        prefs.edit().putString(PREF_ACTIVE_VIEW, view.name).apply()

        // Highlight the selected drawer entry
        val idx = navViews.indexOf(view)
        drawerList.setItemChecked(idx, true)

        // Update ActionBar title
        title = navEntries[idx]

        if (view == ActiveView.INBOX) {
            listView.adapter = inboxAdapter
            loadInboxFile()
        } else {
            listView.adapter = adapter
            refreshList()
        }
    }

    private fun onFabClicked() {
        if (activeView == ActiveView.INBOX) {
            startActivityForResult(
                Intent(this, InboxEditActivity::class.java),
                REQ_INBOX_ADD
            )
        } else {
            startActivityForResult(
                Intent(this, AddEditActivity::class.java).apply {
                    putStringArrayListExtra(AddEditActivity.EXTRA_ALL_CONTEXTS, ArrayList(todoList.allContexts))
                    putStringArrayListExtra(AddEditActivity.EXTRA_ALL_PROJECTS, ArrayList(todoList.allProjects))
                },
                REQ_ADD_TASK
            )
        }
    }

    // ── Core task operations ──────────────────────────────────────────────

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

    private fun toggleFreeze(item: TaskItem) {
        item.task.isFrozen = !item.task.isFrozen
        saveTodoFile()
        refreshList()
    }

    private fun toggleSomeday(item: TaskItem) {
        item.task.isSomeday = !item.task.isSomeday
        saveTodoFile()
        refreshList()
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

    // ── Inbox operations ──────────────────────────────────────────────────

    private fun openInboxEdit(index: Int) {
        val item = inboxItems[index]
        startActivityForResult(
            Intent(this, InboxEditActivity::class.java).apply {
                putExtra(InboxEditActivity.EXTRA_ITEM_INDEX, index)
                putExtra(InboxEditActivity.EXTRA_ITEM_TITLE, item.title)
                putExtra(InboxEditActivity.EXTRA_ITEM_DESC, item.description)
            },
            REQ_INBOX_EDIT
        )
    }

    private fun handleInboxResult(data: Intent) {
        val index  = data.getIntExtra(InboxEditActivity.EXTRA_ITEM_INDEX, -1)
        val delete = data.getBooleanExtra(InboxEditActivity.EXTRA_DELETE, false)

        if (delete && index >= 0) {
            inboxItems.removeAt(index)
        } else {
            val title = data.getStringExtra(InboxEditActivity.EXTRA_ITEM_TITLE) ?: return
            val desc  = data.getStringExtra(InboxEditActivity.EXTRA_ITEM_DESC) ?: ""
            val item  = InboxItem(title, desc)
            if (index >= 0) inboxItems[index] = item else inboxItems.add(item)
        }

        saveInboxFile()
        inboxAdapter.notifyDataSetChanged()
        updateEmptyView()
    }

    // ── File I/O ──────────────────────────────────────────────────────────

    private fun loadTodoFile() {
        if (isSaving.get()) { DebugLog.d(this, "loadTodoFile: skipped — write in flight"); return }
        if (!isLoading.compareAndSet(false, true)) { DebugLog.d(this, "loadTodoFile: already in flight"); return }
        val treeUri = prefs.getString(PREF_TREE_URI, null)?.let { Uri.parse(it) }
        if (treeUri == null) { DebugLog.d(this, "loadTodoFile: no tree URI"); isLoading.set(false); return }
        val cachedUriStr = prefs.getString(PREF_TODO_URI, null)
        Thread {
            try {
                if (isSaving.get()) { DebugLog.d(this, "loadTodoFile: skipped on thread"); return@Thread }
                val uri: Uri? = if (cachedUriStr != null) {
                    Uri.parse(cachedUriStr)
                } else {
                    val resolved = FileStorage.findFile(this, treeUri, "todo.txt")
                    if (resolved != null) prefs.edit().putString(PREF_TODO_URI, resolved.toString()).apply()
                    resolved
                }
                if (uri == null) {
                    runOnUiThread { Toast.makeText(this, R.string.load_error, Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val lines = FileStorage.readLines(this, uri)
                if (lines == null) {
                    if (cachedUriStr != null) prefs.edit().remove(PREF_TODO_URI).apply()
                    runOnUiThread { Toast.makeText(this, R.string.load_error, Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                todoList.loadFromLines(lines)
                persistTaskCache(lines)
                ReminderScheduler.schedule(this, prefs)
                lastLoadMs = System.currentTimeMillis()
                runOnUiThread { if (activeView != ActiveView.INBOX) refreshList() }
            } finally {
                isLoading.set(false)
            }
        }.start()
    }

    private fun saveTodoFile() {
        val lines = todoList.toLines()
        val treeUri = prefs.getString(PREF_TREE_URI, null)?.let { Uri.parse(it) } ?: return
        val cachedUriStr = prefs.getString(PREF_TODO_URI, null)
        isSaving.set(true)
        Thread {
            try {
                val uri: Uri? = if (cachedUriStr != null) {
                    Uri.parse(cachedUriStr)
                } else {
                    val resolved = FileStorage.findFile(this, treeUri, "todo.txt")
                    if (resolved != null) prefs.edit().putString(PREF_TODO_URI, resolved.toString()).apply()
                    resolved
                }
                if (uri == null) return@Thread
                val ok = FileStorage.writeLines(this, uri, lines)
                if (!ok) {
                    prefs.edit().remove(PREF_TODO_URI).apply()
                    runOnUiThread { Toast.makeText(this, R.string.save_error, Toast.LENGTH_SHORT).show() }
                } else {
                    ReminderScheduler.schedule(this, prefs)
                }
            } finally {
                isSaving.set(false)
            }
        }.start()
    }

    private fun loadInboxFile() {
        val treeUri = prefs.getString(PREF_TREE_URI, null)?.let { Uri.parse(it) } ?: return
        Thread {
            val lines = FileStorage.readInboxLines(this, treeUri)
            val items = InboxParser.parse(lines)
            runOnUiThread {
                inboxItems.clear()
                inboxItems.addAll(items)
                inboxAdapter.notifyDataSetChanged()
                updateEmptyView()
                if (lines.isEmpty() && items.isEmpty()) {
                    // Check if the file simply doesn't exist
                    val hasInbox = FileStorage.findFile(this, treeUri, "inbox.txt") != null
                    if (!hasInbox) {
                        Toast.makeText(this, R.string.inbox_no_file, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun saveInboxFile() {
        val treeUri = prefs.getString(PREF_TREE_URI, null)?.let { Uri.parse(it) } ?: return
        val lines = InboxParser.serialize(inboxItems)
        Thread {
            FileStorage.writeInboxLines(this, treeUri, lines)
        }.start()
    }

    // ── List refresh ──────────────────────────────────────────────────────

    private fun refreshList() {
        val today = LocalDate.now().format(DATE_FMT)
        val allTasks = todoList.getAll()

        val filtered = when (activeView) {
            ActiveView.NEXT -> allTasks.filter { task ->
                !task.completed && !task.isFrozen &&
                (task.dueDate == null || task.dueDate!! <= today)
            }
            ActiveView.FROZEN -> allTasks.filter { it.isFrozen }
            ActiveView.SCHEDULED -> allTasks.filter { it.dueDate != null }
            ActiveView.SOMEDAY -> allTasks.filter { it.isSomeday }
            ActiveView.INBOX -> return  // handled separately
        }

        // For non-default views just show tasks flat (no group headers) sorted by due date then text
        val items = filtered
            .sortedWith(compareBy({ it.dueDate ?: "9999-99-99" }, { it.text }))
            .map { io.github.todotxt.app.model.TaskItem(it) }

        adapter.setItems(items)
        updateEmptyView()
    }

    private fun updateEmptyView() {
        val isEmpty = when (activeView) {
            ActiveView.INBOX -> inboxItems.isEmpty()
            else -> adapter.count == 0
        }
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }

    // ── Task cache ────────────────────────────────────────────────────────

    private fun showCachedTasksIfAvailable() {
        val cached = prefs.getString(PREF_TASK_CACHE, null) ?: return
        val lines = cached.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        todoList.loadFromLines(lines)
        if (activeView != ActiveView.INBOX) refreshList()
    }

    private fun persistTaskCache(lines: List<String>) {
        prefs.edit().putString(PREF_TASK_CACHE, lines.joinToString("\n")).apply()
    }

    // ── Preferences ───────────────────────────────────────────────────────

    private fun loadPrefs() {
        val p = prefs
        sortField      = SortField.valueOf(p.getString(PREF_SORT_FIELD, SortField.PRIORITY.name)!!)
        showFuture     = p.getBoolean(PREF_SHOW_FUTURE, false)
        filterContexts = p.getStringSet(PREF_FILTER_CONTEXTS, emptySet())!!
        filterProjects = p.getStringSet(PREF_FILTER_PROJECTS, emptySet())!!
        filterText     = p.getString(PREF_FILTER_TEXT, "") ?: ""
        activeView     = try {
            ActiveView.valueOf(p.getString(PREF_ACTIVE_VIEW, ActiveView.NEXT.name)!!)
        } catch (_: IllegalArgumentException) { ActiveView.NEXT }

        // Apply initial drawer selection + title
        val idx = navViews.indexOf(activeView)
        title = navEntries[idx]
        // drawerList adapter isn't set yet in loadPrefs; selection applied in onCreate after adapter set
    }

    private fun savePrefs() {
        prefs.edit().apply {
            putString(PREF_SORT_FIELD,      sortField.name)
            putBoolean(PREF_SHOW_FUTURE,    showFuture)
            putStringSet(PREF_FILTER_CONTEXTS, filterContexts)
            putStringSet(PREF_FILTER_PROJECTS, filterProjects)
            putString(PREF_FILTER_TEXT,     filterText)
            apply()
        }
    }
}
