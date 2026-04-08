package io.github.todotxt.app.storage

import java.time.LocalDate

/**
 * Shared SharedPreferences key constants and small utilities used across the app.
 *
 * All classes that read or write these preferences must import from here rather
 * than redeclaring their own copies.
 */
object Prefs {
    const val NAME = "todotxt"

    const val TREE_URI        = "pref_tree_uri"
    const val TODO_URI        = "pref_todo_uri"
    const val DONE_URI        = "pref_done_uri"
    const val TASK_CACHE      = "pref_task_cache"
    const val SORT_FIELD      = "pref_sort_field"
    const val SHOW_FUTURE     = "pref_show_future"
    const val FILTER_CONTEXTS = "pref_filter_contexts"
    const val FILTER_PROJECTS = "pref_filter_projects"
    const val FILTER_TEXT     = "pref_filter_text"
    const val ACTIVE_VIEW     = "pref_active_view"
    const val ACTIVE_PROJECT  = "pref_active_project"
    const val REMINDER_TIME   = "pref_reminder_time"
    const val LAST_SYNC_DATE  = "pref_last_sync_date"

    /** Today's date as "YYYY-MM-DD". Equivalent to LocalDate.now().toString(). */
    fun todayString(): String = LocalDate.now().toString()
}
