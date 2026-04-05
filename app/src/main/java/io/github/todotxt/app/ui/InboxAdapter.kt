package io.github.todotxt.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView
import io.github.todotxt.app.R
import io.github.todotxt.app.model.InboxItem

private class InboxViewHolder(view: View) {
    val titleText: TextView  = view.findViewById(R.id.inboxItemTitle)
    val descText: TextView   = view.findViewById(R.id.inboxItemDesc)
    val editButton: Button   = view.findViewById(R.id.inboxEditButton)
}

class InboxAdapter(
    private val context: Context,
    private val items: List<InboxItem>,
    private val onEdit: (index: Int) -> Unit
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view: View
        val holder: InboxViewHolder
        if (convertView == null) {
            view = inflater.inflate(R.layout.item_inbox, parent, false)
            holder = InboxViewHolder(view)
            view.tag = holder
        } else {
            view = convertView
            holder = view.tag as InboxViewHolder
        }

        val item = items[position]
        holder.titleText.text = item.title

        if (item.description.isNotEmpty()) {
            holder.descText.text = item.description
            holder.descText.visibility = View.VISIBLE
        } else {
            holder.descText.visibility = View.GONE
        }

        holder.editButton.setOnClickListener { onEdit(position) }
        view.setOnClickListener { onEdit(position) }

        return view
    }
}
