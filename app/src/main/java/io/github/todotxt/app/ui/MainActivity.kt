package io.github.todotxt.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import io.github.todotxt.app.R
import io.github.todotxt.app.model.HeaderItem
import io.github.todotxt.app.model.InboxItem
import io.github.todotxt.app.model.InboxParser
import io.github.todotxt.app.model.SortField
import io.github.todotxt.app.model.Task
import io.github.todotxt.app.model.TaskItem
import io.github.todotxt.app.model.TodoList
import io.github.todotxt.app.model.VisibleItem
import io.github.todotxt.app.storage.DebugLog
import io.github.todotxt.app.storage.FileStorage
import io.github.todotxt.app.storage.Prefs
import io.github.todotxt.app.storage.ReminderScheduler
import java.time.LocalDate

enum class ActiveView { INBOX, NEXT, FROZEN, SCHEDULED, SOMEDAY, PROJECT }

// ── Drawer item model ────────────────────────────────────────────────────────

sealed class DrawerItem
data class DrawerNavItem(val label: String, val view: ActiveView) : DrawerItem()
data class DrawerSectionHeader(val label: String) : DrawerItem()
data class DrawerProjectItem(val project: String) : DrawerItem()

// ── DrawerAdapter ────────────────────────────────────────────────────────────

class DrawerAdapter(private val context: Context) : BaseAdapter() {

    private var items: List<DrawerItem> = emptyList()

    fun setItems(newItems: List<DrawerItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): DrawerItem = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int =
        if (items[position] is DrawerSectionHeader) 1 else 0

    override fun isEnabled(position: Int): Boolean =
        items[position] !is DrawerSectionHeader

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return when (val item = items[position]) {
            is DrawerSectionHeader -> {
                val v = convertView
                    ?: (context as Activity).layoutInflater.inflate(R.layout.item_header, parent, false)
                (v as TextView).text = item.label
                v
            }
            is DrawerNavItem -> {
                val v = convertView
                    ?: (context as Activity).layoutInflater.inflate(
                        android.R.layout.simple_list_item_activated_1, parent, false)
                (v as TextView).text = item.label
                v
            }
            is DrawerProjectItem -> {
                val v = convertView
                    ?: (context as Activity).layoutInflater.inflate(
                        android.R.layout.simple_list_item_activated_1, parent, false)
                (v as TextView).text = item.project
                v
            }
        }
    }
}

// ── MainActivity ─────────────────────────────────────────────────────────────

class MainActivity : Activity() {

    companion object {
        private const val REQ_ADD_TASK     = 1
        private const val REQ_EDIT_TASK    = 2
        private const val REQ_FILTER       = 3
        private const val REQ_SETTINGS     = 4
        private const val REQ_INBOX_ADD    = 5
        private const val REQ_INBOX_EDIT   = 6
    }

    private val todoList = TodoList()
    private lateinit var adapter: TaskAdapter
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var drawerList: ListView
    private lateinit var drawerScrim: View
    private lateinit var fab: Button
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, MODE_PRIVATE) }

    // Both flags are only meaningful on the main thread; background threads only
    // call set(false) in finally blocks, which is safe without atomic wrappers.
    private var isSaving  = false
    private var isLoading = false
    private var isDirty   = false

    // Current filter/sort state (used for non-inbox views)
    private var sortField      = SortField.PRIORITY
    private var showFuture     = false
    private var filterContexts = emptySet<String>()
    private var filterProjects = emptySet<String>()
    private var filterText     = ""

    // Active navigation view
    private var activeView    = ActiveView.NEXT
    private var activeProject = ""

    // Inbox data
    private var inboxItems = mutableListOf<InboxItem>()
    private lateinit var inboxAdapter: InboxAdapter

    // Drawer
    private lateinit var drawerAdapter: DrawerAdapter

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
        drawerAdapter = DrawerAdapter(this)
        drawerList.adapter = drawerAdapter
        drawerList.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            when (val item = drawerAdapter.getItem(position)) {
                is DrawerNavItem     -> { switchView(item.view); closeDrawer() }
                is DrawerProjectItem -> switchToProject(item.project)
                is DrawerSectionHeader -> { /* non-clickable — never fires */ }
            }
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
        rebuildDrawer()
        showCachedTasksIfAvailable()
        startupSync()
        // Inbox is loaded on demand when switching to that view
    }

    // ── Options menu ──────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home    -> { toggleDrawer(); true }
            R.id.action_sync     -> { syncNow(); true }
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
                markDirty()
                refreshList()
            }
            REQ_EDIT_TASK -> if (resultCode == RESULT_OK) {
                if (data?.getBooleanExtra(AddEditActivity.EXTRA_DELETE, false) == true) {
                    val oldText = data.getStringExtra(AddEditActivity.EXTRA_OLD_TASK_TEXT) ?: return
                    val oldTask = todoList.getAll().firstOrNull { it.text == oldText } ?: return
                    todoList.remove(oldTask)
                } else {
                    val raw     = data?.getStringExtra(AddEditActivity.EXTRA_TASK_TEXT) ?: return
                    val oldText = data.getStringExtra(AddEditActivity.EXTRA_OLD_TASK_TEXT) ?: return
                    val oldTask = todoList.getAll().firstOrNull { it.text == oldText } ?: return
                    todoList.update(oldTask, Task(raw))
                }
                markDirty()
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
                prefs.edit().remove(Prefs.TODO_URI).remove(Prefs.DONE_URI).apply()
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

    private fun rebuildDrawer() {
        val items = mutableListOf<DrawerItem>(
            DrawerSectionHeader(getString(R.string.nav_capture_header)),
            DrawerNavItem(getString(R.string.nav_inbox),     ActiveView.INBOX),
            DrawerSectionHeader(getString(R.string.nav_actions_header)),
            DrawerNavItem(getString(R.string.nav_next),      ActiveView.NEXT),
            DrawerNavItem(getString(R.string.nav_scheduled), ActiveView.SCHEDULED),
            DrawerNavItem(getString(R.string.nav_frozen),    ActiveView.FROZEN),
            DrawerNavItem(getString(R.string.nav_someday),   ActiveView.SOMEDAY)
        )
        val projects = todoList.allProjects
        if (projects.isNotEmpty()) {
            items += DrawerSectionHeader(getString(R.string.nav_projects_header))
            projects.forEach { items += DrawerProjectItem(it) }
        }
        drawerAdapter.setItems(items)

        // Guard: if active project no longer exists, fall back to NEXT
        if (activeView == ActiveView.PROJECT && activeProject !in projects) {
            activeView = ActiveView.NEXT
            activeProject = ""
            prefs.edit()
                .putString(Prefs.ACTIVE_VIEW, ActiveView.NEXT.name)
                .remove(Prefs.ACTIVE_PROJECT)
                .apply()
            title = getString(R.string.nav_next)
        }

        highlightDrawer()
    }

    private fun highlightDrawer() {
        val items = (0 until drawerAdapter.count).map { drawerAdapter.getItem(it) }
        // Clear all first
        for (i in items.indices) drawerList.setItemChecked(i, false)

        val pos = when (activeView) {
            ActiveView.PROJECT -> items.indexOfFirst {
                it is DrawerProjectItem && it.project == activeProject
            }
            else -> items.indexOfFirst {
                it is DrawerNavItem && it.view == activeView
            }
        }
        if (pos >= 0) drawerList.setItemChecked(pos, true)
    }

    // ── Navigation ────────────────────────────────────────────────────────

    private fun switchView(view: ActiveView) {
        activeView = view
        prefs.edit().putString(Prefs.ACTIVE_VIEW, view.name).apply()

        // Update ActionBar title
        title = when (view) {
            ActiveView.INBOX     -> getString(R.string.nav_inbox)
            ActiveView.NEXT      -> getString(R.string.nav_next)
            ActiveView.FROZEN    -> getString(R.string.nav_frozen)
            ActiveView.SCHEDULED -> getString(R.string.nav_scheduled)
            ActiveView.SOMEDAY   -> getString(R.string.nav_someday)
            ActiveView.PROJECT   -> activeProject
        }

        highlightDrawer()

        if (view == ActiveView.INBOX) {
            listView.adapter = inboxAdapter
            loadInboxFile()
        } else {
            listView.adapter = adapter
            refreshList()
        }
    }

    private fun switchToProject(project: String) {
        activeView    = ActiveView.PROJECT
        activeProject = project
        prefs.edit()
            .putString(Prefs.ACTIVE_VIEW,    ActiveView.PROJECT.name)
            .putString(Prefs.ACTIVE_PROJECT, project)
            .apply()
        title = project
        listView.adapter = adapter
        refreshList()
        highlightDrawer()
        closeDrawer()
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
        val today = Prefs.todayString()
        if (item.task.completed) {
            todoList.markIncomplete(item.task)
        } else {
            todoList.markComplete(item.task, today)
        }
        markDirty()
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
                markDirty()
                refreshList()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun toggleFreeze(item: TaskItem) {
        item.task.isFrozen = !item.task.isFrozen
        markDirty()
        refreshList()
    }

    private fun toggleSomeday(item: TaskItem) {
        item.task.isSomeday = !item.task.isSomeday
        markDirty()
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

    // ── Sync ──────────────────────────────────────────────────────────────

    private fun startupSync() {
        val lastSync = prefs.getString(Prefs.LAST_SYNC_DATE, null)
        if (lastSync != Prefs.todayString() || isDirty) {
            // First open of the day, or unsaved changes — flush to SAF then pull
            saveTodoFile(postSave = { loadTodoFile() })
        } else {
            loadTodoFile()
        }
    }

    private fun syncNow() {
        Toast.makeText(this, R.string.syncing, Toast.LENGTH_SHORT).show()
        saveTodoFile(postSave = { loadTodoFile() })
    }

    // ── File I/O ──────────────────────────────────────────────────────────

    private fun loadTodoFile() {
        if (isSaving) { DebugLog.d(this, "loadTodoFile: skipped — write in flight"); return }
        if (isLoading) { DebugLog.d(this, "loadTodoFile: already in flight"); return }
        isLoading = true
        if (prefs.getString(Prefs.TREE_URI, null) == null) {
            DebugLog.d(this, "loadTodoFile: no tree URI")
            isLoading = false
            return
        }
        Thread {
            try {
                if (isSaving) { DebugLog.d(this, "loadTodoFile: skipped on thread"); return@Thread }
                val uri = FileStorage.resolveTodoUri(this, prefs)
                if (uri == null) {
                    runOnUiThread { Toast.makeText(this, R.string.load_error, Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val lines = FileStorage.readLines(this, uri)
                if (lines == null) {
                    prefs.edit().remove(Prefs.TODO_URI).apply()
                    runOnUiThread { Toast.makeText(this, R.string.load_error, Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                todoList.loadFromLines(lines)
                persistTaskCache(lines)
                ReminderScheduler.schedule(this, prefs)
                runOnUiThread {
                    if (activeView != ActiveView.INBOX) refreshList()
                    rebuildDrawer()
                }
            } finally {
                isLoading = false
            }
        }.start()
    }

    private fun saveTodoFile(postSave: (() -> Unit)? = null) {
        if (prefs.getString(Prefs.TREE_URI, null) == null) return
        val lines = todoList.toLines()
        isSaving = true
        Thread {
            try {
                val uri = FileStorage.resolveTodoUri(this, prefs)
                if (uri == null) {
                    runOnUiThread { Toast.makeText(this, R.string.save_error, Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                val ok = FileStorage.writeLines(this, uri, lines)
                if (!ok) {
                    prefs.edit().remove(Prefs.TODO_URI).apply()
                    runOnUiThread { Toast.makeText(this, R.string.save_error, Toast.LENGTH_SHORT).show() }
                } else {
                    isDirty = false
                    prefs.edit().putString(Prefs.LAST_SYNC_DATE, Prefs.todayString()).apply()
                    ReminderScheduler.schedule(this, prefs)
                    postSave?.invoke()
                }
            } finally {
                isSaving = false
            }
        }.start()
    }

    private fun loadInboxFile() {
        val treeUri = prefs.getString(Prefs.TREE_URI, null)?.let { Uri.parse(it) } ?: return
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
        val treeUri = prefs.getString(Prefs.TREE_URI, null)?.let { Uri.parse(it) } ?: return
        val lines = InboxParser.serialize(inboxItems)
        Thread {
            FileStorage.writeInboxLines(this, treeUri, lines)
        }.start()
    }

    // ── List refresh ──────────────────────────────────────────────────────

    private fun refreshList() {
        val today = Prefs.todayString()

        val items: List<VisibleItem> = when (activeView) {
            ActiveView.NEXT -> {
                val sevenDaysFromNow = LocalDate.now().plusDays(6).toString()
                val raw = todoList.filteredAndGrouped(
                    showFuture     = showFuture,
                    today          = today,
                    filterContexts = filterContexts,
                    filterProjects = filterProjects,
                    filterText     = filterText,
                    sortField      = sortField
                ).filter { item ->
                    when (item) {
                        is HeaderItem -> true  // prune orphans in the next pass
                        is TaskItem   -> {
                            val t = item.task
                            !t.completed && !t.isFrozen && !t.isSomeday &&
                                (t.dueDate == null || t.dueDate!! <= sevenDaysFromNow)
                        }
                    }
                }
                // Drop section headers that have no task row immediately after them
                raw.filterIndexed { i, item ->
                    item is TaskItem || (item is HeaderItem && raw.getOrNull(i + 1) is TaskItem)
                }
            }

            ActiveView.FROZEN -> todoList.getAll()
                .filter { it.isFrozen }
                .sortedWith(compareBy({ it.dueDate ?: "9999-99-99" }, { it.text }))
                .map { TaskItem(it) }

            ActiveView.SCHEDULED -> todoList.getAll()
                .filter { it.dueDate != null }
                .sortedWith(compareBy({ it.dueDate!! }, { it.text }))
                .map { TaskItem(it) }

            ActiveView.SOMEDAY -> todoList.getAll()
                .filter { it.isSomeday }
                .sortedWith(compareBy({ it.dueDate ?: "9999-99-99" }, { it.text }))
                .map { TaskItem(it) }

            ActiveView.PROJECT -> todoList.getAll()
                .filter { activeProject in it.projects }
                .sortedWith(compareBy({ it.dueDate ?: "9999-99-99" }, { it.text }))
                .map { TaskItem(it) }

            ActiveView.INBOX -> return  // handled separately
        }

        adapter.setItems(items)
        rebuildDrawer()
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
        val cached = prefs.getString(Prefs.TASK_CACHE, null) ?: return
        val lines = cached.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        todoList.loadFromLines(lines)
        if (activeView != ActiveView.INBOX) refreshList()
    }

    private fun markDirty() {
        isDirty = true
        persistTaskCache(todoList.toLines())
    }

    private fun persistTaskCache(lines: List<String>) {
        prefs.edit().putString(Prefs.TASK_CACHE, lines.joinToString("\n")).apply()
    }

    override fun onStop() {
        super.onStop()
        // markDirty() already persists the cache on every mutation, so nothing to do here.
    }

    // ── Preferences ───────────────────────────────────────────────────────

    private fun loadPrefs() {
        val p = prefs
        sortField      = SortField.valueOf(p.getString(Prefs.SORT_FIELD, SortField.PRIORITY.name)!!)
        showFuture     = p.getBoolean(Prefs.SHOW_FUTURE, false)
        filterContexts = p.getStringSet(Prefs.FILTER_CONTEXTS, emptySet())!!
        filterProjects = p.getStringSet(Prefs.FILTER_PROJECTS, emptySet())!!
        filterText     = p.getString(Prefs.FILTER_TEXT, "") ?: ""
        activeView     = try {
            ActiveView.valueOf(p.getString(Prefs.ACTIVE_VIEW, ActiveView.NEXT.name)!!)
        } catch (_: IllegalArgumentException) { ActiveView.NEXT }

        when (activeView) {
            ActiveView.PROJECT -> {
                activeProject = p.getString(Prefs.ACTIVE_PROJECT, "") ?: ""
                if (activeProject.isEmpty()) {
                    activeView = ActiveView.NEXT
                    title = getString(R.string.nav_next)
                } else {
                    title = activeProject
                }
            }
            ActiveView.INBOX     -> title = getString(R.string.nav_inbox)
            ActiveView.NEXT      -> title = getString(R.string.nav_next)
            ActiveView.FROZEN    -> title = getString(R.string.nav_frozen)
            ActiveView.SCHEDULED -> title = getString(R.string.nav_scheduled)
            ActiveView.SOMEDAY   -> title = getString(R.string.nav_someday)
        }
        // drawerList adapter isn't set yet in loadPrefs; rebuildDrawer() called after in onCreate
    }

    private fun savePrefs() {
        prefs.edit().apply {
            putString(Prefs.SORT_FIELD,       sortField.name)
            putBoolean(Prefs.SHOW_FUTURE,     showFuture)
            putStringSet(Prefs.FILTER_CONTEXTS, filterContexts)
            putStringSet(Prefs.FILTER_PROJECTS, filterProjects)
            putString(Prefs.FILTER_TEXT,      filterText)
            apply()
        }
    }
}
