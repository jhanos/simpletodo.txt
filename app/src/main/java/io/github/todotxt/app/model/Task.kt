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
 *   - status:frozen    — task is frozen (locked from completion)
 *   - status:someday   — task is deferred to "someday/maybe"
 */
class Task(text: String) {

    // Backing list — all mutations must call invalidateCache()
    private val _tokens: MutableList<TToken> = parse(text)

    /** Direct access to the token list. Any external mutation must be followed by invalidateCache(). */
    var tokens: MutableList<TToken>
        get() = _tokens
        set(value) {
            _tokens.clear()
            _tokens.addAll(value)
            invalidateCache()
        }

    // ── String caches ─────────────────────────────────────────────────────

    private var _text: String? = null
    private var _displayText: String? = null

    private fun invalidateCache() {
        _text = null
        _displayText = null
    }

    // ── Derived properties ────────────────────────────────────────────────

    val text: String
        get() = _text ?: _tokens.joinToString(" ") { it.text }.also { _text = it }

    /** Like [text] but omits @context, +project and due:date tokens (shown separately in the UI). */
    val displayText: String
        get() = _displayText ?: _tokens
            .filter { it !is ContextToken && it !is ProjectToken && it !is DueDateToken && it !is StatusToken && it !is RecurrenceToken }
            .joinToString(" ") { it.text }
            .replace(Regex("  +"), " ")
            .trim()
            .also { _displayText = it }

    var completed: Boolean
        get() = _tokens.any { it is CompletedToken }
        set(value) {
            if (value && !completed) {
                _tokens.add(0, CompletedToken)
            } else if (!value) {
                _tokens.removeAll { it is CompletedToken || it is CompletedDateToken }
            }
            invalidateCache()
        }

    val completionDate: String?
        get() = (_tokens.firstOrNull { it is CompletedDateToken } as? CompletedDateToken)?.value

    var createDate: String?
        get() = (_tokens.firstOrNull { it is CreateDateToken } as? CreateDateToken)?.value
        set(date) {
            _tokens.removeAll { it is CreateDateToken }
            if (date != null) {
                _tokens.add(insertionIndexForCreateDate(), CreateDateToken(date))
            }
            invalidateCache()
        }

    var priority: Priority
        get() = (_tokens.firstOrNull { it is PriorityToken } as? PriorityToken)?.value ?: Priority.NONE
        set(p) {
            _tokens.removeAll { it is PriorityToken }
            if (p != Priority.NONE) {
                val idx = if (completed) {
                    val cdIdx = _tokens.indexOfFirst { it is CompletedDateToken }
                    if (cdIdx >= 0) cdIdx + 1 else 1
                } else 0
                _tokens.add(idx, PriorityToken(p))
            }
            invalidateCache()
        }

    var dueDate: String?
        get() = (_tokens.firstOrNull { it is DueDateToken } as? DueDateToken)?.valueStr
        set(date) {
            _tokens.removeAll { it is DueDateToken }
            if (!date.isNullOrEmpty()) _tokens.add(DueDateToken(date))
            invalidateCache()
        }

    var thresholdDate: String?
        get() = (_tokens.firstOrNull { it is ThresholdDateToken } as? ThresholdDateToken)?.valueStr
        set(date) {
            _tokens.removeAll { it is ThresholdDateToken }
            if (!date.isNullOrEmpty()) _tokens.add(ThresholdDateToken(date))
            invalidateCache()
        }

    val recurrencePattern: String?
        get() = (_tokens.firstOrNull { it is RecurrenceToken } as? RecurrenceToken)?.valueStr

    /** True when the task carries a `status:frozen` token. */
    var isFrozen: Boolean
        get() = _tokens.any { it is StatusToken && it.value == "frozen" }
        set(value) {
            _tokens.removeAll { it is StatusToken && it.value == "frozen" }
            if (value) _tokens.add(StatusToken("frozen"))
            invalidateCache()
        }

    /** True when the task carries a `status:someday` token. */
    var isSomeday: Boolean
        get() = _tokens.any { it is StatusToken && it.value == "someday" }
        set(value) {
            _tokens.removeAll { it is StatusToken && it.value == "someday" }
            if (value) _tokens.add(StatusToken("someday"))
            invalidateCache()
        }

    val contexts: List<String>
        get() = _tokens.filterIsInstance<ContextToken>().map { it.value }

    val projects: List<String>
        get() = _tokens.filterIsInstance<ProjectToken>().map { it.value }

    // ── Mutations ─────────────────────────────────────────────────────────

    /**
     * Mark this task complete on [dateStr]. If the task has a rec: pattern,
     * returns a new Task with deferred dates; otherwise returns null.
     */
    fun markComplete(dateStr: String): Task? {
        if (completed) return null
        val originalText = text
        _tokens.add(0, CompletedDateToken(dateStr))
        _tokens.add(0, CompletedToken)
        invalidateCache()
        val pattern = recurrencePattern ?: return null
        val newTask = Task(originalText)
        val strict = pattern.startsWith("+")
        val baseDate = if (strict) dateStr else (newTask.dueDate ?: newTask.thresholdDate ?: dateStr)
        val interval = pattern.removePrefix("+")
        // Always assign a due date on the new recurring task (create one if absent)
        newTask.dueDate = addInterval(baseDate, interval)
        newTask.thresholdDate?.let { newTask.thresholdDate = addInterval(baseDate, interval) }
        if (newTask.createDate != null) newTask.createDate = dateStr
        return newTask
    }

    fun markIncomplete() {
        _tokens.removeAll { it is CompletedToken || it is CompletedDateToken }
        invalidateCache()
    }

    fun isInFuture(today: String): Boolean {
        val threshold = thresholdDate ?: return false
        return threshold > today
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun insertionIndexForCreateDate(): Int {
        var idx = 0
        if (idx < _tokens.size && _tokens[idx] is CompletedToken) idx++
        if (idx < _tokens.size && _tokens[idx] is CompletedDateToken) idx++
        if (idx < _tokens.size && _tokens[idx] is PriorityToken) idx++
        return idx
    }

    override fun toString(): String = text

    // ── Companion / parser ────────────────────────────────────────────────

    companion object {
        private val DATE_PATTERN     = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val PRIORITY_PATTERN = Regex("\\(([A-Z])\\)")

        // Ordered token matchers for body words — first match wins
        private val BODY_MATCHERS: List<Pair<Regex, (MatchResult) -> TToken>> = listOf(
            Regex("[Dd][Uu][Ee]:(\\d{4}-\\d{2}-\\d{2})")       to { m -> DueDateToken(m.groupValues[1]) },
            Regex("[Tt]:(\\d{4}-\\d{2}-\\d{2})")               to { m -> ThresholdDateToken(m.groupValues[1]) },
            Regex("[Rr][Ee][Cc]:(\\+?\\d*[dDwWmMyY])")         to { m -> RecurrenceToken(m.groupValues[1]) },
            Regex("[Ss][Tt][Aa][Tt][Uu][Ss]:([^:\\s]+)")        to { m -> StatusToken(m.groupValues[1].lowercase()) },
            Regex("@(\\S+)")                                     to { m -> ContextToken(m.groupValues[1]) },
            Regex("\\+(\\S+)")                                   to { m -> ProjectToken(m.groupValues[1]) },
            Regex("([^:\\s]+):([^:\\s]+)")                       to { m -> ExtToken(m.groupValues[1], m.groupValues[2]) }
        )

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

            // 4. Remaining body tokens — try each matcher in priority order
            for (word in words) {
                if (word.isEmpty()) {
                    tokens += WhitespaceToken
                    continue
                }
                val token = BODY_MATCHERS
                    .firstOrNull { (pattern, _) -> pattern.matchEntire(word) != null }
                    ?.let { (pattern, factory) -> factory(pattern.matchEntire(word)!!) }
                    ?: TextToken(word)
                tokens += token
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

object CompletedToken  : TToken { override val text = "x" }
object WhitespaceToken : TToken { override val text = "" }

data class CompletedDateToken(val value: String)   : TToken { override val text = value }
data class CreateDateToken(val value: String)       : TToken { override val text = value }
data class PriorityToken(val value: Priority)       : TToken { override val text = value.fileFormat }
data class ContextToken(val value: String)          : TToken { override val text = "@$value" }
data class ProjectToken(val value: String)          : TToken { override val text = "+$value" }
data class TextToken(val value: String)             : TToken { override val text = value }

// Key:value extension tokens
data class DueDateToken(val valueStr: String)       : TToken { override val text = "due:$valueStr" }
data class ThresholdDateToken(val valueStr: String) : TToken { override val text = "t:$valueStr" }
data class RecurrenceToken(val valueStr: String)    : TToken { override val text = "rec:$valueStr" }
data class StatusToken(val value: String)           : TToken { override val text = "status:$value" }
data class ExtToken(val key: String, val valueStr: String) : TToken { override val text = "$key:$valueStr" }
