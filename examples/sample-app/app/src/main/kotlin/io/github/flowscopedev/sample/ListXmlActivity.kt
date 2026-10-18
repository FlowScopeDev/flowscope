package io.github.flowscopedev.sample

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.flowscope.agent.FlowScopeAgent

/** A long RecyclerView: scrolling, selectors on repeated rows, and navigation to a detail screen. */
class ListXmlActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list_xml)
        applySystemBarInsets()
        findViewById<RecyclerView>(R.id.item_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ItemAdapter { item ->
                FlowScopeAgent.onSemanticAction(this@ListXmlActivity, "select", "item_${item.id}", "list-xml")
                startActivity(
                    Intent(this@ListXmlActivity, DetailXmlActivity::class.java)
                        .putExtra(DetailXmlActivity.EXTRA_ID, item.id),
                )
            }
        }
    }

    private class ItemAdapter(val onClick: (Demo.Item) -> Unit) : RecyclerView.Adapter<ItemAdapter.Holder>() {
        class Holder(view: android.view.View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.row_title)
            val subtitle: TextView = view.findViewById(R.id.row_subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false))

        override fun getItemCount() = Demo.items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = Demo.items[position]
            holder.title.text = item.title
            holder.subtitle.text = item.body
            holder.itemView.contentDescription = "Open ${item.title}"
            holder.itemView.setOnClickListener { onClick(item) }
        }
    }
}
