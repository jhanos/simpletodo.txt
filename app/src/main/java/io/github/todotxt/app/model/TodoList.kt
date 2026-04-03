package io.github.todotxt.app.model

/**
 * A single item in the visible list: either a section header or a task row.
 */
sealed class VisibleItem
data class HeaderItem(val title: String) : VisibleItem()
data class TaskItem(val task: Task) : VisibleItem()

enum class SortField {
    PRIORITY, PROJECT, CONTEXT, DUE_DATE, THRESHOLD_DATE, NONE
}

/**
 * In-memory representation of the todo list.
 *
 * Thread-safety: not internally synchronised — callers on Android must
 * access this from one thread (the main thread) and dispatch file I/O
 * via a background thread / coroutine.
 */
class TodoList {

    private val tasks: MutableList<Task> = mutableListOf()

    // ── Derived caches (invalidated on any mutation) ──────────────────────

    private var _contexts: List<String>? = null
    private var _projects: List<String>? = null

    val allContexts: List<String>
        get() {
            return _contexts ?: tasks
                .flatMap { it.contexts }
                .distinct()
                .sorted()
                .also { _contexts = it }
        }

    val allProjects: List<String>
        get() {
            return _projects ?: tasks
                .flatMap { it.projects }
                .distinct()
                .sorted()
                .also { _projects = it }
        }

    // ── Basic list operations ─────────────────────────────────────────────

    fun size(): Int = tasks.size

    fun getAll(): List<Task> = tasks.toList()

    fun add(task: Task) {
        tasks.add(task)
        invalidateCache()
    }

    fun update(old: Task, new: Task) {
        val idx = tasks.indexOf(old)
        if (idx >= 0) {
            tasks[idx] = new
            invalidateCache()
        }
    }

    fun remove(task: Task) {
        tasks.remove(task)
        invalidateCache()
    }

    fun replaceAll(newTasks: List<Task>) {
        tasks.clear()
        tasks.addAll(newTasks)
        invalidateCache()
    }

    // ── Completion ────────────────────────────────────────────────────────

    /**
     * Mark [task] complete. If the task has a rec: pattern a new task with
     * deferred dates is inserted right after it. Returns the new task if one
     * was created, null otherwise.
     */
    fun markComplete(task: Task, dateStr: String): Task? {
        val newTask = task.markComplete(dateStr)
        if (newTask != null) {
            val idx = tasks.indexOf(task)
            if (idx >= 0) tasks.add(idx + 1, newTask)
        }
        invalidateCache()
        return newTask
    }

    fun markIncomplete(task: Task) {
        task.markIncomplete()
        invalidateCache()
    }

    // ── Archive ───────────────────────────────────────────────────────────

    /**
     * Returns the tasks that would be moved to done.txt (completed ones)
     * and removes them from this list. Caller is responsible for appending
     * the returned lines to done.txt.
     */
    fun archiveCompleted(): List<Task> {
        val completed = tasks.filter { it.completed }
        tasks.removeAll(completed.toSet())
        invalidateCache()
        return completed
    }

    // ── Serialisation ─────────────────────────────────────────────────────

    /** Parse lines from todo.txt into this list, replacing any existing content. */
    fun loadFromLines(lines: List<String>) {
        tasks.clear()
        lines.forEach { line ->
            if (line.isNotBlank()) tasks.add(Task(line))
        }
        invalidateCache()
    }

    /** Serialise the list back to todo.txt lines (one task per line). */
    fun toLines(): List<String> = tasks.map { it.text }

    // ── Filtering + sorting ───────────────────────────────────────────────

    /**
     * Return the visible list after applying all filters and sorting.
     *
     * @param showCompleted  include completed tasks
     * @param showFuture     include tasks whose threshold date is in the future
     * @param today          today as "YYYY-MM-DD" for threshold comparison
     * @param filterContexts only show tasks matching ALL of these contexts (empty = no filter)
     * @param filterProjects only show tasks matching ALL of these projects (empty = no filter)
     * @param filterText     free-text search (case-insensitive substring on raw text)
     * @param sortField      primary sort / grouping axis
     */
    fun filteredAndGrouped(
        showCompleted: Boolean,
        showFuture: Boolean,
        today: String,
        filterContexts: Set<String> = emptySet(),
        filterProjects: Set<String> = emptySet(),
        filterText: String = "",
        sortField: SortField = SortField.PRIORITY
    ): List<VisibleItem> {

        val filtered = tasks.filter { task ->
            if (!showCompleted && task.completed) return@filter false
            if (!showFuture && task.isInFuture(today)) return@filter false
            if (filterContexts.isNotEmpty() && !task.contexts.containsAll(filterContexts)) return@filter false
            if (filterProjects.isNotEmpty() && !task.projects.containsAll(filterProjects)) return@filter false
            if (filterText.isNotBlank() && !task.text.contains(filterText, ignoreCase = true)) return@filter false
            true
        }

        val sorted = when (sortField) {
            SortField.PRIORITY       -> filtered.sortedWith(compareBy({ it.priority.ordinal }, { it.text }))
            SortField.PROJECT        -> filtered.sortedWith(compareBy({ it.projects.firstOrNull() ?: "~" }, { it.text }))
            SortField.CONTEXT        -> filtered.sortedWith(compareBy({ it.contexts.firstOrNull() ?: "~" }, { it.text }))
            SortField.DUE_DATE       -> filtered.sortedWith(compareBy({ it.dueDate ?: "9999-99-99" }, { it.text }))
            SortField.THRESHOLD_DATE -> filtered.sortedWith(compareBy({ it.thresholdDate ?: "9999-99-99" }, { it.text }))
            SortField.NONE           -> filtered
        }

        return when (sortField) {
            SortField.PRIORITY -> groupBy(sorted) { task ->
                if (task.priority == Priority.NONE) "No priority" else "(${task.priority.code})"
            }
            SortField.PROJECT -> groupBy(sorted) { task ->
                task.projects.firstOrNull() ?: "No project"
            }
            SortField.CONTEXT -> groupBy(sorted) { task ->
                task.contexts.firstOrNull() ?: "No context"
            }
            SortField.DUE_DATE -> groupBy(sorted) { task ->
                task.dueDate ?: "No due date"
            }
            SortField.THRESHOLD_DATE -> groupBy(sorted) { task ->
                task.thresholdDate ?: "No threshold"
            }
            SortField.NONE -> sorted.map { TaskItem(it) }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun invalidateCache() {
        _contexts = null
        _projects = null
    }

    private fun groupBy(tasks: List<Task>, keyOf: (Task) -> String): List<VisibleItem> {
        val result = mutableListOf<VisibleItem>()
        var lastKey: String? = null
        for (task in tasks) {
            val key = keyOf(task)
            if (key != lastKey) {
                result += HeaderItem(key)
                lastKey = key
            }
            result += TaskItem(task)
        }
        return result
    }
}
