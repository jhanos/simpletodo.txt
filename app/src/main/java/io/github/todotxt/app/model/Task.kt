package io.github.todotxt.app.model

import java.time.LocalDate

/**
 * A single todo.txt task, stored as a list of typed tokens.
 *
 * Supported todo.txt spec fields:
 *   - Completion marker: "x "
 *   - Priority: "(A) "
 *   - Completion date: "YYYY-MM-DD" (after x)
 *   - Creation date:   "YYYY-MM-DD" (after priority / at start)
 *   - Contexts:  @word
 *   - Projects:  +word
 *
 * Supported extensions (key:value):
 *   - due:YYYY-MM-DD   — due date
 *   - t:YYYY-MM-DD     — threshold (defer) date
 *   - rec:Nd/w/m/y     — recurrence pattern (e.g. rec:1w, rec:+2m)
 */
class Task(text: String) {

    var tokens: MutableList<TToken> = parse(text)

    // ── Derived properties ────────────────────────────────────────────────

    val text: String
        get() = tokens.joinToString(" ") { it.text }

    /** Like [text] but omits @context and +project tokens (shown as pills in the UI). */
    val displayText: String
        get() = tokens
            .filter { it !is ContextToken && it !is ProjectToken }
            .joinToString(" ") { it.text }
            .replace(Regex("  +"), " ")
            .trim()

    var completed: Boolean
        get() = tokens.any { it is CompletedToken }
        set(value) {
            if (value && !completed) {
                tokens.add(0, CompletedToken)
            } else if (!value) {
                tokens.removeAll { it is CompletedToken || it is CompletedDateToken }
            }
        }

    val completionDate: String?
        get() = tokens.firstOrNull { it is CompletedDateToken }?.let { (it as CompletedDateToken).value }

    var createDate: String?
        get() = tokens.firstOrNull { it is CreateDateToken }?.let { (it as CreateDateToken).value }
        set(date) {
            tokens.removeAll { it is CreateDateToken }
            if (date != null) {
                // Insert after priority (and after completed/completedDate if present)
                val insertIdx = insertionIndexForCreateDate()
                tokens.add(insertIdx, CreateDateToken(date))
            }
        }

    var priority: Priority
        get() = (tokens.firstOrNull { it is PriorityToken } as? PriorityToken)?.value ?: Priority.NONE
        set(p) {
            tokens.removeAll { it is PriorityToken }
            if (p != Priority.NONE) {
                // Priority always first (unless task is completed, then after x / completion date)
                val idx = if (completed) {
                    val cdIdx = tokens.indexOfFirst { it is CompletedDateToken }
                    if (cdIdx >= 0) cdIdx + 1 else 1
                } else 0
                tokens.add(idx, PriorityToken(p))
            }
        }

    var dueDate: String?
        get() = (tokens.firstOrNull { it is DueDateToken } as? DueDateToken)?.valueStr
        set(date) {
            tokens.removeAll { it is DueDateToken }
            if (!date.isNullOrEmpty()) tokens.add(DueDateToken(date))
        }

    var thresholdDate: String?
        get() = (tokens.firstOrNull { it is ThresholdDateToken } as? ThresholdDateToken)?.valueStr
        set(date) {
            tokens.removeAll { it is ThresholdDateToken }
            if (!date.isNullOrEmpty()) tokens.add(ThresholdDateToken(date))
        }

    val recurrencePattern: String?
        get() = (tokens.firstOrNull { it is RecurrenceToken } as? RecurrenceToken)?.valueStr

    val contexts: List<String>
        get() = tokens.filterIsInstance<ContextToken>().map { it.value }

    val projects: List<String>
        get() = tokens.filterIsInstance<ProjectToken>().map { it.value }

    // ── Mutations ─────────────────────────────────────────────────────────

    /**
     * Mark this task complete on [dateStr]. If the task has a rec: pattern,
     * returns a new Task with deferred dates; otherwise returns null.
     */
    fun markComplete(dateStr: String): Task? {
        if (completed) return null
        val originalText = text
        // Prepend "x YYYY-MM-DD"
        tokens.add(0, CompletedDateToken(dateStr))
        tokens.add(0, CompletedToken)
        val pattern = recurrencePattern ?: return null
        // Build a new recurring task from the original (pre-completion) text
        val newTask = Task(originalText)
        val strict = pattern.startsWith("+")
        val baseDate = if (strict) dateStr else (newTask.dueDate ?: newTask.thresholdDate ?: dateStr)
        newTask.dueDate?.let     { newTask.dueDate       = addInterval(baseDate, pattern.removePrefix("+")) }
        newTask.thresholdDate?.let { newTask.thresholdDate = addInterval(baseDate, pattern.removePrefix("+")) }
        if (newTask.createDate != null) newTask.createDate = dateStr
        return newTask
    }

    fun markIncomplete() {
        tokens.removeAll { it is CompletedToken || it is CompletedDateToken }
    }

    fun isInFuture(today: String): Boolean {
        val threshold = thresholdDate ?: return false
        return threshold > today
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun insertionIndexForCreateDate(): Int {
        var idx = 0
        if (idx < tokens.size && tokens[idx] is CompletedToken) idx++
        if (idx < tokens.size && tokens[idx] is CompletedDateToken) idx++
        if (idx < tokens.size && tokens[idx] is PriorityToken) idx++
        return idx
    }

    override fun toString(): String = text

    // ── Companion / parser ────────────────────────────────────────────────

    companion object {
        private val DATE_PATTERN      = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val PRIORITY_PATTERN  = Regex("\\(([A-Z])\\)")
        private val CONTEXT_PATTERN   = Regex("@(\\S+)")
        private val PROJECT_PATTERN   = Regex("\\+(\\S+)")
        private val DUE_PATTERN       = Regex("[Dd][Uu][Ee]:(\\d{4}-\\d{2}-\\d{2})")
        private val THRESHOLD_PATTERN = Regex("[Tt]:(\\d{4}-\\d{2}-\\d{2})")
        private val REC_PATTERN       = Regex("[Rr][Ee][Cc]:(\\+?\\d*[dDwWmMyY])")
        private val EXT_PATTERN       = Regex("([^:\\s]+):([^:\\s]+)")

        fun parse(text: String): MutableList<TToken> {
            val tokens = mutableListOf<TToken>()
            var words = text.split(' ')

            // 1. Completion marker
            if (words.firstOrNull() == "x") {
                tokens += CompletedToken
                words = words.drop(1)
                // Completion date
                if (words.isNotEmpty() && DATE_PATTERN.matches(words[0])) {
                    tokens += CompletedDateToken(words[0])
                    words = words.drop(1)
                    // Creation date (only present alongside completion date)
                    if (words.isNotEmpty() && DATE_PATTERN.matches(words[0])) {
                        tokens += CreateDateToken(words[0])
                        words = words.drop(1)
                    }
                }
            }

            // 2. Priority (only for incomplete tasks)
            if (words.isNotEmpty()) {
                val m = PRIORITY_PATTERN.matchEntire(words[0])
                if (m != null) {
                    tokens += PriorityToken(Priority.fromCode(m.groupValues[1]))
                    words = words.drop(1)
                }
            }

            // 3. Creation date
            if (words.isNotEmpty() && DATE_PATTERN.matches(words[0])) {
                tokens += CreateDateToken(words[0])
                words = words.drop(1)
            }

            // 4. Remaining body tokens
            for (word in words) {
                when {
                    word.isEmpty() -> tokens += WhitespaceToken
                    else -> {
                        val due = DUE_PATTERN.matchEntire(word)
                        val thr = if (due == null) THRESHOLD_PATTERN.matchEntire(word) else null
                        val rec = if (thr == null && due == null) REC_PATTERN.matchEntire(word) else null
                        val ctx = if (rec == null && thr == null && due == null) CONTEXT_PATTERN.matchEntire(word) else null
                        val prj = if (ctx == null && rec == null && thr == null && due == null) PROJECT_PATTERN.matchEntire(word) else null
                        val ext = if (prj == null && ctx == null && rec == null && thr == null && due == null) EXT_PATTERN.matchEntire(word) else null
                        when {
                            due != null -> tokens += DueDateToken(due.groupValues[1])
                            thr != null -> tokens += ThresholdDateToken(thr.groupValues[1])
                            rec != null -> tokens += RecurrenceToken(rec.groupValues[1])
                            ctx != null -> tokens += ContextToken(ctx.groupValues[1])
                            prj != null -> tokens += ProjectToken(prj.groupValues[1])
                            ext != null -> tokens += ExtToken(ext.groupValues[1], ext.groupValues[2])
                            else        -> tokens += TextToken(word)
                        }
                    }
                }
            }
            return tokens
        }

        /**
         * Add an interval string like "1d", "2w", "1m", "1y" to a YYYY-MM-DD date.
         * Returns the new date string, or null if parsing fails.
         */
        fun addInterval(fromDate: String, interval: String): String? {
            return try {
                val clean = interval.removePrefix("+").lowercase()
                val amount = clean.dropLast(1).toInt()
                val unit = clean.last()
                val base = LocalDate.parse(fromDate)
                val result = when (unit) {
                    'd' -> base.plusDays(amount.toLong())
                    'w' -> base.plusWeeks(amount.toLong())
                    'm' -> base.plusMonths(amount.toLong())
                    'y' -> base.plusYears(amount.toLong())
                    else -> return null
                }
                result.toString()
            } catch (e: Exception) { null }
        }
    }
}

// ── Token types ───────────────────────────────────────────────────────────────

sealed interface TToken { val text: String }

object CompletedToken : TToken { override val text = "x" }
object WhitespaceToken : TToken { override val text = "" }

data class CompletedDateToken(val value: String) : TToken { override val text = value }
data class CreateDateToken(val value: String)    : TToken { override val text = value }
data class PriorityToken(val value: Priority)    : TToken { override val text = value.fileFormat }
data class ContextToken(val value: String)       : TToken { override val text = "@$value" }
data class ProjectToken(val value: String)       : TToken { override val text = "+$value" }
data class TextToken(val value: String)          : TToken { override val text = value }

// Key:value extension tokens
data class DueDateToken(val valueStr: String)    : TToken { override val text = "due:$valueStr" }
data class ThresholdDateToken(val valueStr: String): TToken { override val text = "t:$valueStr" }
data class RecurrenceToken(val valueStr: String) : TToken { override val text = "rec:$valueStr" }
data class ExtToken(val key: String, val valueStr: String) : TToken { override val text = "$key:$valueStr" }
