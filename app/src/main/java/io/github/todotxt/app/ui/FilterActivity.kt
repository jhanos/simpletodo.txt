package io.github.todotxt.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import io.github.todotxt.app.R
import io.github.todotxt.app.model.SortField

class FilterActivity : Activity() {

    companion object {
        const val EXTRA_SORT_FIELD     = "sort_field"
        const val EXTRA_SHOW_FUTURE    = "show_future"
        const val EXTRA_ALL_CONTEXTS   = "all_contexts"
        const val EXTRA_ALL_PROJECTS   = "all_projects"
        const val EXTRA_CONTEXTS       = "contexts"
        const val EXTRA_PROJECTS       = "projects"
        const val EXTRA_FILTER_TEXT    = "filter_text"
    }

    private lateinit var keywordEdit: EditText
    private lateinit var showFutureCheck: CheckBox
    private lateinit var sortSpinner: Spinner
    private lateinit var contextsList: ListView
    private lateinit var projectsList: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_filter)

        keywordEdit       = findViewById(R.id.keywordEdit)
        showFutureCheck   = findViewById(R.id.showFutureCheck)
        sortSpinner       = findViewById(R.id.sortSpinner)
        contextsList      = findViewById(R.id.contextsList)
        projectsList      = findViewById(R.id.projectsList)

        // Sort options
        val sortLabels = listOf(
            getString(R.string.sort_priority),
            getString(R.string.sort_project),
            getString(R.string.sort_context),
            getString(R.string.sort_due),
            getString(R.string.sort_threshold),
            getString(R.string.sort_none)
        )
        sortSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            sortLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // Restore incoming state
        val currentSort     = SortField.valueOf(
            intent.getStringExtra(EXTRA_SORT_FIELD) ?: SortField.PRIORITY.name
        )
        showFutureCheck.isChecked    = intent.getBooleanExtra(EXTRA_SHOW_FUTURE, false)
        keywordEdit.setText(intent.getStringExtra(EXTRA_FILTER_TEXT) ?: "")
        sortSpinner.setSelection(currentSort.ordinal)

        val allContexts  = intent.getStringArrayListExtra(EXTRA_ALL_CONTEXTS) ?: arrayListOf()
        val allProjects  = intent.getStringArrayListExtra(EXTRA_ALL_PROJECTS) ?: arrayListOf()
        val activeCtx    = intent.getStringArrayExtra(EXTRA_CONTEXTS)?.toSet() ?: emptySet()
        val activePrj    = intent.getStringArrayExtra(EXTRA_PROJECTS)?.toSet() ?: emptySet()

        populateList(contextsList, allContexts, activeCtx)
        populateList(projectsList, allProjects, activePrj)

        findViewById<Button>(R.id.applyButton).setOnClickListener { apply(allContexts, allProjects) }
        findViewById<Button>(R.id.clearButton).setOnClickListener { clear(allContexts, allProjects) }
    }

    private fun populateList(lv: ListView, items: List<String>, active: Set<String>) {
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, items)
        items.forEachIndexed { i, v -> lv.setItemChecked(i, v in active) }
        // Fix height so it doesn't scroll inside ScrollView
        lv.post {
            var height = 0
            for (i in 0 until (lv.adapter?.count ?: 0)) {
                val child = lv.adapter.getView(i, null, lv)
                child.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(lv.width, android.view.View.MeasureSpec.AT_MOST),
                    android.view.View.MeasureSpec.UNSPECIFIED
                )
                height += child.measuredHeight + lv.dividerHeight
            }
            lv.layoutParams = lv.layoutParams.also { it.height = height }
            lv.requestLayout()
        }
    }

    private fun apply(allContexts: List<String>, allProjects: List<String>) {
        val sortField = SortField.entries[sortSpinner.selectedItemPosition]
        val selCtx = allContexts.filterIndexed { i, _ -> contextsList.isItemChecked(i) }
        val selPrj = allProjects.filterIndexed { i, _ -> projectsList.isItemChecked(i) }
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_SORT_FIELD,      sortField.name)
            putExtra(EXTRA_SHOW_FUTURE,     showFutureCheck.isChecked)
            putExtra(EXTRA_CONTEXTS,        selCtx.toTypedArray())
            putExtra(EXTRA_PROJECTS,        selPrj.toTypedArray())
            putExtra(EXTRA_FILTER_TEXT,     keywordEdit.text.toString().trim())
        })
        finish()
    }

    private fun clear(allContexts: List<String>, allProjects: List<String>) {
        allContexts.indices.forEach { contextsList.setItemChecked(it, false) }
        allProjects.indices.forEach { projectsList.setItemChecked(it, false) }
        keywordEdit.setText("")
        showFutureCheck.isChecked    = false
        sortSpinner.setSelection(0)
        apply(allContexts, allProjects)
    }
}
