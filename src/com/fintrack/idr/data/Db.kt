package com.fintrack.idr.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fintrack.idr.util.tr
import java.io.File

class Db(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, VERSION) {
    private val appCtx: Context = ctx.applicationContext
    /** Old receipt files moved into the database; deleted only after the upgrade has committed. */
    private val migratedFiles = ArrayList<File>()

    init {
        // Plain rollback journal: the database is a single file, which keeps backup/restore simple and safe.
        setWriteAheadLoggingEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE wallet(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                kind INTEGER NOT NULL,
                icon TEXT NOT NULL,
                initial INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0,
                sort INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE category(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type INTEGER NOT NULL,
                dkey TEXT,
                name TEXT,
                icon TEXT NOT NULL,
                budget INTEGER NOT NULL DEFAULT 0,
                sort INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE tx(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type INTEGER NOT NULL,
                amount INTEGER NOT NULL,
                category_id INTEGER,
                wallet_id INTEGER NOT NULL,
                to_wallet_id INTEGER,
                title TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                date INTEGER NOT NULL,
                time INTEGER NOT NULL,
                recurring_id INTEGER,
                receipt TEXT)"""
        )
        db.execSQL("CREATE INDEX tx_date ON tx(date)")
        db.execSQL("CREATE INDEX tx_cat ON tx(category_id, date)")
        db.execSQL("CREATE INDEX tx_wallet ON tx(wallet_id)")
        db.execSQL("CREATE INDEX tx_to_wallet ON tx(to_wallet_id)")
        db.execSQL(
            """CREATE TABLE recurring(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type INTEGER NOT NULL,
                amount INTEGER NOT NULL,
                category_id INTEGER,
                wallet_id INTEGER NOT NULL,
                to_wallet_id INTEGER,
                title TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                freq INTEGER NOT NULL,
                every INTEGER NOT NULL DEFAULT 1,
                start_date INTEGER NOT NULL,
                end_date INTEGER,
                count INTEGER NOT NULL DEFAULT 0,
                active INTEGER NOT NULL DEFAULT 1,
                remind_days INTEGER NOT NULL DEFAULT 1,
                last_reminded INTEGER NOT NULL DEFAULT -1)"""
        )
        db.execSQL(
            """CREATE TABLE goal(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                icon TEXT NOT NULL,
                target INTEGER NOT NULL,
                deadline INTEGER,
                created INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE goal_entry(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                goal_id INTEGER NOT NULL,
                amount INTEGER NOT NULL,
                date INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL("CREATE INDEX goal_entry_goal ON goal_entry(goal_id)")
        createReceiptTable(db)
        seed(db)
    }

    /**
     * One compressed receipt image per transaction (version 2). data = Base64 of the image binary (at most
     * ReceiptMath.LIMIT bytes before encoding). tx.receipt holds Receipts.MARK while a row exists here.
     */
    private fun createReceiptTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS receipt_image(
                tx_id INTEGER PRIMARY KEY,
                mime TEXT NOT NULL,
                bytes INTEGER NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                data TEXT NOT NULL,
                updated INTEGER NOT NULL)"""
        )
    }

    /** Version 1 kept receipt photos as files and tx.receipt as the file name: compress each into receipt_image. */
    private fun migrateReceiptFiles(db: SQLiteDatabase) {
        val rows = ArrayList<Pair<Long, String>>()
        db.rawQuery("SELECT id, receipt FROM tx WHERE receipt IS NOT NULL", null).use { c ->
            while (c.moveToNext()) rows += c.getLong(0) to c.getString(1)
        }
        if (rows.isEmpty()) return
        val dir = Receipts.dir(appCtx)
        for ((id, name) in rows) {
            val f = File(dir, name)
            val img = try { Receipts.prepare(Receipts.readFile(f)) } catch (t: Throwable) {
                android.util.Log.w("Db", "receipt $name for tx $id not migrated", t); null
            }
            if (img != null) {
                db.insertWithOnConflict("receipt_image", null, Receipts.values(id, img), SQLiteDatabase.CONFLICT_REPLACE)
                db.execSQL("UPDATE tx SET receipt=? WHERE id=?", arrayOf<Any>(Receipts.MARK, id))
                migratedFiles += f
            } else {
                // Missing or unreadable file: the transaction stays, without an attachment it could never show.
                db.execSQL("UPDATE tx SET receipt=NULL WHERE id=?", arrayOf<Any>(id))
            }
        }
    }

    private fun seed(db: SQLiteDatabase) {
        Defaults.categories.forEachIndexed { i, c ->
            db.insert("category", null, ContentValues().apply {
                put("type", c.type); put("dkey", c.key); put("icon", c.icon); put("sort", i)
            })
        }
        val wallets = listOf(
            Triple(tr("Cash", "Tunai"), WalletKind.CASH, "💵"),
            Triple(tr("Bank account", "Rekening bank"), WalletKind.BANK, "🏦"),
            Triple(tr("E-wallet", "Dompet digital"), WalletKind.EWALLET, "📱"),
        )
        wallets.forEachIndexed { i, w ->
            db.insert("wallet", null, ContentValues().apply {
                put("name", w.first); put("kind", w.second); put("icon", w.third); put("sort", i)
            })
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // One step per version. Runs in a single transaction: if anything throws, the old database is left untouched.
        if (oldVersion < 2) {
            createReceiptTable(db)
            migrateReceiptFiles(db)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        migratedFiles.forEach { it.delete() }
        migratedFiles.clear()
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Database is from a newer app version ($oldVersion > $newVersion)")
    }

    companion object {
        const val NAME = "finance.db"
        /** 1 = 1.0.0-1.1.0; 2 = 1.1.1 (receipt_image). */
        const val VERSION = 2
    }
}
