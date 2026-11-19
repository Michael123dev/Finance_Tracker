package com.fintrack.idr.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.fintrack.idr.util.TxType
import com.fintrack.idr.util.tr
import java.io.File
import java.time.YearMonth
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Single source of truth for all data. Every method is synchronous and must run off the main thread:
 * screens use [async]; background components use [blocking]. All work is serialized on one thread,
 * which keeps SQLite simple and makes backup/restore safe.
 */
object Repo {
    private lateinit var app: Context
    private var helper: Db? = null
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "repo-io") }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ioThread: Thread? = null

    /** Bumped on every write so screens know when to reload. */
    @Volatile var version = 0L
        private set

    fun init(ctx: Context) {
        app = ctx.applicationContext
        io.execute { ioThread = Thread.currentThread() }
    }

    val db: SQLiteDatabase
        get() = (helper ?: Db(app).also { helper = it }).writableDatabase

    fun <T> async(work: () -> T, done: (T) -> Unit) {
        io.execute {
            try {
                val r = work()
                main.post { done(r) }
            } catch (t: Throwable) {
                android.util.Log.e("Repo", "async failed", t)
                main.post { Toast.makeText(app, tr("Something went wrong: ", "Terjadi kesalahan: ") + (t.message ?: t.javaClass.simpleName), Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun fire(work: () -> Unit) = async(work) { }

    fun <T> blocking(work: () -> T): T =
        if (Thread.currentThread() === ioThread) work() else io.submit(Callable { work() }).get()

    fun inTransaction(block: () -> Unit) {
        val d = db
        d.beginTransaction()
        try {
            block()
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    fun close() {
        helper?.close()
        helper = null
    }

    fun dbFile(): File = app.getDatabasePath(Db.NAME)

    private fun changed() { version++ }

    // ---------------------------------------------------------------- cursor helpers

    private inline fun <T> query(sql: String, args: Array<Any?> = emptyArray(), map: (Cursor) -> T): List<T> {
        val c = db.rawQuery(sql, Array(args.size) { args[it].toString() })
        try {
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out.add(map(c))
            return out
        } finally {
            c.close()
        }
    }

    private fun long(sql: String, vararg args: Any?): Long {
        val c = db.rawQuery(sql, Array(args.size) { args[it].toString() })
        try {
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } finally {
            c.close()
        }
    }

    private fun Cursor.lN(i: Int): Long? = if (isNull(i)) null else getLong(i)
    private fun Cursor.sN(i: Int): String? = if (isNull(i)) null else getString(i)

    // ---------------------------------------------------------------- wallets

    fun wallets(includeArchived: Boolean = false): List<Wallet> = query(
        """SELECT w.id, w.name, w.kind, w.icon, w.initial, w.archived,
             w.initial
             + IFNULL((SELECT SUM(CASE WHEN type=1 THEN amount ELSE -amount END) FROM tx WHERE wallet_id=w.id), 0)
             + IFNULL((SELECT SUM(amount) FROM tx WHERE type=2 AND to_wallet_id=w.id), 0)
           FROM wallet w ${if (includeArchived) "" else "WHERE w.archived=0"}
           ORDER BY w.archived, w.sort, w.id"""
    ) { c -> Wallet(c.getLong(0), c.getString(1), c.getInt(2), c.getString(3), c.getLong(4), c.getInt(5) != 0, c.getLong(6)) }

    fun totalBalance(): Long = wallets().sumOf { it.balance }

    fun saveWallet(w: Wallet): Long {
        val cv = ContentValues().apply {
            put("name", w.name); put("kind", w.kind); put("icon", w.icon); put("initial", w.initial)
            put("archived", if (w.archived) 1 else 0)
        }
        changed()
        return if (w.id == 0L) {
            cv.put("sort", long("SELECT IFNULL(MAX(sort),0)+1 FROM wallet"))
            db.insertOrThrow("wallet", null, cv)
        } else {
            db.update("wallet", cv, "id=?", arrayOf(w.id.toString())); w.id
        }
    }

    fun walletUsage(id: Long): Long =
        long("SELECT COUNT(*) FROM tx WHERE wallet_id=? OR to_wallet_id=?", id, id) +
            long("SELECT COUNT(*) FROM recurring WHERE wallet_id=? OR to_wallet_id=?", id, id)

    fun deleteWallet(id: Long) {
        db.delete("wallet", "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setWalletArchived(id: Long, archived: Boolean) {
        db.update("wallet", ContentValues().apply { put("archived", if (archived) 1 else 0) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    // ---------------------------------------------------------------- categories

    private fun catFrom(c: Cursor, o: Int = 0) = Category(c.getLong(o), c.getInt(o + 1), c.sN(o + 2), c.sN(o + 3), c.getString(o + 4), c.getLong(o + 5))

    fun categories(type: Int? = null): List<Category> =
        query("SELECT id, type, dkey, name, icon, budget FROM category ${if (type != null) "WHERE type=$type" else ""} ORDER BY type, sort, id") { catFrom(it) }

    fun category(id: Long): Category? =
        query("SELECT id, type, dkey, name, icon, budget FROM category WHERE id=?", arrayOf(id)) { catFrom(it) }.firstOrNull()

    fun saveCategory(c: Category): Long {
        val cv = ContentValues().apply {
            put("type", c.type); put("icon", c.icon); put("budget", c.budget)
            if (c.name == null) putNull("name") else put("name", c.name)
            if (c.dkey == null) putNull("dkey") else put("dkey", c.dkey)
        }
        changed()
        return if (c.id == 0L) {
            cv.put("sort", long("SELECT IFNULL(MAX(sort),0)+1 FROM category"))
            db.insertOrThrow("category", null, cv)
        } else {
            db.update("category", cv, "id=?", arrayOf(c.id.toString())); c.id
        }
    }

    fun setBudget(catId: Long, amount: Long) {
        db.update("category", ContentValues().apply { put("budget", amount) }, "id=?", arrayOf(catId.toString()))
        changed()
    }

    fun categoryUsage(id: Long): Long =
        long("SELECT COUNT(*) FROM tx WHERE category_id=?", id) + long("SELECT COUNT(*) FROM recurring WHERE category_id=?", id)

    /** Moves all transactions and recurring rules to [replacement], then deletes the category. */
    fun deleteCategory(id: Long, replacement: Long?) {
        inTransaction {
            if (replacement != null) {
                val cv = ContentValues().apply { put("category_id", replacement) }
                db.update("tx", cv, "category_id=?", arrayOf(id.toString()))
                db.update("recurring", cv, "category_id=?", arrayOf(id.toString()))
            }
            db.delete("category", "id=?", arrayOf(id.toString()))
        }
        changed()
    }

    // ---------------------------------------------------------------- transactions

    private fun txValues(t: Tx) = ContentValues().apply {
        put("type", t.type); put("amount", t.amount)
        if (t.categoryId == null) putNull("category_id") else put("category_id", t.categoryId)
        put("wallet_id", t.walletId)
        if (t.toWalletId == null) putNull("to_wallet_id") else put("to_wallet_id", t.toWalletId)
        put("title", t.title); put("note", t.note); put("date", t.date); put("time", t.time)
        if (t.recurringId == null) putNull("recurring_id") else put("recurring_id", t.recurringId)
        if (t.receipt == null) putNull("receipt") else put("receipt", t.receipt)
    }

    fun insertTx(t: Tx): Long {
        changed()
        return db.insertOrThrow("tx", null, txValues(t))
    }

    fun updateTx(t: Tx) {
        db.update("tx", txValues(t), "id=?", arrayOf(t.id.toString()))
        changed()
    }

    fun deleteTx(id: Long) {
        db.delete("tx", "id=?", arrayOf(id.toString()))
        changed()
    }

    private const val TX_SELECT =
        """SELECT t.id, t.type, t.amount, t.category_id, t.wallet_id, t.to_wallet_id, t.title, t.note, t.date, t.time,
                  t.recurring_id, t.receipt, c.dkey, c.name, c.icon, w.name, w2.name
           FROM tx t
           LEFT JOIN category c ON c.id = t.category_id
           LEFT JOIN wallet w ON w.id = t.wallet_id
           LEFT JOIN wallet w2 ON w2.id = t.to_wallet_id"""

    private fun rowFrom(c: Cursor): TxRow {
        val tx = Tx(
            c.getLong(0), c.getInt(1), c.getLong(2), c.lN(3), c.getLong(4), c.lN(5), c.getString(6), c.getString(7),
            c.getLong(8), c.getLong(9), c.lN(10), c.sN(11)
        )
        val transfer = tx.type == TxType.TRANSFER
        val label = c.sN(13) ?: Defaults.categoryName(c.sN(12))
            ?: if (transfer) tr("Transfer", "Transfer") else tr("Uncategorized", "Tanpa kategori")
        val icon = c.sN(14) ?: if (transfer) "🔄" else "❔"
        return TxRow(tx, label, icon, c.sN(15) ?: "?", c.sN(16))
    }

    fun tx(id: Long): TxRow? = query("$TX_SELECT WHERE t.id=?", arrayOf(id)) { rowFrom(it) }.firstOrNull()

    fun txRows(f: TxFilter): List<TxRow> {
        val where = ArrayList<String>()
        val args = ArrayList<Any?>()
        if (f.from != null) { where += "t.date >= ?"; args += f.from }
        if (f.to != null) { where += "t.date <= ?"; args += f.to }
        if (f.type != null) { where += "t.type = ?"; args += f.type }
        if (f.categoryId != null) { where += "t.category_id = ?"; args += f.categoryId }
        if (f.walletId != null) { where += "(t.wallet_id = ? OR t.to_wallet_id = ?)"; args += f.walletId; args += f.walletId }
        val s = f.search.trim()
        if (s.isNotEmpty()) {
            val esc = "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            where += "(t.title LIKE ? ESCAPE '\\' OR t.note LIKE ? ESCAPE '\\')"
            args += esc; args += esc
        }
        val sql = TX_SELECT + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY t.date DESC, t.time DESC, t.id DESC" + (if (f.limit > 0) " LIMIT ${f.limit}" else "")
        return query(sql, args.toTypedArray()) { rowFrom(it) }
    }

    /** Highest (desc=true) or lowest single expenses in a date range. */
    fun extremeExpenses(from: Long, to: Long, n: Int, desc: Boolean): List<TxRow> =
        query("$TX_SELECT WHERE t.type=0 AND t.date BETWEEN ? AND ? ORDER BY t.amount ${if (desc) "DESC" else "ASC"}, t.date DESC LIMIT $n", arrayOf(from, to)) { rowFrom(it) }

    /** (income, expense) in a date range. Transfers are neither. */
    fun totals(from: Long, to: Long): LongArray {
        val c = db.rawQuery(
            "SELECT IFNULL(SUM(CASE WHEN type=1 THEN amount END),0), IFNULL(SUM(CASE WHEN type=0 THEN amount END),0) FROM tx WHERE date BETWEEN ? AND ?",
            arrayOf(from.toString(), to.toString())
        )
        try {
            c.moveToFirst()
            return longArrayOf(c.getLong(0), c.getLong(1))
        } finally {
            c.close()
        }
    }

    fun totals(ym: YearMonth): LongArray = totals(com.fintrack.idr.util.D.first(ym), com.fintrack.idr.util.D.last(ym))

    fun byCategory(type: Int, from: Long, to: Long): List<CatSum> = query(
        """SELECT t.category_id, c.dkey, c.name, c.icon, SUM(t.amount), COUNT(*)
           FROM tx t LEFT JOIN category c ON c.id = t.category_id
           WHERE t.type=? AND t.date BETWEEN ? AND ?
           GROUP BY t.category_id ORDER BY 5 DESC""",
        arrayOf(type, from, to)
    ) { c ->
        CatSum(
            c.lN(0) ?: -1, c.sN(2) ?: Defaults.categoryName(c.sN(1)) ?: tr("Uncategorized", "Tanpa kategori"),
            c.sN(3) ?: "❔", c.getLong(4), c.getInt(5)
        )
    }

    /** Per day: [income, expense, transactionCount] */
    fun daily(from: Long, to: Long): Map<Long, LongArray> {
        val out = HashMap<Long, LongArray>()
        query(
            "SELECT date, SUM(CASE WHEN type=1 THEN amount ELSE 0 END), SUM(CASE WHEN type=0 THEN amount ELSE 0 END), COUNT(*) FROM tx WHERE date BETWEEN ? AND ? GROUP BY date",
            arrayOf(from, to)
        ) { c -> out[c.getLong(0)] = longArrayOf(c.getLong(1), c.getLong(2), c.getLong(3)) }
        return out
    }

    fun budgetLines(from: Long, to: Long, onlyWithBudget: Boolean): List<BudgetLine> = query(
        """SELECT c.id, c.type, c.dkey, c.name, c.icon, c.budget,
             IFNULL((SELECT SUM(amount) FROM tx WHERE type=0 AND category_id=c.id AND date BETWEEN ? AND ?), 0)
           FROM category c WHERE c.type=0 ${if (onlyWithBudget) "AND c.budget > 0" else ""}
           ORDER BY c.budget = 0, c.sort, c.id""",
        arrayOf(from, to)
    ) { c -> val cat = catFrom(c); BudgetLine(cat, cat.budget, c.getLong(6)) }

    /** Recent distinct titles with the category last used for them (for autocomplete). */
    fun titleSuggestions(): List<Triple<String, Int, Long?>> = query(
        "SELECT title, type, category_id, MAX(time) FROM tx WHERE title <> '' AND type <> 2 GROUP BY title ORDER BY MAX(time) DESC LIMIT 300"
    ) { c -> Triple(c.getString(0), c.getInt(1), c.lN(2)) }

    fun txCountOn(day: Long): Long = long("SELECT COUNT(*) FROM tx WHERE date=?", day)
    fun txCount(): Long = long("SELECT COUNT(*) FROM tx")
    fun firstTxDate(): Long? = if (txCount() == 0L) null else long("SELECT MIN(date) FROM tx")

    fun receiptsInUse(): Set<String> = query("SELECT receipt FROM tx WHERE receipt IS NOT NULL") { it.getString(0) }.toHashSet()

    // ---------------------------------------------------------------- recurring

    private const val REC_SELECT =
        """SELECT r.id, r.type, r.amount, r.category_id, r.wallet_id, r.to_wallet_id, r.title, r.note, r.freq, r.every,
                  r.start_date, r.end_date, r.count, r.active, r.remind_days, r.last_reminded, c.dkey, c.name, c.icon, w.name
           FROM recurring r
           LEFT JOIN category c ON c.id = r.category_id
           LEFT JOIN wallet w ON w.id = r.wallet_id"""

    private fun recFrom(c: Cursor): Recurring {
        val type = c.getInt(1)
        return Recurring(
            c.getLong(0), type, c.getLong(2), c.lN(3), c.getLong(4), c.lN(5), c.getString(6), c.getString(7), c.getInt(8),
            c.getInt(9), c.getLong(10), c.lN(11), c.getInt(12), c.getInt(13) != 0, c.getInt(14), c.getLong(15),
            c.sN(17) ?: Defaults.categoryName(c.sN(16)) ?: if (type == TxType.TRANSFER) tr("Transfer", "Transfer") else "",
            c.sN(18) ?: if (type == TxType.TRANSFER) "🔄" else "🔁",
            c.sN(19) ?: "?",
        )
    }

    fun recurringRules(includeInactive: Boolean = false): List<Recurring> =
        query("$REC_SELECT ${if (includeInactive) "" else "WHERE r.active=1"} ORDER BY r.active DESC, r.id") { recFrom(it) }

    fun recurring(id: Long): Recurring? = query("$REC_SELECT WHERE r.id=?", arrayOf(id)) { recFrom(it) }.firstOrNull()

    fun saveRecurring(r: Recurring): Long {
        val cv = ContentValues().apply {
            put("type", r.type); put("amount", r.amount)
            if (r.categoryId == null) putNull("category_id") else put("category_id", r.categoryId)
            put("wallet_id", r.walletId)
            if (r.toWalletId == null) putNull("to_wallet_id") else put("to_wallet_id", r.toWalletId)
            put("title", r.title); put("note", r.note); put("freq", r.freq); put("every", r.every)
            put("start_date", r.startDate)
            if (r.endDate == null) putNull("end_date") else put("end_date", r.endDate)
            put("count", r.count); put("active", if (r.active) 1 else 0); put("remind_days", r.remindDays)
            put("last_reminded", r.lastReminded)
        }
        changed()
        return if (r.id == 0L) db.insertOrThrow("recurring", null, cv)
        else { db.update("recurring", cv, "id=?", arrayOf(r.id.toString())); r.id }
    }

    fun setRecurringCount(id: Long, count: Int) {
        db.update("recurring", ContentValues().apply { put("count", count) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setRecurringActive(id: Long, active: Boolean) {
        db.update("recurring", ContentValues().apply { put("active", if (active) 1 else 0) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun setRecurringReminded(id: Long, day: Long) {
        db.update("recurring", ContentValues().apply { put("last_reminded", day) }, "id=?", arrayOf(id.toString()))
    }

    /** Deletes the rule; transactions it already created stay (they really happened). */
    fun deleteRecurring(id: Long) {
        inTransaction {
            db.update("tx", ContentValues().apply { putNull("recurring_id") }, "recurring_id=?", arrayOf(id.toString()))
            db.delete("recurring", "id=?", arrayOf(id.toString()))
        }
        changed()
    }

    // ---------------------------------------------------------------- goals

    fun goals(): List<Goal> = query(
        """SELECT g.id, g.name, g.icon, g.target, g.deadline, g.created,
             IFNULL((SELECT SUM(amount) FROM goal_entry WHERE goal_id = g.id), 0) AS saved
           FROM goal g ORDER BY saved >= g.target, g.deadline IS NULL, g.deadline, g.id"""
    ) { c -> Goal(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.lN(4), c.getLong(5), c.getLong(6)) }

    fun saveGoal(g: Goal): Long {
        val cv = ContentValues().apply {
            put("name", g.name); put("icon", g.icon); put("target", g.target)
            if (g.deadline == null) putNull("deadline") else put("deadline", g.deadline)
            put("created", g.created)
        }
        changed()
        return if (g.id == 0L) db.insertOrThrow("goal", null, cv)
        else { db.update("goal", cv, "id=?", arrayOf(g.id.toString())); g.id }
    }

    fun deleteGoal(id: Long) {
        inTransaction {
            db.delete("goal_entry", "goal_id=?", arrayOf(id.toString()))
            db.delete("goal", "id=?", arrayOf(id.toString()))
        }
        changed()
    }

    fun addGoalEntry(goalId: Long, amount: Long, date: Long, note: String) {
        db.insertOrThrow("goal_entry", null, ContentValues().apply {
            put("goal_id", goalId); put("amount", amount); put("date", date); put("note", note)
        })
        changed()
    }

    fun goalEntries(goalId: Long): List<GoalEntry> =
        query("SELECT id, goal_id, amount, date, note FROM goal_entry WHERE goal_id=? ORDER BY date DESC, id DESC", arrayOf(goalId)) { c ->
            GoalEntry(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getString(4))
        }

    fun deleteGoalEntry(id: Long) {
        db.delete("goal_entry", "id=?", arrayOf(id.toString()))
        changed()
    }

    // ---------------------------------------------------------------- maintenance

    /** Deletes everything and recreates the default categories and wallets. */
    fun wipeAll() {
        close()
        app.deleteDatabase(Db.NAME)
        Receipts.dir(app).listFiles()?.forEach { it.delete() }
        changed()
        db // reopen -> onCreate seeds defaults
    }

    fun markChanged() = changed()

    /** Renames the wallets created on first launch when the app language changes (only if the user never renamed them). */
    fun relabelDefaultWallets(toIndonesian: Boolean) {
        val names = listOf(
            WalletKind.CASH to ("Cash" to "Tunai"),
            WalletKind.BANK to ("Bank account" to "Rekening bank"),
            WalletKind.EWALLET to ("E-wallet" to "Dompet digital"),
        )
        for ((kind, n) in names) {
            val from = if (toIndonesian) n.first else n.second
            val to = if (toIndonesian) n.second else n.first
            db.update("wallet", ContentValues().apply { put("name", to) }, "kind=? AND name=?", arrayOf(kind.toString(), from))
        }
        changed()
    }
}
