package dev.wbrowser.nativeapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Durable offline queue ("outbox") of activity events waiting for upload.
 * Rows survive process death and offline periods; SyncWorker deletes them only after the server acknowledged them.
 * Events carry stable IDs, so re-sending (retry) or re-queuing a checkpoint is idempotent on the server.
 */
object ActivityDb {
    private const val MAX_ROWS = 20_000
    private lateinit var helper: SQLiteOpenHelper
    private val io = Executors.newSingleThreadExecutor()

    data class Row(val seq: Long, val json: JSONObject)

    fun init(ctx: Context) {
        helper = object : SQLiteOpenHelper(ctx, "activity_outbox.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE outbox(seq INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE, type TEXT NOT NULL, json TEXT NOT NULL, created INTEGER NOT NULL)")
            }
            override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
        }
    }

    /** Queues (or replaces, for checkpoints of the same visit/session) one event. Never blocks the UI thread. */
    fun enqueue(event: JSONObject) {
        io.execute {
            val db = helper.writableDatabase
            db.insertWithOnConflict("outbox", null, ContentValues().apply {
                put("event_id", event.getString("id")); put("type", event.getString("type"))
                put("json", event.toString()); put("created", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            // bound storage during very long offline periods: drop the oldest
            db.execSQL("DELETE FROM outbox WHERE seq <= (SELECT MAX(seq) FROM outbox) - $MAX_ROWS")
            SyncScheduler.kick()
        }
    }

    fun peek(limit: Int): List<Row> =
        helper.readableDatabase.rawQuery("SELECT seq,json FROM outbox ORDER BY seq LIMIT ?", arrayOf(limit.toString())).use { c ->
            val out = ArrayList<Row>()
            while (c.moveToNext()) out += Row(c.getLong(0), JSONObject(c.getString(1)))
            out
        }

    fun delete(seqs: Collection<Long>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try { seqs.forEach { db.delete("outbox", "seq=?", arrayOf(it.toString())) }; db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    fun count(): Int = helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use { it.moveToFirst(); it.getInt(0) }

    fun clear() = io.execute { helper.writableDatabase.delete("outbox", null, null) }
    fun purgeSearches() = io.execute { helper.writableDatabase.delete("outbox", "type='search'", null) }
}
