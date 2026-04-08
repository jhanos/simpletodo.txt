package io.github.todotxt.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import io.github.todotxt.app.R
import android.graphics.Typeface
import io.github.todotxt.app.model.HeaderItem
import io.github.todotxt.app.model.Priority
import io.github.todotxt.app.model.TaskItem
import io.github.todotxt.app.model.VisibleItem

/**
 * A LinkMovementMethod that only consumes touch events when the user actually
 * taps on a link span. Non-link taps are passed through to the parent view's
 * onClickListener (i.e. the row tap → edit).
 */
private object PassthroughLinkMovementMethod : LinkMovementMethod() {
    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val action = event.action
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
            var x = event.x.toInt()
            var y = event.y.toInt()
            x -= widget.totalPaddingLeft
            y -= widget.totalPaddingTop
            x += widget.scrollX
            y += widget.scrollY
            val layout = widget.layout
            val line = layout.getLineForVertical(y)
            val off = layout.getOffsetForHorizontal(line, x.toFloat())
            val links = buffer.getSpans(off, off, URLSpan::class.java)
            if (links.isNotEmpty()) {
                return super.onTouchEvent(widget, buffer, event)
            }
        }
        // No link under finger — do not consume, let the row handle it
        return false
    }
}

private class TaskViewHolder(view: View) {
    val taskText: TextView       = view.findViewById(R.id.taskText)
    val priorityBadge: TextView  = view.findViewById(R.id.priorityBadge)
    val badgeSpacer: Space       = view.findViewById(R.id.badgeSpacer)
    val dueDateText: TextView    = view.findViewById(R.id.dueDateText)
    val completedCheck: CheckBox = view.findViewById(R.id.completedCheck)
    val editButton: TextView     = view.findViewById(R.id.editButton)
    val tagsRow: LinearLayout    = view.findViewById(R.id.tagsRow)
    /** Last text set on taskText — used to skip redundant Linkify runs. */
    var lastLinkifiedText: String = ""
}

class TaskAdapter(
    private val context: Context,
    private val onToggleComplete: (taskItem: TaskItem) -> Unit,
    private val onEdit: (taskItem: TaskItem) -> Unit,
    private val onDelete: (taskItem: TaskItem) -> Unit,
    private val onToggleFreeze: (taskItem: TaskItem) -> Unit,
    private val onToggleSomeday: (taskItem: TaskItem) -> Unit = {}
) : BaseAdapter() {

    companion object {
        private const val ITEM_TYPE_HEADER = 0
        private const val ITEM_TYPE_TASK   = 1

        // Badge / text colours
        private const val COLOR_SOMEDAY_TEXT    = 0xFF795548.toInt()  // brown-muted
        private const val COLOR_NORMAL_TEXT     = 0xFFEEEEEE.toInt()
        private const val COLOR_CONTEXT_BADGE   = 0xFF1565C0.toInt()  // dark blue
        private const val COLOR_PROJECT_BADGE   = 0xFF2E7D32.toInt()  // dark green
        private const val COLOR_RECURRING_BADGE = 0xFF00897B.toInt()  // teal
        private const val COLOR_SOMEDAY_BADGE   = 0xFFFF6F00.toInt()  // amber
        private const val COLOR_FROZEN_BADGE    = 0xFF607D8B.toInt()  // blue-grey
        private const val COLOR_DUE_TODAY       = 0xFFE65100.toInt()  // deep orange
    }

    private val inflater = LayoutInflater.from(context)
    private var items: List<VisibleItem> = emptyList()
    // Computed once per setItems call — avoids repeated LocalDate.now() in getView
    private var today: LocalDate = LocalDate.now()

    fun setItems(newItems: List<VisibleItem>) {
        today = LocalDate.now()
        items = newItems
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int =
        if (items[position] is HeaderItem) ITEM_TYPE_HEADER else ITEM_TYPE_TASK
    override fun isEnabled(position: Int): Boolean = items[position] is TaskItem

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return when (val item = items[position]) {
            is HeaderItem -> {
                val view = convertView
                    ?: inflater.inflate(R.layout.item_header, parent, false)
                view.findViewById<TextView>(R.id.headerText).text = item.title
                view
            }
            is TaskItem -> {
                val view: View
                val holder: TaskViewHolder
                if (convertView == null) {
                    view = inflater.inflate(R.layout.item_task, parent, false)
                    holder = TaskViewHolder(view)
                    view.tag = holder
                } else {
                    view = convertView
                    holder = view.tag as TaskViewHolder
                }
                val task = item.task

                // Build display text (contexts and projects shown as pills below)
                holder.taskText.text = task.displayText

                // Frozen: grey italic; Someday: muted amber; normal otherwise
                when {
                    task.isFrozen -> {
                        holder.taskText.setTextColor(Color.GRAY)
                        holder.taskText.setTypeface(null, Typeface.ITALIC)
                    }
                    task.isSomeday -> {
                        holder.taskText.setTextColor(COLOR_SOMEDAY_TEXT)
                        holder.taskText.setTypeface(null, Typeface.NORMAL)
                    }
                    else -> {
                        holder.taskText.setTextColor(COLOR_NORMAL_TEXT)
                        holder.taskText.setTypeface(null, Typeface.NORMAL)
                    }
                }

                // Strike-through when completed
                holder.taskText.paintFlags = if (task.completed)
                    holder.taskText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                else
                    holder.taskText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

                // Linkify URLs and phone numbers; skip if text hasn't changed to avoid
                // redundant work when the ListView recycles a view for the same task.
                if (holder.lastLinkifiedText != task.displayText) {
                    Linkify.addLinks(holder.taskText, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS)
                    holder.lastLinkifiedText = task.displayText
                }
                holder.taskText.movementMethod = PassthroughLinkMovementMethod

                // Context (@) and project (+) tag pills — no prefix in the label
                val tags = task.contexts.map { it to false } +
                           task.projects.map { it to true }
                val showTags = tags.isNotEmpty() || task.isFrozen || task.isSomeday || task.recurrencePattern != null
                if (!showTags) {
                    holder.tagsRow.visibility = View.GONE
                } else {
                    holder.tagsRow.removeAllViews()
                    if (task.isFrozen) holder.tagsRow.addView(makeFrozenBadge())
                    if (task.isSomeday) holder.tagsRow.addView(makeSomedayBadge())
                    task.recurrencePattern?.let { holder.tagsRow.addView(makeRecurringBadge(it)) }
                    tags.forEach { (label, isProject) ->
                        holder.tagsRow.addView(makeTagPill(label, isProject))
                    }
                    holder.tagsRow.visibility = View.VISIBLE
                }

                // Priority badge
                if (task.priority != Priority.NONE) {
                    holder.priorityBadge.text = task.priority.code
                    holder.priorityBadge.visibility = View.VISIBLE
                    holder.badgeSpacer.visibility = View.VISIBLE
                } else {
                    holder.priorityBadge.visibility = View.GONE
                    holder.badgeSpacer.visibility = View.GONE
                }

                // Due date
                val due = task.dueDate
                if (due != null) {
                    val dueDate = LocalDate.parse(due)
                    val days = ChronoUnit.DAYS.between(today, dueDate)
                    val label = when {
                        days < 0  -> "due: $due (${-days}d overdue)"
                        days == 0L -> "due: $due (today)"
                        days == 1L -> "due: $due (tomorrow)"
                        else       -> "due: $due (in ${days}d)"
                    }
                    val color = when {
                        days < 0  -> Color.RED
                        days == 0L -> COLOR_DUE_TODAY
                        else       -> Color.DKGRAY
                    }
                    holder.dueDateText.text = label
                    holder.dueDateText.setTextColor(color)
                    holder.dueDateText.visibility = View.VISIBLE
                } else {
                    holder.dueDateText.visibility = View.GONE
                }

                // Completion checkbox (state indicator only)
                holder.completedCheck.isChecked = task.completed

                // Tap anywhere on the row = toggle completion (or edit if frozen/someday); pencil = edit; long-press = context menu
                view.setOnClickListener {
                    if (task.isFrozen || task.isSomeday) onEdit(item) else onToggleComplete(item)
                }
                view.setOnLongClickListener {
                    showContextMenu(item)
                    true
                }
                holder.editButton.setOnClickListener { onEdit(item) }
                holder.editButton.alpha = 1f

                view
            }
        }
    }

    private fun makeTagPill(label: String, isProject: Boolean): TextView =
        makeBadge(label, if (isProject) COLOR_PROJECT_BADGE else COLOR_CONTEXT_BADGE)

    private fun makeFrozenBadge(): TextView =
        makeBadge(context.getString(R.string.frozen_badge), COLOR_FROZEN_BADGE)

    private fun makeSomedayBadge(): TextView =
        makeBadge(context.getString(R.string.someday_badge), COLOR_SOMEDAY_BADGE)

    private fun makeRecurringBadge(pattern: String): TextView =
        makeBadge(context.getString(R.string.rec_badge) + " $pattern", COLOR_RECURRING_BADGE)

    /** Single factory for all badge/pill views — only label and background colour differ. */
    private fun makeBadge(label: String, color: Int): TextView {
        val tv = TextView(context)
        tv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(0, 0, 6, 0) }
        tv.text = label
        tv.textSize = 10f
        tv.setTextColor(Color.WHITE)
        tv.setPadding(10, 2, 10, 2)
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f
            setColor(color)
        }
        return tv
    }

    private fun showContextMenu(item: TaskItem) {
        val task = item.task
        val freezeLabel = if (task.isFrozen)
            context.getString(R.string.unfreeze_task)
        else
            context.getString(R.string.freeze_task)
        val somedayLabel = if (task.isSomeday)
            context.getString(R.string.unsomeday_task)
        else
            context.getString(R.string.someday_task)

        val options: Array<String>
        if (task.isFrozen) {
            // Frozen: edit, unfreeze (no delete, no someday toggle)
            options = arrayOf(
                context.getString(R.string.edit_task),
                freezeLabel
            )
        } else if (task.isSomeday) {
            // Someday: edit, remove-someday, freeze (no delete while someday)
            options = arrayOf(
                context.getString(R.string.edit_task),
                somedayLabel,
                freezeLabel
            )
        } else {
            // Normal: edit, delete, freeze, mark-someday
            options = arrayOf(
                context.getString(R.string.edit_task),
                context.getString(R.string.delete),
                freezeLabel,
                somedayLabel
            )
        }

        android.app.AlertDialog.Builder(context)
            .setItems(options) { _, which ->
                when {
                    task.isFrozen -> when (which) {
                        0 -> onEdit(item)
                        1 -> onToggleFreeze(item)
                    }
                    task.isSomeday -> when (which) {
                        0 -> onEdit(item)
                        1 -> onToggleSomeday(item)
                        2 -> onToggleFreeze(item)
                    }
                    else -> when (which) {
                        0 -> onEdit(item)
                        1 -> onDelete(item)
                        2 -> onToggleFreeze(item)
                        3 -> onToggleSomeday(item)
                    }
                }
            }
            .show()
    }
}
