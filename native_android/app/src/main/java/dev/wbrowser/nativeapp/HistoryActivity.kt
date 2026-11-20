package dev.wbrowser.nativeapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.text.DateFormat
import java.util.Date

/** One list screen for both History and Bookmarks (extra "mode"). */
class HistoryActivity : AppCompatActivity() {
    private var items: List<HistoryEntry> = emptyList()
    private var query = ""
    private lateinit var empty: TextView
    private val bookmarks get() = intent.getStringExtra("mode") == "bookmarks"
    private val refresh: () -> Unit = { load() }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            object : RecyclerView.ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_list, p, false)) {}

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val e = items[i]
            val v = h.itemView
            val name = e.title.ifEmpty { Uri.parse(e.url).host ?: e.url }
            v.findViewById<TextView>(R.id.avatar).text = name.trim().firstOrNull()?.uppercase() ?: "?"
            v.findViewById<TextView>(R.id.itemTitle).text = name
            val host = Uri.parse(e.url).host?.removePrefix("www.") ?: e.url
            val whenStr = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.visitedAt))
            v.findViewById<TextView>(R.id.itemSub).text =
                if (bookmarks) host else (if (e.synced) "☁ " else "") + host + "  ·  " + whenStr
            v.findViewById<ImageView>(R.id.itemDelete).setOnClickListener {
                if (bookmarks) HistoryDb.removeBookmark(e.url) else HistoryDb.delete(e.id)
                if (bookmarks) load()
            }
            v.setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra("open", e.url)); finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        tb.title = if (bookmarks) "Bookmarks" else "History"
        tb.setNavigationOnClickListener { finish() }
        if (!bookmarks) {
            // Text action, not an icon: a plain icon here does not pick up a theme tint and used to
            // disappear against a dark toolbar in dark mode.
            tb.menu.add("Clear all").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS or android.view.MenuItem.SHOW_AS_ACTION_WITH_TEXT)
            tb.setOnMenuItemClickListener {
                SheetBuilder(this).setTitle("Clear all history?").danger()
                    .setMessage("This also removes it from your synced devices.")
                    .setPositiveButton("Clear") { _, _ -> HistoryDb.clearAll() }
                    .setNegativeButton("Cancel", null).show()
                true
            }
        }
        empty = findViewById(R.id.empty)
        empty.text = if (bookmarks) "No bookmarks yet" else "No history"
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = this@HistoryActivity.adapter
        }
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s.toString(); load() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        HistoryDb.addListener(refresh)
        load()
    }

    private fun load() {
        val cb: (List<HistoryEntry>) -> Unit = { l ->
            items = l
            adapter.notifyDataSetChanged()
            empty.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
        }
        if (bookmarks) HistoryDb.listBookmarks(query, cb) else HistoryDb.list(query, cb)
    }

    override fun onDestroy() { HistoryDb.removeListener(refresh); super.onDestroy() }
}
