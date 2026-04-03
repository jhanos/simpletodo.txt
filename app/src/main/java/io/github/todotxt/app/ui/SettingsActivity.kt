package io.github.todotxt.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import io.github.todotxt.app.R
import io.github.todotxt.app.storage.DebugLog
import io.github.todotxt.app.storage.FileStorage

class SettingsActivity : Activity() {

    companion object {
        private const val REQ_OPEN_TREE = 10
        const val PREF_TREE_URI = "pref_tree_uri"
    }

    private lateinit var folderUriText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        folderUriText = findViewById(R.id.folderUriText)
        refreshFolderLabel()

        findViewById<Button>(R.id.chooseFolderButton).setOnClickListener {
            DebugLog.clear(this)
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_OPEN_TREE)
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

        getSharedPreferences("todotxt", MODE_PRIVATE)
            .edit()
            .putString(PREF_TREE_URI, treeUri.toString())
            .apply()

        refreshFolderLabel()

        // Verify files exist on a background thread (Nextcloud does network I/O)
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

    private fun refreshFolderLabel() {
        val treeUri = getSharedPreferences("todotxt", MODE_PRIVATE)
            .getString(PREF_TREE_URI, null)
            ?.let { Uri.parse(it) }
        folderUriText.text = treeUri?.lastPathSegment ?: getString(R.string.folder_not_set)
    }
}
