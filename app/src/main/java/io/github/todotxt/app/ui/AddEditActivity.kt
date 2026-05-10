package io.github.todotxt.app.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import io.github.todotxt.app.R
import io.github.todotxt.app.model.Priority
import io.github.todotxt.app.model.Task
import io.github.todotxt.app.storage.NoteStorage
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar

class AddEditActivity : Activity() {

    companion object {
        const val EXTRA_TASK_TEXT     = "task_text"
        const val EXTRA_OLD_TASK_TEXT = "old_task_text"
        const val EXTRA_ALL_CONTEXTS  = "all_contexts"
        const val EXTRA_ALL_PROJECTS  = "all_projects"
        const val EXTRA_DELETE        = "delete"

        private val DEFAULT_CONTEXTS = listOf("phone", "mail")

        // Chip colours
        private const val CHIP_SELECTED_BG   = 0xFF1565C0.toInt()  // dark blue
        private const val CHIP_UNSELECTED_BG = 0xFFE0E0E0.toInt()  // light grey
        private const val CHIP_SELECTED_TEXT = Color.WHITE
        private const val CHIP_UNSELECTED_TEXT = Color.BLACK
        private const val CHIP_CORNER_RADIUS = 32f

        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        private const val REQ_NOTE = 2001
    }

    private lateinit var taskEditText: EditText
    private lateinit var prioritySpinner: Spinner
    private lateinit var contextsGroup: LinearLayout
    private lateinit var projectsGroup: LinearLayout
    private lateinit var dueDateValue: TextView
    private lateinit var dueDateClearButton: Button
    private lateinit var frozenSwitch: Switch
    private lateinit var somedaySwitch: Switch
    private lateinit var recurrenceEdit: EditText
    private lateinit var noteButton: Button

    // Collapsible section views
    private lateinit var contextsHeader: LinearLayout
    private lateinit var contextsBody: LinearLayout
    private lateinit var contextsSummary: TextView
    private lateinit var contextsArrow: TextView
    private lateinit var projectsHeader: LinearLayout
    private lateinit var projectsBody: LinearLayout
    private lateinit var projectsSummary: TextView
    private lateinit var projectsArrow: TextView

    // Track selected values
    private val selectedContexts = mutableSetOf<String>()
    private val selectedProjects  = mutableSetOf<String>()
    private var selectedDueDate: String? = null   // "YYYY-MM-DD" or null
    private var currentNoteId: String?  = null    // note:id token value, or null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_edit)

        taskEditText      = findViewById(R.id.taskEditText)
        prioritySpinner   = findViewById(R.id.prioritySpinner)
        contextsGroup     = findViewById(R.id.contextsChipGroup)
        projectsGroup     = findViewById(R.id.projectsChipGroup)
        dueDateValue      = findViewById(R.id.dueDateValue)
        dueDateClearButton = findViewById(R.id.dueDateClearButton)
        frozenSwitch      = findViewById(R.id.frozenSwitch)
        somedaySwitch     = findViewById(R.id.somedaySwitch)
        recurrenceEdit    = findViewById(R.id.recurrenceEdit)
        noteButton        = findViewById(R.id.noteButton)
        contextsHeader    = findViewById(R.id.contextsHeader)
        contextsBody      = findViewById(R.id.contextsBody)
        contextsSummary   = findViewById(R.id.contextsSummary)
        contextsArrow     = findViewById(R.id.contextsArrow)
        projectsHeader    = findViewById(R.id.projectsHeader)
        projectsBody      = findViewById(R.id.projectsBody)
        projectsSummary   = findViewById(R.id.projectsSummary)
        projectsArrow     = findViewById(R.id.projectsArrow)

        // Priority spinner
        val priorities = mutableListOf(getString(R.string.none))
        priorities.addAll(Priority.entries.filter { it != Priority.NONE }.map { it.code })
        prioritySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            priorities
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val existingText = intent.getStringExtra(EXTRA_TASK_TEXT)

        // Parse existing task once using the model — reuse for contexts, projects, due, display text
        val existingTask = existingText?.let { Task(it) }
        selectedContexts.addAll(existingTask?.contexts ?: emptyList())
        selectedProjects.addAll(existingTask?.projects ?: emptyList())
        selectedDueDate = existingTask?.dueDate
        currentNoteId   = existingTask?.noteId

        // Build context list: defaults + any from file, deduped, sorted
        val allContextsFromFile = intent.getStringArrayListExtra(EXTRA_ALL_CONTEXTS) ?: arrayListOf()
        val allContexts = (DEFAULT_CONTEXTS + allContextsFromFile)
            .map { it.lowercase() }
            .distinct()
            .sorted()

        // Build project list: from file, sorted
        val allProjects = (intent.getStringArrayListExtra(EXTRA_ALL_PROJECTS) ?: arrayListOf())
            .distinct()
            .sorted()

        buildChips(contextsGroup, allContexts, selectedContexts) {
            refreshSectionSummary(contextsSummary, getString(R.string.contexts_label), selectedContexts)
        }
        buildChips(projectsGroup, allProjects, selectedProjects) {
            refreshSectionSummary(projectsSummary, getString(R.string.projects_label), selectedProjects)
        }

        // Collapsible contexts — auto-expand if task has contexts
        refreshSectionSummary(contextsSummary, getString(R.string.contexts_label), selectedContexts)
        if (selectedContexts.isNotEmpty()) toggleSection(contextsBody, contextsArrow)
        contextsHeader.setOnClickListener { toggleSection(contextsBody, contextsArrow) }

        // Collapsible projects — auto-expand if task has projects
        refreshSectionSummary(projectsSummary, getString(R.string.projects_label), selectedProjects)
        if (selectedProjects.isNotEmpty()) toggleSection(projectsBody, projectsArrow)
        projectsHeader.setOnClickListener { toggleSection(projectsBody, projectsArrow) }

        // Due date row
        refreshDueDateUi()
        dueDateValue.setOnClickListener { showDatePicker() }
        dueDateClearButton.setOnClickListener {
            selectedDueDate = null
            refreshDueDateUi()
        }

        // Manual context entry
        val contextInput = findViewById<EditText>(R.id.contextInput)
        val addContextBtn = findViewById<Button>(R.id.addContextButton)
        fun addManualContext() {
            val value = contextInput.text.toString().trim().lowercase()
            if (value.isNotEmpty()) {
                selectedContexts.add(value)
                buildChips(contextsGroup, (allContexts + selectedContexts).distinct().sorted(), selectedContexts) {
                    refreshSectionSummary(contextsSummary, getString(R.string.contexts_label), selectedContexts)
                }
                refreshSectionSummary(contextsSummary, getString(R.string.contexts_label), selectedContexts)
                contextInput.setText("")
            }
        }
        addContextBtn.setOnClickListener { addManualContext() }
        contextInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                addManualContext(); true
            } else false
        }

        // Manual project entry
        val projectInput = findViewById<EditText>(R.id.projectInput)
        val addProjectBtn = findViewById<Button>(R.id.addProjectButton)
        fun addManualProject() {
            val value = projectInput.text.toString().trim().lowercase()
            if (value.isNotEmpty()) {
                selectedProjects.add(value)
                buildChips(projectsGroup, (allProjects + selectedProjects).distinct().sorted(), selectedProjects) {
                    refreshSectionSummary(projectsSummary, getString(R.string.projects_label), selectedProjects)
                }
                refreshSectionSummary(projectsSummary, getString(R.string.projects_label), selectedProjects)
                projectInput.setText("")
            }
        }
        addProjectBtn.setOnClickListener { addManualProject() }
        projectInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                addManualProject(); true
            } else false
        }

        // Pre-fill when editing
        if (existingTask != null) {
            setTitle(R.string.edit_task)
            // Show displayText (without due:, @context, +project) in the edit field
            val displayRaw = existingTask.displayText
            taskEditText.setText(displayRaw)
            taskEditText.setSelection(displayRaw.length)
            val prioCode = existingTask.priority.takeIf { it != Priority.NONE }?.code
            if (prioCode != null) {
                val idx = priorities.indexOf(prioCode)
                if (idx >= 0) prioritySpinner.setSelection(idx)
            }
            frozenSwitch.isChecked = existingTask.isFrozen
            somedaySwitch.isChecked = existingTask.isSomeday
            recurrenceEdit.setText(existingTask.recurrencePattern ?: "")
        } else {
            setTitle(R.string.add_task)
            frozenSwitch.isChecked = false
            somedaySwitch.isChecked = false
            recurrenceEdit.setText("")
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener { save(existingText) }
        findViewById<Button>(R.id.cancelButton).setOnClickListener { finish() }

        val deleteButton = findViewById<Button>(R.id.deleteButton)
        if (existingText != null) {
            deleteButton.visibility = View.VISIBLE
            deleteButton.setOnClickListener {
                android.app.AlertDialog.Builder(this)
                    .setMessage(R.string.delete_confirm)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        setResult(RESULT_OK, Intent().apply {
                            putExtra(EXTRA_DELETE, true)
                            putExtra(EXTRA_OLD_TASK_TEXT, existingText)
                        })
                        finish()
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
        }

        refreshNoteButton()
        noteButton.setOnClickListener {
            startActivityForResult(
                Intent(this, NoteActivity::class.java).apply {
                    currentNoteId?.let { putExtra(NoteActivity.EXTRA_NOTE_ID, it) }
                },
                REQ_NOTE
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_NOTE && resultCode == RESULT_OK && data != null) {
            currentNoteId = if (data.getBooleanExtra(NoteActivity.EXTRA_DELETED, false)) {
                null
            } else {
                data.getStringExtra(NoteActivity.EXTRA_NOTE_ID)
            }
            refreshNoteButton()
        }
    }

    private fun refreshNoteButton() {
        if (currentNoteId != null) {
            val note = NoteStorage.load(this, currentNoteId!!)
            val imgCount = note?.images?.size ?: 0
            noteButton.text = if (imgCount > 0)
                getString(R.string.note_edit_with_images, imgCount)
            else
                getString(R.string.note_edit)
        } else {
            noteButton.text = getString(R.string.note_add)
        }
    }

    // ── Collapsible section helpers ───────────────────────────────────────

    private fun toggleSection(body: LinearLayout, arrow: TextView) {
        if (body.visibility == View.GONE) {
            body.visibility = View.VISIBLE
            arrow.text = "▼"
        } else {
            body.visibility = View.GONE
            arrow.text = "▶"
        }
    }

    private fun refreshSectionSummary(summary: TextView, label: String, selected: Set<String>) {
        summary.text = if (selected.isEmpty()) label
        else "$label: ${selected.sorted().joinToString(", ")}"
    }

    // ── Due date helpers ──────────────────────────────────────────────────

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        selectedDueDate?.let {
            try {
                val d = LocalDate.parse(it, DATE_FMT)
                cal.set(d.year, d.monthValue - 1, d.dayOfMonth)
            } catch (_: Exception) {}
        }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                selectedDueDate = DATE_FMT.format(LocalDate.of(year, month + 1, day))
                refreshDueDateUi()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun refreshDueDateUi() {
        if (selectedDueDate != null) {
            dueDateValue.text = selectedDueDate
            dueDateClearButton.visibility = View.VISIBLE
        } else {
            dueDateValue.text = getString(R.string.due_date_none)
            dueDateClearButton.visibility = View.GONE
        }
    }

    // ── Chip helpers ──────────────────────────────────────────────────────

    private fun buildChips(
        group: LinearLayout,
        values: List<String>,
        selected: MutableSet<String>,
        onToggle: (() -> Unit) = {}
    ) {
        group.removeAllViews()
        var row = newRow()
        group.addView(row)

        values.forEach { value ->
            val chip = makeChip(value, value in selected) { isNowSelected ->
                if (isNowSelected) selected.add(value) else selected.remove(value)
                onToggle()
            }
            if (row.childCount >= 4) {
                row = newRow()
                group.addView(row)
            }
            row.addView(chip)
        }
    }

    private fun newRow(): LinearLayout {
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 4)
        }
    }

    private fun makeChip(
        label: String,
        initiallySelected: Boolean,
        onToggle: (Boolean) -> Unit
    ): Button {
        val btn = Button(this)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 8, 0)
        btn.layoutParams = lp
        btn.text = label
        btn.textSize = 12f
        btn.setPadding(24, 8, 24, 8)
        btn.isAllCaps = false

        var selected = initiallySelected
        fun applyStyle() {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = CHIP_CORNER_RADIUS
                setColor(if (selected) CHIP_SELECTED_BG else CHIP_UNSELECTED_BG)
            }
            btn.background = bg
            btn.setTextColor(if (selected) CHIP_SELECTED_TEXT else CHIP_UNSELECTED_TEXT)
        }
        applyStyle()

        btn.setOnClickListener {
            selected = !selected
            applyStyle()
            onToggle(selected)
        }
        return btn
    }

    // ── Save ──────────────────────────────────────────────────────────────

    private fun save(oldText: String?) {
        var raw = taskEditText.text.toString().trim()
        if (raw.isEmpty()) { finish(); return }

        // Strip existing @context, +project, due:, t:, rec:, status: and note: tokens from raw text
        raw = raw.split(' ')
            .filter { word ->
                !word.startsWith('@') && !word.startsWith('+') &&
                !word.startsWith("due:", ignoreCase = true) &&
                !word.startsWith("t:", ignoreCase = true) &&
                !word.startsWith("rec:", ignoreCase = true) &&
                !word.startsWith("status:", ignoreCase = true) &&
                !word.startsWith("note:", ignoreCase = true)
            }
            .joinToString(" ")
            .trim()

        // Apply priority
        raw = raw.replace(Regex("^\\([A-Z]\\) "), "")
        val selectedPrio = prioritySpinner.selectedItemPosition
        if (selectedPrio > 0) {
            val code = Priority.entries.filter { it != Priority.NONE }[selectedPrio - 1].code
            raw = "($code) $raw"
        }

        // Build the ordered list of extra tokens to append
        val parts = mutableListOf<String>()

        // Recurrence — may also auto-set due date
        val recPattern = recurrenceEdit.text.toString().trim()
        if (recPattern.isNotEmpty()) {
            if (selectedDueDate == null) {
                selectedDueDate = Task.addInterval(LocalDate.now().toString(), recPattern)
            }
            parts += "rec:$recPattern"
        }

        selectedDueDate?.let { parts += "due:$it" }
        if (selectedContexts.isNotEmpty()) parts += selectedContexts.sorted().joinToString(" ") { "@$it" }
        if (selectedProjects.isNotEmpty()) parts += selectedProjects.sorted().joinToString(" ") { "+$it" }
        if (frozenSwitch.isChecked)  parts += "status:frozen"
        if (somedaySwitch.isChecked) parts += "status:someday"
        currentNoteId?.let { parts += "note:$it" }

        if (parts.isNotEmpty()) raw = "$raw ${parts.joinToString(" ")}"

        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_TASK_TEXT, raw)
            if (oldText != null) putExtra(EXTRA_OLD_TASK_TEXT, oldText)
        })
        finish()
    }
}
