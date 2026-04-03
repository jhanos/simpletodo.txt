package io.github.todotxt.app.ui

import android.content.Context
import android.graphics.Paint
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

class TaskAdapter(
    private val context: Context,
    private val onToggleComplete: (taskItem: TaskItem) -> Unit,
    private val onEdit: (taskItem: TaskItem) -> Unit,
    private val onDelete: (taskItem: TaskItem) -> Unit
) : BaseAdapter() {

    private val ITEM_TYPE_HEADER = 0
    private val ITEM_TYPE_TASK   = 1

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
        val inflater = LayoutInflater.from(context)
        return when (val item = items[position]) {
            is HeaderItem -> {
                val view = convertView
                    ?: inflater.inflate(R.layout.item_header, parent, false)
                view.findViewById<TextView>(R.id.headerText).text = item.title
                view
            }
            is TaskItem -> {
                val view = convertView
                    ?: inflater.inflate(R.layout.item_task, parent, false)
                val task = item.task

                val taskText     = view.findViewById<TextView>(R.id.taskText)
                val priorityBadge= view.findViewById<TextView>(R.id.priorityBadge)
                val badgeSpacer  = view.findViewById<Space>(R.id.badgeSpacer)
                val dueDateText  = view.findViewById<TextView>(R.id.dueDateText)
                val completedCheck = view.findViewById<CheckBox>(R.id.completedCheck)

                // Build display text: strip priority/dates from visible text
                taskText.text = task.text

                // Strike-through when completed
                taskText.paintFlags = if (task.completed)
                    taskText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                else
                    taskText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

                // Linkify URLs and phone numbers; pass non-link taps through to row
                Linkify.addLinks(taskText, Linkify.WEB_URLS or Linkify.PHONE_NUMBERS)
                taskText.movementMethod = PassthroughLinkMovementMethod

                // Priority badge
                if (task.priority != Priority.NONE) {
                    priorityBadge.text = task.priority.code
                    priorityBadge.visibility = View.VISIBLE
                    badgeSpacer.visibility = View.VISIBLE
                } else {
                    priorityBadge.visibility = View.GONE
                    badgeSpacer.visibility = View.GONE
                }

                // Due date
                val due = task.dueDate
                if (due != null) {
                    dueDateText.text = "due: $due"
                    dueDateText.visibility = View.VISIBLE
                } else {
                    dueDateText.visibility = View.GONE
                }

                // Completion checkbox
                completedCheck.isChecked = task.completed

                // Single tap anywhere on the row = edit; long-press = context menu
                view.setOnClickListener { onEdit(item) }
                view.setOnLongClickListener {
                    showContextMenu(item)
                    true
                }
                completedCheck.setOnClickListener { onToggleComplete(item) }

                view
            }
        }
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
