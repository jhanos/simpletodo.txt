package io.github.todotxt.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import io.github.todotxt.app.R

class InboxEditActivity : Activity() {

    companion object {
        /** Index of the existing item in the list (-1 = new item). */
        const val EXTRA_ITEM_INDEX   = "inbox_item_index"
        const val EXTRA_ITEM_TITLE   = "inbox_item_title"
        const val EXTRA_ITEM_DESC    = "inbox_item_desc"
        /** Action: save (put EXTRA_ITEM_*) or delete (put EXTRA_DELETE = true). */
        const val EXTRA_DELETE       = "inbox_delete"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inbox_edit)

        val titleEdit  = findViewById<EditText>(R.id.inboxTitleEdit)
        val descEdit   = findViewById<EditText>(R.id.inboxDescEdit)
        val saveBtn    = findViewById<Button>(R.id.inboxSaveButton)
        val cancelBtn  = findViewById<Button>(R.id.inboxCancelButton)
        val deleteBtn  = findViewById<Button>(R.id.inboxDeleteButton)

        val itemIndex  = intent.getIntExtra(EXTRA_ITEM_INDEX, -1)
        val isEdit     = itemIndex >= 0

        if (isEdit) {
            setTitle(getString(R.string.edit_task))
            titleEdit.setText(intent.getStringExtra(EXTRA_ITEM_TITLE) ?: "")
            descEdit.setText(intent.getStringExtra(EXTRA_ITEM_DESC) ?: "")
            deleteBtn.visibility = android.view.View.VISIBLE
        } else {
            setTitle(getString(R.string.nav_inbox))
        }

        saveBtn.setOnClickListener {
            val title = titleEdit.text.toString().trim()
            if (title.isEmpty()) {
                titleEdit.error = getString(R.string.inbox_title_hint)
                return@setOnClickListener
            }
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_ITEM_INDEX, itemIndex)
                putExtra(EXTRA_ITEM_TITLE, title)
                putExtra(EXTRA_ITEM_DESC, descEdit.text.toString().trim())
            })
            finish()
        }

        cancelBtn.setOnClickListener { finish() }

        deleteBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.inbox_delete_confirm)
                .setPositiveButton(R.string.yes) { _, _ ->
                    setResult(RESULT_OK, Intent().apply {
                        putExtra(EXTRA_ITEM_INDEX, itemIndex)
                        putExtra(EXTRA_DELETE, true)
                    })
                    finish()
                }
                .setNegativeButton(R.string.no, null)
                .show()
        }
    }
}
