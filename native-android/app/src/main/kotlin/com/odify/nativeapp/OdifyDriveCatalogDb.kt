package com.odify.nativeapp

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable catalog for tens of thousands of Drive files; local legacy audio
 * remains in its previous SharedPreferences store. All snapshot changes are
 * one SQLite transaction so a failed sync never partially deletes the catalog.
 */
class OdifyDriveCatalogDb(context: Context) :
    SQLiteOpenHelper(context, "odify_drive_catalog_v8.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE music (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL,
            album TEXT NOT NULL, genre TEXT NOT NULL, lyrics TEXT NOT NULL,
            source_uri TEXT NOT NULL, modified TEXT NOT NULL,
            artwork_path TEXT NOT NULL DEFAULT '', user_edit INTEGER NOT NULL DEFAULT 0,
            hidden INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("""CREATE VIRTUAL TABLE music_fts USING fts4(
            id, title, artist, album, genre, lyrics, tokenize=unicode61
        )""")
        db.execSQL("CREATE INDEX music_genre ON music(genre)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No destructive migration is permitted for Odify catalog.")
    }

    private fun row(c: Cursor) = OdifySong(
        id = c.getString(0),
        title = c.getString(1),
        artist = c.getString(2),
        album = c.getString(3),
        genre = c.getString(4),
        lyrics = c.getString(5),
        filePath = "",
        sourceUri = c.getString(6),
        driveVersion = c.getString(7),
        artworkPath = c.getString(8).takeIf { it.isNotBlank() }
    )

    fun all(): List<OdifySong> {
        val result = mutableListOf<OdifySong>()
        readableDatabase.rawQuery("""SELECT id,title,artist,album,genre,lyrics,
            source_uri,modified,artwork_path FROM music
            WHERE hidden=0 ORDER BY title COLLATE NOCASE""", null).use { c ->
            while (c.moveToNext()) result.add(row(c))
        }
        return result
    }

    fun search(text: String, genre: String = ""): List<OdifySong> {
        val terms = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .take(8).map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }
            .filter { it.isNotBlank() }
        val results = mutableListOf<OdifySong>()
        if (terms.isEmpty()) {
            readableDatabase.rawQuery("""SELECT id,title,artist,album,genre,lyrics,
                source_uri,modified,artwork_path FROM music
                WHERE hidden=0 AND (?='' OR genre=?) LIMIT 500""",
                arrayOf(genre, genre)).use { c ->
                while (c.moveToNext()) results.add(row(c))
            }
        } else {
            val ftsQuery = terms.joinToString(" AND ") { "$it*" }
            readableDatabase.rawQuery("""SELECT m.id,m.title,m.artist,m.album,
                m.genre,m.lyrics,m.source_uri,m.modified,m.artwork_path
                FROM music m JOIN music_fts f ON m.id=f.id
                WHERE music_fts MATCH ? AND m.hidden=0
                AND (?='' OR m.genre=?) LIMIT 500""",
                arrayOf(ftsQuery, genre, genre)).use { c ->
                while (c.moveToNext()) results.add(row(c))
            }
        }
        return results
    }

    private fun rebuildFts(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM music_fts")
        db.execSQL("""INSERT INTO music_fts(id,title,artist,album,genre,lyrics)
            SELECT id,title,artist,album,genre,lyrics FROM music WHERE hidden=0""")
    }

    fun replaceSnapshot(tracks: List<OdifySong>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Do not destroy a user's manual tag corrections or fetched cover.
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS current_scan(id TEXT PRIMARY KEY)")
            db.execSQL("DELETE FROM current_scan")
            val insertScan = db.compileStatement("INSERT OR IGNORE INTO current_scan(id) VALUES(?)")
            val insert = db.compileStatement("""INSERT OR IGNORE INTO music(
                id,title,artist,album,genre,lyrics,source_uri,modified)
                VALUES(?,?,?,?,?,?,?,?)""")
            val update = db.compileStatement("""UPDATE music SET genre=?,
                lyrics=?,source_uri=?,modified=? WHERE id=?""")
            for (song in tracks) {
                insertScan.clearBindings()
                insertScan.bindString(1,song.id)
                insertScan.executeInsert()
                insert.clearBindings()
                listOf(song.id,song.title,song.artist,song.album,song.genre,
                    song.lyrics,song.sourceUri ?: "",song.driveVersion)
                    .forEachIndexed { i,value -> insert.bindString(i+1,value) }
                insert.executeInsert()
                update.clearBindings()
                listOf(song.genre,song.lyrics,song.sourceUri ?: "",
                    song.driveVersion,song.id)
                    .forEachIndexed { i,value -> update.bindString(i+1,value) }
                update.executeUpdateDelete()
            }
            db.execSQL("DELETE FROM music WHERE id NOT IN (SELECT id FROM current_scan)")
            rebuildFts(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun edit(id: String, title: String, artist: String, album: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("title", title); put("artist", artist); put("album", album)
                put("user_edit",1)
            }
            db.update("music",values,"id=?",arrayOf(id))
            rebuildFts(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun cover(id: String, path: String) {
        val values = ContentValues().apply { put("artwork_path",path) }
        writableDatabase.update("music", values, "id=?",arrayOf(id))
    }

    fun hide(id: String) {
        val values = ContentValues().apply { put("hidden",1) }
        writableDatabase.update("music",values,"id=?",arrayOf(id))
        rebuildFts(writableDatabase)
    }
}