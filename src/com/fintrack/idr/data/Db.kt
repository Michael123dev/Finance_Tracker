package com.fintrack.idr.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.fintrack.idr.util.tr

class Db(ctx: Context) : SQLiteOpenHelper(ctx, NAME, null, VERSION) {

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
        seed(db)
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
        // Future schema migrations go here, one step per version.
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Database is from a newer app version ($oldVersion > $newVersion)")
    }

    companion object {
        const val NAME = "finance.db"
        const val VERSION = 1
    }
}
