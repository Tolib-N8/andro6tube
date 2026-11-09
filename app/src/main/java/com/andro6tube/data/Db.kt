package com.andro6tube.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** История и «Смотреть позже» на чистом SQLite — без лишних библиотек. */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context, "andro6tube.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        for (t in listOf(HISTORY, LATER)) {
            db.execSQL(
                """CREATE TABLE $t (
                    url TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    uploader TEXT NOT NULL,
                    duration INTEGER NOT NULL,
                    position INTEGER NOT NULL DEFAULT 0,
                    ts INTEGER NOT NULL)"""
            )
            db.execSQL("CREATE INDEX ${t}_ts ON $t(ts DESC)")
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- История ----

    fun addHistory(v: Video) {
        val old = position(v.url)
        upsert(HISTORY, v.copy(positionMs = old))
        // Ограничиваем размер истории
        writableDatabase.execSQL(
            "DELETE FROM $HISTORY WHERE url NOT IN (SELECT url FROM $HISTORY ORDER BY ts DESC LIMIT 500)"
        )
    }

    fun savePosition(url: String, positionMs: Long) {
        val cv = ContentValues().apply { put("position", positionMs) }
        writableDatabase.update(HISTORY, cv, "url=?", arrayOf(url))
    }

    fun position(url: String): Long =
        readableDatabase.rawQuery("SELECT position FROM $HISTORY WHERE url=?", arrayOf(url)).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    fun history(): List<Video> = list(HISTORY)
    fun removeHistory(url: String) = writableDatabase.delete(HISTORY, "url=?", arrayOf(url))
    fun clearHistory() = writableDatabase.delete(HISTORY, null, null)

    // ---- Смотреть позже ----

    fun addLater(v: Video) = upsert(LATER, v.copy(positionMs = 0))
    fun removeLater(url: String) = writableDatabase.delete(LATER, "url=?", arrayOf(url))
    fun isLater(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM $LATER WHERE url=?", arrayOf(url)).use { it.moveToFirst() }

    /** Очередь «позже» — в порядке добавления (старые первыми). */
    fun later(): List<Video> = list(LATER, "ts ASC")

    private fun upsert(table: String, v: Video) {
        val cv = ContentValues().apply {
            put("url", v.url)
            put("title", v.title)
            put("uploader", v.uploader)
            put("duration", v.durationSec)
            put("position", v.positionMs)
            put("ts", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(table, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun list(table: String, order: String = "ts DESC"): List<Video> =
        readableDatabase.rawQuery(
            "SELECT url,title,uploader,duration,position FROM $table ORDER BY $order", null
        ).use { c -> buildList { while (c.moveToNext()) add(c.toVideo()) } }

    private fun Cursor.toVideo() = Video(
        url = getString(0),
        title = getString(1),
        uploader = getString(2),
        durationSec = getLong(3),
        positionMs = getLong(4),
    )

    companion object {
        private const val HISTORY = "history"
        private const val LATER = "later"

        @Volatile
        private var inst: Db? = null

        fun get(context: Context): Db =
            inst ?: synchronized(this) { inst ?: Db(context.applicationContext).also { inst = it } }
    }
}
