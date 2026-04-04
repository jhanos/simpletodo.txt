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
import android.widget.TextView
import io.github.todotxt.app.R
import io.github.todotxt.app.model.Priority
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar

class AddEditActivity : Activity() {

    companion object {
        const val EXTRA_TASK_TEXT     = "task_text"
        const val EXTRA_OLD_TASK_TEXT = "old_task_text"
        const val EXTRA_ALL_CONTEXTS  = "all_contexts"
        const val EXTRA_ALL_PROJECTS  = "all_projects"

        private val DEFAULT_CONTEXTS = listOf("phone", "mail")

        // Chip colours
        private const val CHIP_SELECTED_BG   = 0xFF1565C0.toInt()  // dark blue
        private const val CHIP_UNSELECTED_BG = 0xFFE0E0E0.toInt()  // light grey
        private const val CHIP_SELECTED_TEXT = Color.WHITE
        private const val CHIP_UNSELECTED_TEXT = Color.BLACK
        private const val CHIP_CORNER_RADIUS = 32f

        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private lateinit var taskEditText: EditText
    private lateinit var prioritySpinner: Spinner
    private lateinit var contextsGroup: LinearLayout
    private lateinit var projectsGroup: LinearLayout
    private lateinit var dueDateValue: TextView
    private lateinit var dueDateClearButton: Button

    // Track selected values
    private val selectedContexts = mutableSetOf<String>()
    private val selectedProjects  = mutableSetOf<String>()
    private var selectedDueDate: String? = null   // "YYYY-MM-DD" or null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_edit)

        taskEditText      = findViewById(R.id.taskEditText)
        prioritySpinner   = findViewById(R.id.prioritySpinner)
        contextsGroup     = findViewById(R.id.contextsChipGroup)
        projectsGroup     = findViewById(R.id.projectsChipGroup)
        dueDateValue      = findViewById(R.id.dueDateValue)
        dueDateClearButton = findViewById(R.id.dueDateClearButton)

        // Priority spinner
        val priorities = mutableListOf(getString(R.string.none))
        priorities.addAll(Priority.entries.filter { it != Priority.NONE }.map { it.code })
        prioritySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            priorities
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val existingText = intent.getStringExtra(EXTRA_TASK_TEXT)

        // Parse existing contexts/projects/due from the task text
        val taskContexts = parseTokens(existingText, '@')
        val taskProjects  = parseTokens(existingText, '+')
        selectedContexts.addAll(taskContexts)
        selectedProjects.addAll(taskProjects)
        selectedDueDate = parseDueDate(existingText)

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

        buildChips(contextsGroup, allContexts, selectedContexts)
        buildChips(projectsGroup, allProjects, selectedProjects)

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
                buildChips(contextsGroup, (allContexts + selectedContexts).distinct().sorted(), selectedContexts)
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
                buildChips(projectsGroup, (allProjects + selectedProjects).distinct().sorted(), selectedProjects)
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
        if (existingText != null) {
            setTitle(R.string.edit_task)
            // Show displayText (without due:, @context, +project) in the edit field
            val displayRaw = stripMetaTokens(existingText)
            taskEditText.setText(displayRaw)
            taskEditText.setSelection(displayRaw.length)
            val prioCode = Regex("(?:^|^x\\s+\\S+\\s+)\\(([A-Z])\\)").find(existingText)
                ?.groupValues?.get(1)
            if (prioCode != null) {
                val idx = priorities.indexOf(prioCode)
                if (idx >= 0) prioritySpinner.setSelection(idx)
            }
        } else {
            setTitle(R.string.add_task)
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener { save(existingText) }
        findViewById<Button>(R.id.cancelButton).setOnClickListener { finish() }
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
        selected: MutableSet<String>
    ) {
        group.removeAllViews()
        var row = newRow()
        group.addView(row)

        values.forEach { value ->
            val chip = makeChip(value, value in selected) { isNowSelected ->
                if (isNowSelected) selected.add(value) else selected.remove(value)
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

        // Strip existing @context, +project and due: tokens from raw text
        raw = raw.split(' ')
            .filter { word ->
                !word.startsWith('@') && !word.startsWith('+') &&
                !word.startsWith("due:", ignoreCase = true)
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

        // Append due date
        if (selectedDueDate != null) {
            raw = "$raw due:$selectedDueDate"
        }

        // Append selected contexts and projects
        if (selectedContexts.isNotEmpty()) {
            raw = raw + " " + selectedContexts.sorted().joinToString(" ") { "@$it" }
        }
        if (selectedProjects.isNotEmpty()) {
            raw = raw + " " + selectedProjects.sorted().joinToString(" ") { "+$it" }
        }

        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_TASK_TEXT, raw)
            if (oldText != null) putExtra(EXTRA_OLD_TASK_TEXT, oldText)
        })
        finish()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Extract @context or +project values from a raw task string. */
    private fun parseTokens(text: String?, prefix: Char): Set<String> {
        if (text == null) return emptySet()
        return text.split(' ')
            .filter { it.startsWith(prefix) && it.length > 1 }
            .map { it.substring(1).lowercase() }
            .toSet()
    }

    /** Extract the due:YYYY-MM-DD value from a raw task string, or null. */
    private fun parseDueDate(text: String?): String? {
        if (text == null) return null
        return text.split(' ')
            .firstOrNull { it.startsWith("due:", ignoreCase = true) && it.length > 4 }
            ?.substring(4)
    }

    /**
     * Return the task text with @context, +project, and due: tokens removed —
     * used to pre-fill the edit field so the user only edits the plain description.
     */
    private fun stripMetaTokens(text: String): String =
        text.split(' ')
            .filter { word ->
                !word.startsWith('@') && !word.startsWith('+') &&
                !word.startsWith("due:", ignoreCase = true)
            }
            .joinToString(" ")
            .trim()
}
