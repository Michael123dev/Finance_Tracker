package com.fintrack.idr.data

import android.content.Context
import com.fintrack.idr.util.D
import com.fintrack.idr.util.TxType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Random

/**
 * Demo build only (package ends with ".demo"): fills an empty install with ~6 months of realistic
 * sample data for store screenshots and videos. The real app never runs this.
 * Every number on screen is still computed by the normal app code from these transactions.
 */
object Demo {
    fun active(ctx: Context): Boolean = ctx.packageName.endsWith(".demo")

    /** Runs once, synchronously, before the first screen loads. */
    fun seedIfNeeded(ctx: Context) {
        val sp = ctx.getSharedPreferences("demo", Context.MODE_PRIVATE)
        if (sp.getBoolean("seeded", false)) return
        Repo.blocking {
            if (Repo.txCount() == 0L) seed()
            RecurringEngine.run(D.today())
            tuneBudgets()
        }
        sp.edit().putBoolean("seeded", true).apply()
    }

    private val rnd = Random(2026)

    private fun pick(vararg s: String): String = s[rnd.nextInt(s.size)]

    /** Random amount between a and b, rounded to Rp1.000 like real prices. */
    private fun r(a: Long, b: Long): Long = (a + (rnd.nextDouble() * (b - a)).toLong()) / 1000 * 1000

    private fun seed() {
        val cats = Repo.categories().filter { it.dkey != null }.associateBy { it.dkey!! }
        fun cat(k: String): Long = cats.getValue(k).id

        val ws = Repo.wallets(includeArchived = true)
        val bank = ws.first { it.kind == WalletKind.BANK }
        val cash = ws.first { it.kind == WalletKind.CASH }
        val ew = ws.first { it.kind == WalletKind.EWALLET }
        Repo.saveWallet(bank.copy(name = "Rekening Utama", initial = 6_000_000))
        Repo.saveWallet(cash.copy(name = "Tunai", initial = 500_000))
        Repo.saveWallet(ew.copy(name = "Dompet Digital", initial = 300_000))
        val savings = Repo.saveWallet(Wallet(0, "Tabungan", WalletKind.BANK, "🐷", 15_000_000, false))

        val today = LocalDate.now()
        val start = YearMonth.from(today).minusMonths(5).atDay(1)
        val zone = ZoneId.systemDefault()

        fun add(day: LocalDate, type: Int, amount: Long, catKey: String?, wallet: Long, title: String, note: String = "", hour: Int = 12, to: Long? = null) {
            if (day.isAfter(today)) return
            val time = day.atTime(hour, rnd.nextInt(60)).atZone(zone).toInstant().toEpochMilli()
            Repo.insertTx(Tx(0, type, amount, catKey?.let { cat(it) }, wallet, to, title, note, day.toEpochDay(), time))
        }

        fun rule(title: String, type: Int, amount: Long, catKey: String?, wallet: Long, day: Int, to: Long? = null, remind: Int = 1) {
            Repo.saveRecurring(Recurring(0, type, amount, catKey?.let { cat(it) }, wallet, to, title, "", Freq.MONTHLY, 1,
                start.withDayOfMonth(day).toEpochDay(), null, 0, true, remind, -1))
        }

        Repo.inTransaction {
            // Fixed monthly items: recorded by the normal recurring engine.
            rule("Gaji bulanan", TxType.INCOME, 9_500_000, "salary", bank.id, 1)
            rule("Sewa kos", TxType.EXPENSE, 2_000_000, "housing", bank.id, 1)
            rule("Top up dompet digital", TxType.TRANSFER, 2_500_000, null, bank.id, 1, to = ew.id, remind = 0)
            rule("Tarik tunai", TxType.TRANSFER, 1_000_000, null, bank.id, 2, to = cash.id, remind = 0)
            rule("Langganan streaming", TxType.EXPENSE, 65_000, "subscription", ew.id, 5)
            rule("Cicilan motor", TxType.EXPENSE, 850_000, "installment", bank.id, 10, remind = 3)
            rule("Internet rumah", TxType.EXPENSE, 350_000, "bills", bank.id, 15)
            rule("Tarik tunai", TxType.TRANSFER, 1_000_000, null, bank.id, 16, to = cash.id, remind = 0)
            rule("BPJS Kesehatan", TxType.EXPENSE, 150_000, "health", bank.id, 22, remind = 3)
            rule("Iuran arisan", TxType.EXPENSE, 200_000, "other_exp", cash.id, 24)
            rule("Setor tabungan", TxType.TRANSFER, 1_000_000, null, bank.id, 26, to = savings)

            // Day-to-day spending.
            var d = start
            while (!d.isAfter(today)) {
                val dow = d.dayOfWeek
                val weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
                if (!weekend) {
                    add(d, TxType.EXPENSE, r(25_000, 45_000), "food", ew.id, pick("Makan siang", "Nasi padang", "Bakso", "Ayam geprek", "Soto ayam"), hour = 12)
                    if (rnd.nextInt(100) < 55) add(d, TxType.EXPENSE, r(18_000, 38_000), "food", ew.id, pick("Kopi susu", "Kopi", "Es teh & camilan"), hour = 15)
                    if (rnd.nextInt(100) < 35) add(d, TxType.EXPENSE, r(15_000, 32_000), "transport", ew.id, "Ojek online", hour = 8)
                } else {
                    if (rnd.nextInt(100) < 60) add(d, TxType.EXPENSE, r(80_000, 180_000), "food", cash.id, pick("Makan bareng keluarga", "Nongkrong", "Makan malam di luar"), hour = 19)
                    if (rnd.nextInt(100) < 30) add(d, TxType.EXPENSE, r(50_000, 110_000), "entertainment", ew.id, pick("Nonton bioskop", "Karaoke", "Main bowling"), hour = 20)
                }
                if (rnd.nextInt(100) < 35) add(d, TxType.EXPENSE, r(30_000, 75_000), "food", cash.id, pick("Makan malam", "Martabak", "Sate ayam", "Pecel lele"), hour = 19)
                if (dow == DayOfWeek.MONDAY || dow == DayOfWeek.THURSDAY) add(d, TxType.EXPENSE, r(30_000, 50_000), "transport", cash.id, "Bensin", hour = 7)
                when (d.dayOfMonth) {
                    3 -> add(d, TxType.EXPENSE, r(550_000, 750_000), "shopping", bank.id, "Belanja bulanan", "Kebutuhan dapur dan rumah", hour = 10)
                    7 -> add(d, TxType.EXPENSE, 200_000, "bills", ew.id, "Token listrik", hour = 9)
                    12 -> add(d, TxType.EXPENSE, 100_000, "bills", ew.id, "Pulsa & paket data", hour = 9)
                    17 -> add(d, TxType.EXPENSE, r(300_000, 450_000), "shopping", bank.id, "Belanja mingguan", hour = 10)
                }
                if (rnd.nextInt(100) < 6) add(d, TxType.EXPENSE, r(150_000, 420_000), "shopping", ew.id, pick("Belanja online", "Sepatu lari", "Kaos & celana"), hour = 21)
                if (rnd.nextInt(100) < 4) add(d, TxType.EXPENSE, r(45_000, 120_000), "health", cash.id, pick("Apotek", "Vitamin"), hour = 18)
                d = d.plusDays(1)
            }

            // One-off income and spending.
            add(start.plusMonths(1).withDayOfMonth(14), TxType.INCOME, 1_200_000, "business", bank.id, "Proyek desain freelance", hour = 16)
            add(start.plusMonths(3).withDayOfMonth(14), TxType.INCOME, 1_500_000, "business", bank.id, "Proyek desain freelance", hour = 16)
            add(start.plusMonths(2).withDayOfMonth(20), TxType.INCOME, 1_500_000, "bonus", bank.id, "Bonus kinerja", hour = 10)
            add(start.plusMonths(2).withDayOfMonth(9), TxType.EXPENSE, 299_000, "education", ew.id, "Kursus online", "Kelas Excel untuk keuangan", hour = 20)
            add(start.plusMonths(4).withDayOfMonth(6), TxType.INCOME, 250_000, "gift", cash.id, "Hadiah ulang tahun", hour = 18)

            // Savings goals (earmarks, separate from wallet balances).
            val emergency = Repo.saveGoal(Goal(0, "Dana Darurat", "🛡️", 30_000_000, LocalDate.of(today.year + 1, 12, 31).toEpochDay(), System.currentTimeMillis(), 0))
            val trip = Repo.saveGoal(Goal(0, "Liburan ke Bali", "🏖️", 6_000_000, today.plusMonths(4).withDayOfMonth(1).toEpochDay(), System.currentTimeMillis(), 0))
            val wedding = Repo.saveGoal(Goal(0, "Dana Nikah", "💍", 60_000_000, LocalDate.of(today.year + 2, 6, 30).toEpochDay(), System.currentTimeMillis(), 0))
            Repo.addGoalEntry(emergency, 10_000_000, start.toEpochDay(), "")
            Repo.addGoalEntry(trip, 1_500_000, start.toEpochDay(), "")
            Repo.addGoalEntry(wedding, 5_000_000, start.toEpochDay(), "")
            var m = start
            while (!m.isAfter(today)) {
                Repo.addGoalEntry(emergency, 1_000_000, m.withDayOfMonth(26).toEpochDay().coerceAtMost(today.toEpochDay()), "")
                Repo.addGoalEntry(trip, 500_000, m.withDayOfMonth(26).toEpochDay().coerceAtMost(today.toEpochDay()), "")
                Repo.addGoalEntry(wedding, 1_500_000, m.withDayOfMonth(26).toEpochDay().coerceAtMost(today.toEpochDay()), "")
                m = m.plusMonths(1)
            }
        }
    }

    /** Budgets set from this month's spending so the budget screens show a normal, a near-limit and an over-limit category. */
    private fun tuneBudgets() {
        val ym = YearMonth.now()
        val lines = Repo.budgetLines(D.first(ym), D.last(ym), false).associateBy { it.cat.dkey }
        fun set(key: String, usedShare: Double, minimum: Long) {
            val line = lines[key] ?: return
            val raw = maxOf(minimum, (line.spent / usedShare).toLong())
            // Round to Rp50.000 the way people set budgets; down for the over-limit example so it stays over.
            val budget = if (usedShare > 1.0) maxOf(50_000L, raw / 50_000 * 50_000) else (raw + 49_999) / 50_000 * 50_000
            Repo.setBudget(line.cat.id, budget)
        }
        set("food", 0.70, 1_500_000)
        set("transport", 0.55, 400_000)
        set("entertainment", 0.86, 200_000)
        set("shopping", 1.06, 500_000)
        set("bills", 0.62, 400_000)
    }
}
