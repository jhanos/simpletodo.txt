package io.github.todotxt.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.todotxt.app.R
import io.github.todotxt.app.storage.DebugLog
import io.github.todotxt.app.storage.FileStorage
import io.github.todotxt.app.storage.ReminderScheduler

class SettingsActivity : Activity() {

    companion object {
        private const val REQ_OPEN_TREE          = 10
        private const val REQ_NOTIFICATION_PERM  = 11
        const val PREF_TREE_URI = "pref_tree_uri"
    }

    private lateinit var folderUriText: TextView
    private lateinit var remindersSwitch: Switch
    private lateinit var reminderTimeRow: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        folderUriText  = findViewById(R.id.folderUriText)
        remindersSwitch = findViewById(R.id.remindersSwitch)
        reminderTimeRow = findViewById(R.id.reminderTimeValue)

        refreshFolderLabel()
        refreshReminderUi()

        findViewById<Button>(R.id.chooseFolderButton).setOnClickListener {
            DebugLog.clear(this)
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_OPEN_TREE)
        }

        remindersSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestNotificationPermissionIfNeeded { granted ->
                    if (granted) {
                        setRemindersEnabled(true)
                    } else {
                        remindersSwitch.isChecked = false
                    }
                }
            } else {
                setRemindersEnabled(false)
            }
        }

        reminderTimeRow.setOnClickListener {
            val current = prefs().getString(ReminderScheduler.PREF_REMINDER_TIME, "09:00") ?: "09:00"
            val (h, m) = parseTime(current)
            TimePickerDialog(this, { _, hour, minute ->
                val timeStr = "%02d:%02d".format(hour, minute)
                prefs().edit().putString(ReminderScheduler.PREF_REMINDER_TIME, timeStr).apply()
                refreshReminderUi()
                ReminderScheduler.schedule(this, prefs())
            }, h, m, true).show()
        }

        findViewById<Button>(R.id.copyDebugLogButton).setOnClickListener {
            val log = DebugLog.read(this)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("debug log", log))
            Toast.makeText(this, R.string.debug_log_copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN_TREE || resultCode != RESULT_OK) return
        val treeUri = data?.data ?: return

        DebugLog.d(this, "onActivityResult: treeUri=$treeUri")

        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            DebugLog.d(this, "takePersistableUriPermission: OK")
        } catch (e: Exception) {
            DebugLog.e(this, "takePersistableUriPermission FAILED", e)
        }

        prefs().edit().putString(PREF_TREE_URI, treeUri.toString()).apply()
        refreshFolderLabel()

        Thread {
            val todoUri = FileStorage.findFile(this, treeUri, "todo.txt")
            val doneUri = FileStorage.findFile(this, treeUri, "done.txt")
            runOnUiThread {
                if (todoUri == null || doneUri == null) {
                    val missing = listOfNotNull(
                        if (todoUri == null) "todo.txt" else null,
                        if (doneUri == null) "done.txt" else null
                    ).joinToString(", ")
                    DebugLog.e(this, "FAILED to resolve: $missing")
                    Toast.makeText(
                        this,
                        getString(R.string.folder_resolve_error, missing),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    DebugLog.d(this, "SUCCESS: todoUri=$todoUri doneUri=$doneUri")
                    Toast.makeText(this, R.string.folder_set_ok, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION_PERM) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (granted) {
                setRemindersEnabled(true)
            } else {
                remindersSwitch.isChecked = false
                Toast.makeText(this, R.string.reminder_permission_denied, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Reminders ─────────────────────────────────────────────────────────

    private fun setRemindersEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(ReminderScheduler.PREF_REMINDERS_ENABLED, enabled).apply()
        refreshReminderUi()
        ReminderScheduler.schedule(this, prefs())
    }

    private fun refreshReminderUi() {
        val enabled = prefs().getBoolean(ReminderScheduler.PREF_REMINDERS_ENABLED, false)
        // Temporarily suppress the listener while we programmatically update the switch
        remindersSwitch.setOnCheckedChangeListener(null)
        remindersSwitch.isChecked = enabled
        remindersSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestNotificationPermissionIfNeeded { granted ->
                    if (granted) setRemindersEnabled(true)
                    else remindersSwitch.isChecked = false
                }
            } else {
                setRemindersEnabled(false)
            }
        }
        reminderTimeRow.isEnabled = enabled
        reminderTimeRow.alpha = if (enabled) 1f else 0.4f
        val time = prefs().getString(ReminderScheduler.PREF_REMINDER_TIME, "09:00") ?: "09:00"
        reminderTimeRow.text = time
    }

    private fun requestNotificationPermissionIfNeeded(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // POST_NOTIFICATIONS not needed before API 33
            onResult(true)
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            AlertDialog.Builder(this)
                .setMessage(R.string.reminder_rationale)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQ_NOTIFICATION_PERM
                    )
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> onResult(false) }
                .show()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATION_PERM
            )
        }
        // Result delivered via onRequestPermissionsResult; onResult not called here
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun prefs() = getSharedPreferences("todotxt", MODE_PRIVATE)

    private fun refreshFolderLabel() {
        val treeUri = prefs()
            .getString(PREF_TREE_URI, null)
            ?.let { Uri.parse(it) }
        folderUriText.text = treeUri?.lastPathSegment ?: getString(R.string.folder_not_set)
    }

    private fun parseTime(timeStr: String): Pair<Int, Int> {
        return try {
            val parts = timeStr.split(":")
            Pair(parts[0].trim().toInt(), parts[1].trim().toInt())
        } catch (e: Exception) {
            Pair(9, 0)
        }
    }
}
