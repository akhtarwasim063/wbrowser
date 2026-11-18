package dev.wbrowser.nativeapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

data class HistoryEntry(
    val id: String,
    val url: String,
    val title: String,
    val visitedAt: Long,
    val deleted: Boolean = false,
    val synced: Boolean = false,
)

/** Local browsing history and bookmarks (SQLite). Admin-visible activity is handled separately by Tracker/ActivityDb. */
object HistoryDb {
    private lateinit var helper: SQLiteOpenHelper
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()

    fun init(ctx: Context) {
        helper = object : SQLiteOpenHelper(ctx, "history_native.db", null, 2) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE history(id TEXT PRIMARY KEY, url TEXT NOT NULL, title TEXT," +
                        "visited_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0," +
                        "synced INTEGER NOT NULL DEFAULT 0)"
                )
                createBookmarks(db)
            }
            override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
                if (o < 2) createBookmarks(db)
            }
            private fun createBookmarks(db: SQLiteDatabase) = db.execSQL(
                "CREATE TABLE IF NOT EXISTS bookmarks(url TEXT PRIMARY KEY, title TEXT, created INTEGER NOT NULL)"
            )
        }
    }

    // ---- bookmarks (local) ----
    fun isBookmarked(url: String, cb: (Boolean) -> Unit) = io.execute {
        val r = helper.readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url=?", arrayOf(url)).use { it.moveToFirst() }
        main.post { cb(r) }
    }

    fun toggleBookmark(url: String, title: String, cb: (Boolean) -> Unit) = io.execute {
        val db = helper.writableDatabase
        val exists = db.rawQuery("SELECT 1 FROM bookmarks WHERE url=?", arrayOf(url)).use { it.moveToFirst() }
        if (exists) db.delete("bookmarks", "url=?", arrayOf(url))
        else db.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("url", url); put("title", title); put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        main.post { cb(!exists) }
    }

    fun removeBookmark(url: String) = io.execute {
        helper.writableDatabase.delete("bookmarks", "url=?", arrayOf(url))
        notifyChanged()
    }

    fun listBookmarks(query: String, cb: (List<HistoryEntry>) -> Unit) = io.execute {
        val q = query.trim()
        val sql = if (q.isEmpty()) "SELECT url,title,created FROM bookmarks ORDER BY created DESC"
        else "SELECT url,title,created FROM bookmarks WHERE url LIKE ? OR title LIKE ? ORDER BY created DESC"
        val args = if (q.isEmpty()) null else arrayOf("%$q%", "%$q%")
        val out = helper.readableDatabase.rawQuery(sql, args).use { c ->
            val l = ArrayList<HistoryEntry>()
            while (c.moveToNext()) l += HistoryEntry(c.getString(0), c.getString(0), c.getString(1) ?: "", c.getLong(2), synced = false)
            l
        }
        main.post { cb(out) }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun notifyChanged() = main.post { listeners.toList().forEach { it() } }

    fun addVisit(url: String, title: String) {
        if (!url.startsWith("http")) return
        io.execute {
            val db = helper.writableDatabase
            db.rawQuery("SELECT id,url,title FROM history WHERE deleted=0 ORDER BY visited_at DESC LIMIT 1", null).use { c ->
                if (c.moveToFirst() && c.getString(1) == url) {
                    val cv = ContentValues().apply {
                        if (title.isNotEmpty()) put("title", title)
                        put("synced", 0)
                    }
                    db.update("history", cv, "id=?", arrayOf(c.getString(0)))
                    notifyChanged()
                    return@execute
                }
            }
            val now = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put("id", "${now}_${url.hashCode().toUInt()}")
                put("url", url); put("title", title)
                put("visited_at", now); put("deleted", 0); put("synced", 0)
            }
            db.insertWithOnConflict("history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            notifyChanged()
        }
    }

    fun list(query: String, cb: (List<HistoryEntry>) -> Unit) {
        io.execute {
            val q = query.trim()
            val sql = if (q.isEmpty())
                "SELECT * FROM history WHERE deleted=0 ORDER BY visited_at DESC LIMIT 500"
            else "SELECT * FROM history WHERE deleted=0 AND (url LIKE ? OR title LIKE ?) ORDER BY visited_at DESC LIMIT 500"
            val args = if (q.isEmpty()) null else arrayOf("%$q%", "%$q%")
            val out = helper.readableDatabase.rawQuery(sql, args).use { readAll(it) }
            main.post { cb(out) }
        }
    }

    fun delete(id: String) = io.execute {
        helper.writableDatabase.update("history", ContentValues().apply { put("deleted", 1); put("synced", 0) }, "id=?", arrayOf(id))
        notifyChanged()
    }

    fun clearAll() = io.execute {
        helper.writableDatabase.update("history", ContentValues().apply { put("deleted", 1); put("synced", 0) }, "deleted=0", null)
        notifyChanged()
    }

    fun unsynced(): List<HistoryEntry> =
        helper.readableDatabase.rawQuery("SELECT * FROM history WHERE synced=0", null).use { readAll(it) }

    fun markSynced(ids: Collection<String>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.update("history", ContentValues().apply { put("synced", 1) }, "id=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun upsertFromCloud(e: HistoryEntry) {
        val cv = ContentValues().apply {
            put("id", e.id); put("url", e.url); put("title", e.title)
            put("visited_at", e.visitedAt); put("deleted", if (e.deleted) 1 else 0); put("synced", 1)
        }
        helper.writableDatabase.insertWithOnConflict("history", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun runIo(block: () -> Unit) = io.execute(block)

    private fun readAll(c: android.database.Cursor): List<HistoryEntry> {
        val out = ArrayList<HistoryEntry>()
        val id = c.getColumnIndexOrThrow("id"); val url = c.getColumnIndexOrThrow("url")
        val title = c.getColumnIndexOrThrow("title"); val v = c.getColumnIndexOrThrow("visited_at")
        val d = c.getColumnIndexOrThrow("deleted"); val s = c.getColumnIndexOrThrow("synced")
        while (c.moveToNext()) {
            out += HistoryEntry(c.getString(id), c.getString(url), c.getString(title) ?: "",
                c.getLong(v), c.getInt(d) == 1, c.getInt(s) == 1)
        }
        return out
    }
}
