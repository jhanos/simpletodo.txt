package io.github.todotxt.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.todotxt.app.R
import io.github.todotxt.app.model.Task
import io.github.todotxt.app.storage.DebugLog
import io.github.todotxt.app.storage.ReminderScheduler
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "reminders"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /**
         * Post reminder notifications immediately using the current task cache.
         * If there are tasks due today or overdue, one notification per task is
         * posted.  If there are no due tasks, a single placeholder notification
         * is posted so the user can verify the channel is working.
         *
         * Safe to call from any context (Settings test button or alarm receiver).
         */
        fun fireNow(context: Context) {
            ensureNotificationChannel(context)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val today = LocalDate.now().format(DATE_FMT)
            val prefs = context.getSharedPreferences("todotxt", Context.MODE_PRIVATE)
            val cached = prefs.getString("pref_task_cache", null)
            val tasks: List<Task> = if (cached != null) {
                cached.split('\n').filter { it.isNotBlank() }.map { Task(it) }
            } else {
                emptyList()
            }

            val due = ReminderScheduler.tasksToRemind(tasks, today)
            DebugLog.d(context, "ReminderReceiver.fireNow: ${due.size} tasks due or overdue")

            if (due.isEmpty()) {
                // Post a placeholder so the user can confirm the channel works
                val tapIntent = PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = android.app.Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(context.getString(R.string.reminder_title))
                    .setContentText(context.getString(R.string.reminder_no_tasks))
                    .setContentIntent(tapIntent)
                    .setAutoCancel(true)
                    .build()
                nm.notify("no_tasks".hashCode(), notification)
            } else {
                due.forEachIndexed { index, task ->
                    postNotification(context, nm, index, task)
                }
            }
        }

        private fun postNotification(
            context: Context,
            nm: NotificationManager,
            index: Int,
            task: Task
        ) {
            val tapIntent = PendingIntent.getActivity(
                context,
                index,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = android.app.Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(context.getString(R.string.reminder_title))
                .setContentText(task.displayText.ifBlank { task.text })
                .setContentIntent(tapIntent)
                .setAutoCancel(true)
                .build()

            nm.notify(task.text.hashCode(), notification)
        }

        private fun ensureNotificationChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.reminder_channel_desc)
            }
            nm.createNotificationChannel(channel)
            DebugLog.d(context, "ReminderReceiver: notification channel created")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("todotxt", Context.MODE_PRIVATE)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                DebugLog.d(context, "ReminderReceiver: BOOT_COMPLETED — rescheduling alarm")
                ReminderScheduler.schedule(context, prefs)
                return
            }
        }

        // Daily alarm fired — post notifications then re-arm for tomorrow
        DebugLog.d(context, "ReminderReceiver: alarm fired")
        fireNow(context)
        ReminderScheduler.schedule(context, prefs)
    }
}
