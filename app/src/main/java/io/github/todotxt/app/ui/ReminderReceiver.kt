package io.github.todotxt.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.todotxt.app.R
import io.github.todotxt.app.model.Task
import io.github.todotxt.app.storage.DebugLog
import io.github.todotxt.app.storage.FileStorage
import io.github.todotxt.app.storage.Prefs
import io.github.todotxt.app.storage.ReminderScheduler

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "reminders"

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

            val today = Prefs.todayString()
            val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            val cached = prefs.getString(Prefs.TASK_CACHE, null)
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

        /**
         * Read todo.txt from SAF then write it back. This forces Nextcloud's SAF
         * provider to pull the latest version from the server and immediately push
         * any local changes back, effectively bidirectional syncing.
         */
        fun performBackgroundSync(context: Context, prefs: android.content.SharedPreferences) {
            val treeUriStr = prefs.getString(Prefs.TREE_URI, null) ?: run {
                DebugLog.d(context, "BackgroundSync: no tree URI — skipping")
                return
            }
            val treeUri = Uri.parse(treeUriStr)

            // Resolve or find the todo.txt URI
            val cachedUriStr = prefs.getString(Prefs.TODO_URI, null)
            val uri: Uri? = if (cachedUriStr != null) {
                Uri.parse(cachedUriStr)
            } else {
                val resolved = FileStorage.findFile(context, treeUri, "todo.txt")
                if (resolved != null) prefs.edit().putString(Prefs.TODO_URI, resolved.toString()).apply()
                resolved
            }

            if (uri == null) {
                DebugLog.d(context, "BackgroundSync: todo.txt not found — skipping")
                return
            }

            val lines = FileStorage.readLines(context, uri)
            if (lines == null) {
                DebugLog.d(context, "BackgroundSync: read failed — skipping write")
                return
            }

            val ok = FileStorage.writeLines(context, uri, lines)
            DebugLog.d(context, "BackgroundSync: write ${if (ok) "succeeded" else "failed"}")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                DebugLog.d(context, "ReminderReceiver: BOOT_COMPLETED — rescheduling alarms")
                ReminderScheduler.schedule(context, prefs)
                ReminderScheduler.scheduleDailySync(context)
                return
            }
            ReminderScheduler.ACTION_SYNC -> {
                DebugLog.d(context, "ReminderReceiver: daily sync alarm fired")
                val result = goAsync()
                Thread {
                    try {
                        performBackgroundSync(context, prefs)
                    } finally {
                        result.finish()
                    }
                    // Re-arm for tomorrow
                    ReminderScheduler.scheduleDailySync(context)
                }.start()
                return
            }
        }

        // Daily reminder alarm fired — post notifications then re-arm for tomorrow
        DebugLog.d(context, "ReminderReceiver: reminder alarm fired")
        fireNow(context)
        ReminderScheduler.schedule(context, prefs)
    }
}
