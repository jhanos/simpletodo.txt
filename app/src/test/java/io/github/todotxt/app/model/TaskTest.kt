package io.github.todotxt.app.model

import junit.framework.TestCase

class TaskTest : TestCase() {

    // ── Basic parsing ─────────────────────────────────────────────────────

    fun testParseSimpleText() {
        val task = Task("Buy milk")
        assertEquals("Buy milk", task.text)
        assertFalse(task.completed)
        assertEquals(Priority.NONE, task.priority)
        assertNull(task.createDate)
        assertNull(task.dueDate)
        assertNull(task.thresholdDate)
        assertNull(task.recurrencePattern)
        assertTrue(task.contexts.isEmpty())
        assertTrue(task.projects.isEmpty())
    }

    fun testParsePriority() {
        val task = Task("(A) Buy milk")
        assertEquals(Priority.A, task.priority)
        assertFalse(task.completed)
    }

    fun testParsePriorityZ() {
        val task = Task("(Z) Low priority task")
        assertEquals(Priority.Z, task.priority)
    }

    fun testParseCompleted() {
        val task = Task("x 2024-01-15 Do laundry")
        assertTrue(task.completed)
        assertEquals("2024-01-15", task.completionDate)
        assertTrue(task.text.contains("Do laundry"))
    }

    fun testParseCompletedWithCreationDate() {
        val task = Task("x 2024-01-15 2024-01-10 Do laundry")
        assertTrue(task.completed)
        assertEquals("2024-01-15", task.completionDate)
        assertEquals("2024-01-10", task.createDate)
    }

    fun testParseCreationDate() {
        val task = Task("2024-01-10 Buy milk")
        assertEquals("2024-01-10", task.createDate)
        assertFalse(task.completed)
    }

    fun testParsePriorityAndCreationDate() {
        val task = Task("(B) 2024-01-10 Buy milk")
        assertEquals(Priority.B, task.priority)
        assertEquals("2024-01-10", task.createDate)
    }

    fun testParseContexts() {
        val task = Task("Call dentist @phone @health")
        assertEquals(listOf("phone", "health"), task.contexts)
    }

    fun testParseProjects() {
        val task = Task("Write report +work +quarterly")
        assertEquals(listOf("work", "quarterly"), task.projects)
    }

    fun testParseDueDate() {
        val task = Task("Submit report due:2024-12-31")
        assertEquals("2024-12-31", task.dueDate)
    }

    fun testParseThresholdDate() {
        val task = Task("Prepare report t:2024-12-01")
        assertEquals("2024-12-01", task.thresholdDate)
    }

    fun testParseRecurrence() {
        val task = Task("Pay rent rec:1m")
        assertEquals("1m", task.recurrencePattern)
    }

    fun testParseRecurrenceStrict() {
        val task = Task("Take vitamins rec:+1d")
        assertEquals("+1d", task.recurrencePattern)
    }

    fun testParseFullTask() {
        val task = Task("(A) 2024-01-01 Call dentist @phone +health due:2024-01-10 t:2024-01-05 rec:1w")
        assertEquals(Priority.A, task.priority)
        assertEquals("2024-01-01", task.createDate)
        assertEquals(listOf("phone"), task.contexts)
        assertEquals(listOf("health"), task.projects)
        assertEquals("2024-01-10", task.dueDate)
        assertEquals("2024-01-05", task.thresholdDate)
        assertEquals("1w", task.recurrencePattern)
    }

    fun testParseExtToken() {
        val task = Task("Some task foo:bar")
        // Should not throw; ext token parsed
        assertTrue(task.text.contains("foo:bar"))
    }

    // ── Roundtrip ─────────────────────────────────────────────────────────

    fun testRoundtripSimple() {
        val line = "Buy milk"
        assertEquals(line, Task(line).text)
    }

    fun testRoundtripPriority() {
        val line = "(A) Buy milk"
        assertEquals(line, Task(line).text)
    }

    fun testRoundtripCompleted() {
        val line = "x 2024-01-15 Buy milk"
        assertEquals(line, Task(line).text)
    }

    fun testRoundtripFull() {
        val line = "(B) 2024-01-01 Do thing @ctx +proj due:2024-06-01 t:2024-05-01 rec:1m"
        assertEquals(line, Task(line).text)
    }

    // ── Completion ────────────────────────────────────────────────────────

    fun testMarkComplete() {
        val task = Task("Buy milk")
        val newTask = task.markComplete("2024-06-01")
        assertNull(newTask) // no rec pattern
        assertTrue(task.completed)
        assertEquals("2024-06-01", task.completionDate)
    }

    fun testMarkCompleteIdempotent() {
        val task = Task("Buy milk")
        task.markComplete("2024-06-01")
        val result = task.markComplete("2024-06-02")
        assertNull(result) // already completed, returns null
        assertEquals("2024-06-01", task.completionDate) // unchanged
    }

    fun testMarkIncomplete() {
        val task = Task("x 2024-06-01 Buy milk")
        assertTrue(task.completed)
        task.markIncomplete()
        assertFalse(task.completed)
        assertNull(task.completionDate)
    }

    fun testMarkCompleteWithRecurrence() {
        val task = Task("Pay rent due:2024-01-31 rec:1m")
        val newTask = task.markComplete("2024-02-01")
        assertNotNull(newTask)
        // base date is due date (non-strict), so new due = 2024-01-31 + 1m = 2024-02-29 (2024 is a leap year)
        assertEquals("2024-02-29", newTask!!.dueDate)
        assertFalse(newTask.completed)
    }

    fun testMarkCompleteWithStrictRecurrence() {
        val task = Task("Pay rent due:2024-01-31 rec:+1m")
        val newTask = task.markComplete("2024-02-05")
        assertNotNull(newTask)
        // strict: base = completion date 2024-02-05, new due = 2024-03-05
        assertEquals("2024-03-05", newTask!!.dueDate)
    }

    // ── Priority mutation ─────────────────────────────────────────────────

    fun testSetPriority() {
        val task = Task("Buy milk")
        task.priority = Priority.A
        assertEquals(Priority.A, task.priority)
        assertTrue(task.text.startsWith("(A)"))
    }

    fun testClearPriority() {
        val task = Task("(A) Buy milk")
        task.priority = Priority.NONE
        assertEquals(Priority.NONE, task.priority)
        assertFalse(task.text.contains("(A)"))
    }

    fun testChangePriority() {
        val task = Task("(A) Buy milk")
        task.priority = Priority.C
        assertEquals(Priority.C, task.priority)
        assertTrue(task.text.startsWith("(C)"))
    }

    // ── Date mutations ────────────────────────────────────────────────────

    fun testSetDueDate() {
        val task = Task("Buy milk")
        task.dueDate = "2024-12-31"
        assertEquals("2024-12-31", task.dueDate)
        assertTrue(task.text.contains("due:2024-12-31"))
    }

    fun testClearDueDate() {
        val task = Task("Buy milk due:2024-12-31")
        task.dueDate = null
        assertNull(task.dueDate)
        assertFalse(task.text.contains("due:"))
    }

    fun testSetThresholdDate() {
        val task = Task("Buy milk")
        task.thresholdDate = "2024-11-01"
        assertEquals("2024-11-01", task.thresholdDate)
        assertTrue(task.text.contains("t:2024-11-01"))
    }

    fun testSetCreateDate() {
        val task = Task("Buy milk")
        task.createDate = "2024-01-01"
        assertEquals("2024-01-01", task.createDate)
    }

    fun testCreateDatePositionAfterPriority() {
        val task = Task("(A) Buy milk")
        task.createDate = "2024-01-01"
        assertTrue(task.text.startsWith("(A) 2024-01-01"))
    }

    // ── isInFuture ────────────────────────────────────────────────────────

    fun testIsInFutureWithFutureThreshold() {
        val task = Task("Some task t:2099-01-01")
        assertTrue(task.isInFuture("2024-06-01"))
    }

    fun testIsInFutureWithPastThreshold() {
        val task = Task("Some task t:2020-01-01")
        assertFalse(task.isInFuture("2024-06-01"))
    }

    fun testIsInFutureWithNoThreshold() {
        val task = Task("Some task")
        assertFalse(task.isInFuture("2024-06-01"))
    }

    // ── addInterval ───────────────────────────────────────────────────────

    fun testAddIntervalDays() {
        assertEquals("2024-01-08", Task.addInterval("2024-01-01", "7d"))
    }

    fun testAddIntervalWeeks() {
        assertEquals("2024-01-08", Task.addInterval("2024-01-01", "1w"))
    }

    fun testAddIntervalMonths() {
        assertEquals("2024-02-15", Task.addInterval("2024-01-15", "1m"))
    }

    fun testAddIntervalYears() {
        assertEquals("2025-06-01", Task.addInterval("2024-06-01", "1y"))
    }

    fun testAddIntervalMonthsYearRollover() {
        assertEquals("2025-01-15", Task.addInterval("2024-12-15", "1m"))
    }

    fun testAddIntervalDaysMonthBoundary() {
        assertEquals("2024-02-01", Task.addInterval("2024-01-31", "1d"))
    }

    fun testAddIntervalDaysLeapYear() {
        assertEquals("2024-03-01", Task.addInterval("2024-02-29", "1d"))
    }

    fun testAddIntervalStripsPlusPrefix() {
        assertEquals("2024-01-08", Task.addInterval("2024-01-01", "+7d"))
    }

    fun testAddIntervalInvalidUnit() {
        assertNull(Task.addInterval("2024-01-01", "1x"))
    }

    // ── status:frozen ─────────────────────────────────────────────────────

    fun testParseFrozen() {
        val task = Task("Buy milk status:frozen")
        assertTrue(task.isFrozen)
    }

    fun testParseFrozenCaseInsensitive() {
        val task = Task("Buy milk Status:Frozen")
        assertTrue(task.isFrozen)
    }

    fun testNotFrozenByDefault() {
        val task = Task("Buy milk")
        assertFalse(task.isFrozen)
    }

    fun testFrozenRoundTrip() {
        val task = Task("Buy milk status:frozen")
        assertEquals("Buy milk status:frozen", task.text)
    }

    fun testFrozenExcludedFromDisplayText() {
        val task = Task("Buy milk status:frozen")
        assertFalse(task.displayText.contains("status:frozen"))
        assertEquals("Buy milk", task.displayText)
    }

    fun testSetFrozen() {
        val task = Task("Buy milk")
        task.isFrozen = true
        assertTrue(task.isFrozen)
        assertTrue(task.text.contains("status:frozen"))
    }

    fun testUnfreeze() {
        val task = Task("Buy milk status:frozen")
        task.isFrozen = false
        assertFalse(task.isFrozen)
        assertFalse(task.text.contains("status:frozen"))
    }

    fun testFrozenWithOtherTokens() {
        val task = Task("(A) Buy milk due:2024-06-01 @shop status:frozen")
        assertTrue(task.isFrozen)
        assertEquals(Priority.A, task.priority)
        assertEquals("2024-06-01", task.dueDate)
        assertEquals(listOf("shop"), task.contexts)
    }

    // ── status:someday ────────────────────────────────────────────────────

    fun testParseSomeday() {
        val task = Task("Buy milk status:someday")
        assertTrue(task.isSomeday)
    }

    fun testParseSomedayCaseInsensitive() {
        val task = Task("Buy milk Status:Someday")
        assertTrue(task.isSomeday)
    }

    fun testNotSomedayByDefault() {
        val task = Task("Buy milk")
        assertFalse(task.isSomeday)
    }

    fun testSomedayRoundTrip() {
        val task = Task("Buy milk status:someday")
        assertEquals("Buy milk status:someday", task.text)
    }

    fun testSomedayExcludedFromDisplayText() {
        val task = Task("Buy milk status:someday")
        assertFalse(task.displayText.contains("status:someday"))
        assertEquals("Buy milk", task.displayText)
    }

    fun testSetSomeday() {
        val task = Task("Buy milk")
        task.isSomeday = true
        assertTrue(task.isSomeday)
        assertTrue(task.text.contains("status:someday"))
    }

    fun testUnsetSomeday() {
        val task = Task("Buy milk status:someday")
        task.isSomeday = false
        assertFalse(task.isSomeday)
        assertFalse(task.text.contains("status:someday"))
    }

    fun testFrozenAndSomedayAreIndependent() {
        val task = Task("Buy milk status:frozen")
        task.isSomeday = true
        assertTrue(task.isFrozen)
        assertTrue(task.isSomeday)
        task.isFrozen = false
        assertFalse(task.isFrozen)
        assertTrue(task.isSomeday)
    }
}
