package io.github.todotxt.app.model

import junit.framework.TestCase

class TodoListTest : TestCase() {

    private fun list(vararg lines: String): TodoList {
        val tl = TodoList()
        tl.loadFromLines(lines.toList())
        return tl
    }

    // ── Basic CRUD ────────────────────────────────────────────────────────

    fun testLoadFromLines() {
        val tl = list("Buy milk", "(A) Call dentist @phone")
        assertEquals(2, tl.size())
    }

    fun testLoadFromLinesSkipsBlanks() {
        val tl = list("Buy milk", "", "  ", "Call dentist")
        assertEquals(2, tl.size())
    }

    fun testAdd() {
        val tl = TodoList()
        tl.add(Task("Buy milk"))
        assertEquals(1, tl.size())
    }

    fun testRemove() {
        val tl = list("Buy milk", "Call dentist")
        val task = tl.getAll()[0]
        tl.remove(task)
        assertEquals(1, tl.size())
    }

    fun testUpdate() {
        val tl = list("Buy milk")
        val old = tl.getAll()[0]
        val new = Task("Buy bread")
        tl.update(old, new)
        assertEquals("Buy bread", tl.getAll()[0].text)
    }

    fun testReplaceAll() {
        val tl = list("Buy milk", "Call dentist")
        tl.replaceAll(listOf(Task("New task")))
        assertEquals(1, tl.size())
        assertEquals("New task", tl.getAll()[0].text)
    }

    fun testToLines() {
        val lines = listOf("Buy milk", "(A) Call dentist @phone")
        val tl = TodoList()
        tl.loadFromLines(lines)
        assertEquals(lines, tl.toLines())
    }

    // ── Cache invalidation ────────────────────────────────────────────────

    fun testAllContexts() {
        val tl = list("Do thing @home", "Call @phone @home", "(A) Work @office")
        val ctxs = tl.allContexts
        assertEquals(listOf("home", "office", "phone"), ctxs)
    }

    fun testAllContextsCacheInvalidatedOnAdd() {
        val tl = list("Do thing @home")
        assertEquals(listOf("home"), tl.allContexts)
        tl.add(Task("Work @office"))
        assertEquals(listOf("home", "office"), tl.allContexts)
    }

    fun testAllProjects() {
        val tl = list("Do thing +projectA", "Write +projectB +projectA")
        assertEquals(listOf("projectA", "projectB"), tl.allProjects)
    }

    // ── Completion ────────────────────────────────────────────────────────

    fun testMarkComplete() {
        val tl = list("Buy milk")
        val task = tl.getAll()[0]
        val newTask = tl.markComplete(task, "2024-06-01")
        assertNull(newTask) // no rec
        assertTrue(task.completed)
        assertEquals(1, tl.size())
    }

    fun testMarkCompleteWithRecCreatesNewTask() {
        val tl = list("Pay rent due:2024-01-31 rec:1m")
        val task = tl.getAll()[0]
        val newTask = tl.markComplete(task, "2024-02-01")
        assertNotNull(newTask)
        assertEquals(2, tl.size())
        assertTrue(tl.getAll()[0].completed)
        assertFalse(tl.getAll()[1].completed)
        // New task inserted right after the completed one
        assertEquals(newTask, tl.getAll()[1])
    }

    fun testMarkIncomplete() {
        val tl = list("x 2024-06-01 Buy milk")
        val task = tl.getAll()[0]
        assertTrue(task.completed)
        tl.markIncomplete(task)
        assertFalse(task.completed)
    }

    // ── Archive ───────────────────────────────────────────────────────────

    fun testArchiveCompleted() {
        val tl = list("Buy milk", "x 2024-06-01 Call dentist", "x 2024-06-02 Pay bill")
        val archived = tl.archiveCompleted()
        assertEquals(2, archived.size)
        assertEquals(1, tl.size())
        assertEquals("Buy milk", tl.getAll()[0].text)
        assertTrue(archived.all { it.completed })
    }

    fun testArchiveCompletedNoneCompleted() {
        val tl = list("Buy milk", "Call dentist")
        val archived = tl.archiveCompleted()
        assertTrue(archived.isEmpty())
        assertEquals(2, tl.size())
    }

    // ── filteredAndGrouped ────────────────────────────────────────────────

    fun testFilterHidesCompleted() {
        val tl = list("Buy milk", "x 2024-06-01 Done task")
        val items = tl.filteredAndGrouped(
            showCompleted = false, showFuture = true,
            today = "2024-06-01", sortField = SortField.NONE
        )
        val tasks = items.filterIsInstance<TaskItem>()
        assertEquals(1, tasks.size)
        assertEquals("Buy milk", tasks[0].task.text)
    }

    fun testFilterShowsCompleted() {
        val tl = list("Buy milk", "x 2024-06-01 Done task")
        val items = tl.filteredAndGrouped(
            showCompleted = true, showFuture = true,
            today = "2024-06-01", sortField = SortField.NONE
        )
        assertEquals(2, items.filterIsInstance<TaskItem>().size)
    }

    fun testFilterHidesFuture() {
        val tl = list("Buy milk", "Future task t:2099-01-01")
        val items = tl.filteredAndGrouped(
            showCompleted = true, showFuture = false,
            today = "2024-06-01", sortField = SortField.NONE
        )
        assertEquals(1, items.filterIsInstance<TaskItem>().size)
    }

    fun testFilterByContext() {
        val tl = list("Call @phone", "Buy @store", "Email @phone @work")
        val items = tl.filteredAndGrouped(
            showCompleted = true, showFuture = true,
            today = "2024-06-01",
            filterContexts = setOf("phone"),
            sortField = SortField.NONE
        )
        val tasks = items.filterIsInstance<TaskItem>()
        assertEquals(2, tasks.size)
    }

    fun testFilterByProject() {
        val tl = list("Task +work", "Task +home", "Task +work +home")
        val items = tl.filteredAndGrouped(
            showCompleted = true, showFuture = true,
            today = "2024-06-01",
            filterProjects = setOf("work"),
            sortField = SortField.NONE
        )
        assertEquals(2, items.filterIsInstance<TaskItem>().size)
    }

    fun testFilterByText() {
        val tl = list("Buy milk", "Call dentist", "Buy bread")
        val items = tl.filteredAndGrouped(
            showCompleted = true, showFuture = true,
            today = "2024-06-01",
            filterText = "buy",
            sortField = SortField.NONE
        )
        assertEquals(2, items.filterIsInstance<TaskItem>().size)
    }

    // ── Sort / group ──────────────────────────────────────────────────────

    fun testSortByPriorityInsertsHeaders() {
        val tl = list("(B) Task B", "(A) Task A", "No priority task")
        val items = tl.filteredAndGrouped(
            showCompleted = true, showFuture = true,
            today = "2024-06-01", sortField = SortField.PRIORITY
        )
        val headers = items.filterIsInstance<HeaderItem>().map { it.title }
        assertTrue(headers.contains("(A)"))
        assertTrue(headers.contains("(B)"))
        assertTrue(headers.contains("No priority"))
        // (A) header should come before (B) header
        assertTrue(headers.indexOf("(A)") < headers.indexOf("(B)"))
    }

    fun testSortByProject() {
        val tl = list("Task +alpha", "Task +beta", "Task +alpha 2")
        val items = tl.filteredAndGrouped(
            showCompleted = true, showFuture = true,
            today = "2024-06-01", sortField = SortField.PROJECT
        )
        val headers = items.filterIsInstance<HeaderItem>().map { it.title }
        assertTrue(headers.contains("alpha"))
        assertTrue(headers.contains("beta"))
        assertTrue(headers.indexOf("alpha") < headers.indexOf("beta"))
    }

    fun testSortByContext() {
        val tl = list("Task @beta", "Task @alpha")
        val items = tl.filteredAndGrouped(
            showCompleted = true, showFuture = true,
            today = "2024-06-01", sortField = SortField.CONTEXT
        )
        val headers = items.filterIsInstance<HeaderItem>().map { it.title }
        assertTrue(headers.indexOf("alpha") < headers.indexOf("beta"))
    }

    fun testSortByDueDate() {
        val tl = list("Task due:2024-06-10", "Task due:2024-06-01", "Task no due")
        val items = tl.filteredAndGrouped(
            showCompleted = true, showFuture = true,
            today = "2024-01-01", sortField = SortField.DUE_DATE
        )
        val headers = items.filterIsInstance<HeaderItem>().map { it.title }
        // Earlier due date header comes first
        assertTrue(headers.indexOf("2024-06-01") < headers.indexOf("2024-06-10"))
    }

    fun testSortNonePreservesOrder() {
        val lines = listOf("Task C", "Task A", "Task B")
        val tl = list(*lines.toTypedArray())
        val items = tl.filteredAndGrouped(
            showCompleted = true, showFuture = true,
            today = "2024-06-01", sortField = SortField.NONE
        )
        val taskTexts = items.filterIsInstance<TaskItem>().map { it.task.text }
        assertEquals(lines, taskTexts)
    }
}
