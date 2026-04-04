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
}

class TaskAdapter(
    private val context: Context,
    private val onToggleComplete: (taskItem: TaskItem) -> Unit,
    private val onEdit: (taskItem: TaskItem) -> Unit,
    private val onDelete: (taskItem: TaskItem) -> Unit
) : BaseAdapter() {

    companion object {
        private const val ITEM_TYPE_HEADER = 0
        private const val ITEM_TYPE_TASK   = 1
    }

    private val inflater = LayoutInflater.from(context)
    private var items: List<VisibleItem> = emptyList()

    fun setItems(newItems: List<VisibleItem>) {
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

                // Strike-through when completed
                holder.taskText.paintFlags = if (task.completed)
                    holder.taskText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                else
                    holder.taskText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

                // Linkify URLs and phone numbers; pass non-link taps through to row
                Linkify.addLinks(holder.taskText, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS)
                holder.taskText.movementMethod = PassthroughLinkMovementMethod

                // Context (@) and project (+) tag pills — no prefix in the label
                val tags = task.contexts.map { it to false } +
                           task.projects.map { it to true }
                if (tags.isEmpty()) {
                    holder.tagsRow.visibility = View.GONE
                } else {
                    holder.tagsRow.removeAllViews()
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
                    val today = LocalDate.now()
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
                        days == 0L -> 0xFFE65100.toInt()
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

                // Tap anywhere on the row = toggle completion; pencil = edit; long-press = context menu
                view.setOnClickListener { onToggleComplete(item) }
                view.setOnLongClickListener {
                    showContextMenu(item)
                    true
                }
                holder.editButton.setOnClickListener { onEdit(item) }

                view
            }
        }
    }

    private fun makeTagPill(label: String, isProject: Boolean): TextView {
        val tv = TextView(context)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 6, 0)
        tv.layoutParams = lp
        tv.text = label
        tv.textSize = 10f
        tv.setTextColor(Color.WHITE)
        tv.setPadding(10, 2, 10, 2)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f
            setColor(if (isProject) 0xFF2E7D32.toInt() else 0xFF1565C0.toInt())
        }
        tv.background = bg
        return tv
    }

    private fun showContextMenu(item: TaskItem) {
        val options = arrayOf(
            context.getString(R.string.edit_task),
            context.getString(R.string.delete)
        )
        android.app.AlertDialog.Builder(context)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> onEdit(item)
                    1 -> onDelete(item)
                }
            }
            .show()
    }
}
