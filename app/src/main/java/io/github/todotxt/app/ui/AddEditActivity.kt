package io.github.todotxt.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import io.github.todotxt.app.R
import io.github.todotxt.app.model.Priority

class AddEditActivity : Activity() {

    companion object {
        const val EXTRA_TASK_TEXT     = "task_text"
        const val EXTRA_OLD_TASK_TEXT = "old_task_text"
    }

    private lateinit var taskEditText: EditText
    private lateinit var prioritySpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_edit)

        taskEditText    = findViewById(R.id.taskEditText)
        prioritySpinner = findViewById(R.id.prioritySpinner)

        // Populate priority spinner: "None", "A", "B", …, "Z"
        val priorities = mutableListOf(getString(R.string.none))
        priorities.addAll(Priority.entries.filter { it != Priority.NONE }.map { it.code })
        prioritySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            priorities
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // Pre-fill when editing
        val existingText = intent.getStringExtra(EXTRA_TASK_TEXT)
        if (existingText != null) {
            setTitle(R.string.edit_task)
            taskEditText.setText(existingText)
            taskEditText.setSelection(existingText.length)
            // Select the right priority from existing text
            val prioMatch = Regex("^\\(([A-Z])\\) ").find(existingText)
            if (prioMatch != null) {
                val code = prioMatch.groupValues[1]
                val idx = priorities.indexOf(code)
                if (idx >= 0) prioritySpinner.setSelection(idx)
            }
        } else {
            setTitle(R.string.add_task)
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener { save(existingText) }
        findViewById<Button>(R.id.cancelButton).setOnClickListener { finish() }
    }

    private fun save(oldText: String?) {
        var raw = taskEditText.text.toString().trim()
        if (raw.isEmpty()) { finish(); return }

        // Apply priority from spinner if selected
        val selectedPrio = prioritySpinner.selectedItemPosition
        // Remove any existing priority prefix first
        raw = raw.replace(Regex("^\\([A-Z]\\) "), "")
        if (selectedPrio > 0) {
            val code = Priority.entries.filter { it != Priority.NONE }[selectedPrio - 1].code
            raw = "($code) $raw"
        }

        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_TASK_TEXT, raw)
            if (oldText != null) putExtra(EXTRA_OLD_TASK_TEXT, oldText)
        })
        finish()
    }
}
