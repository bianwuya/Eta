package io.github.mangi.eta.local.subagentstats

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Separate bounded diagnostics DB: no changes to conversation/credential storage or backups. */
internal class SubagentStatsStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "subagent-request-stats.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE requests (id TEXT PRIMARY KEY NOT NULL, conversation_id TEXT NOT NULL, " +
            "started_at INTEGER NOT NULL, record_json TEXT NOT NULL)")
        db.execSQL("CREATE INDEX requests_started ON requests(started_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun save(record: SubagentRequestStat) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("requests", null, ContentValues().apply {
                put("id", record.id)
                put("conversation_id", record.conversationId)
                put("started_at", record.startedAt)
                put("record_json", json.encodeToString(record))
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL("DELETE FROM requests WHERE id NOT IN " +
                "(SELECT id FROM requests ORDER BY started_at DESC, id DESC LIMIT 5000)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun records(): List<SubagentRequestStat> = readableDatabase.query(
        "requests", arrayOf("record_json"), null, null, null, null, "started_at DESC, id DESC", "5000",
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(json.decodeFromString<SubagentRequestStat>(cursor.getString(0)))
    } }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        @Volatile private var instance: SubagentStatsStore? = null
        fun get(context: Context): SubagentStatsStore = instance ?: synchronized(this) {
            instance ?: SubagentStatsStore(context).also { instance = it }
        }
        fun export(records: List<SubagentRequestStat>): String = json.encodeToString(records)
    }
}
