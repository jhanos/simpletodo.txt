package io.github.todotxt.app.storage

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import io.github.todotxt.app.model.Task
import java.time.LocalDateTime
import java.time.ZoneId

object ReminderScheduler {

    const val PREF_REMINDERS_ENABLED = "pref_reminders_enabled"

    private const val ACTION_REMINDER      = "io.github.todotxt.app.REMINDER"
    private const val REQUEST_CODE         = 1001
    private const val REQUEST_CODE_TEST    = 1002

    /**
     * Schedule (or cancel) the single daily reminder alarm.
     *
     * Call this:
     *  - after every successful load/save in MainActivity
     *  - after the user changes reminder settings in SettingsActivity
     *  - on BOOT_COMPLETED (from ReminderReceiver)
     *
     * If reminders are disabled, any existing alarm is cancelled.
     * If reminders are enabled, a single exact alarm is set for the next
     * occurrence of the configured time (today if it hasn't passed, tomorrow
     * otherwise).  That alarm fires ReminderReceiver which posts notifications
     * for all tasks due that day, then calls schedule() again to arm the next
     * day's alarm.
     */
    fun schedule(context: Context, prefs: SharedPreferences) {
        val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = buildPendingIntent(context)

        if (!prefs.getBoolean(PREF_REMINDERS_ENABLED, false)) {
            alarmMgr.cancel(pi)
            DebugLog.d(context, "ReminderScheduler: reminders disabled — alarm cancelled")
            return
        }

        val timeStr = prefs.getString(Prefs.REMINDER_TIME, "09:00") ?: "09:00"
        val (hour, minute) = parseTime(timeStr)

        val now = LocalDateTime.now()
        var trigger = now.toLocalDate().atTime(hour, minute)
        if (!trigger.isAfter(now)) {
            trigger = trigger.plusDays(1)
        }

        val triggerMs = trigger
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmMgr.canScheduleExactAlarms()) {
            alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            DebugLog.d(context, "ReminderScheduler: exact alarm not permitted — using inexact fallback for $trigger")
        } else {
            alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            DebugLog.d(context, "ReminderScheduler: exact alarm set for $trigger")
        }
    }

    /**
     * Schedule a one-shot test alarm for [hour]:[minute] today (or in the next
     * minute if that time has already passed).  Uses a separate request code so
     * it never interferes with the real daily alarm.
     */
    fun scheduleTest(context: Context, hour: Int, minute: Int) {
        val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_TEST,
            Intent(ACTION_REMINDER).apply { setPackage(context.packageName) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val now = LocalDateTime.now()
        var trigger = now.toLocalDate().atTime(hour, minute)
        if (!trigger.isAfter(now)) {
            trigger = trigger.plusDays(1)
        }

        val triggerMs = trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmMgr.canScheduleExactAlarms()) {
            alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            DebugLog.d(context, "ReminderScheduler: test alarm (inexact) set for $trigger")
        } else {
            alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            DebugLog.d(context, "ReminderScheduler: test alarm set for $trigger")
        }
    }

    /**
     * Given today's date string and all tasks, return those that are due today
     * or overdue and not already completed.
     */
    fun tasksToRemind(tasks: List<Task>, today: String): List<Task> =
        tasks.filter { !it.completed && it.dueDate != null && it.dueDate!! <= today }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_REMINDER).apply {
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    internal fun parseTime(timeStr: String): Pair<Int, Int> {
        return try {
            val parts = timeStr.split(":")
            Pair(parts[0].trim().toInt(), parts[1].trim().toInt())
        } catch (e: Exception) {
            Pair(9, 0)
        }
    }
}
