package com.fintrack.idr.data

import com.fintrack.idr.util.Money
import com.fintrack.idr.util.tr
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Everything the Reports screen shows for one month. */
class ReportData(
    val ym: YearMonth,
    val income: Long,
    val expense: Long,
    val prevIncome: Long,
    val prevExpense: Long,
    val cats: List<CatSum>,
    val prevCats: Map<Long, Long>,
    val incomeCats: List<CatSum>,
    /** expense per day of month, index 0 = day 1 */
    val daily: LongArray,
    val months: List<Export.MonthSum>,
    val highest: List<TxRow>,
    val lowest: List<TxRow>,
    val budget: List<BudgetLine>,
    /** amounts of each expense in the month, for small-purchase insight */
    val expenseAmounts: List<Long>,
) {
    var insights: List<String> = emptyList()
}

/** Rule-based, explainable insights. Pure Kotlin so it can be unit-tested. */
object Insights {
    const val SMALL = 50_000L

    private fun pct(a: Long, b: Long): Int = if (b == 0L) 0 else Math.round((a - b) * 100.0 / b).toInt()

    fun build(d: ReportData, today: LocalDate, budgetThreshold: Int): List<String> {
        val out = ArrayList<String>()
        val isCurrent = YearMonth.from(today) == d.ym
        val isPast = d.ym.isBefore(YearMonth.from(today))

        if (d.income == 0L && d.expense == 0L) {
            out += tr("📭 No income or expenses recorded for this month yet.", "📭 Belum ada pemasukan atau pengeluaran bulan ini.")
            return out
        }

        // 1. Overspending vs income / savings rate
        if (d.income > 0) {
            val rate = Math.round((d.income - d.expense) * 100.0 / d.income).toInt()
            out += when {
                d.expense > d.income -> tr("⚠️ You spent ${Money.fmt(d.expense - d.income)} more than you earned.",
                    "⚠️ Pengeluaranmu ${Money.fmt(d.expense - d.income)} lebih besar dari pemasukan.")
                rate >= 20 -> tr("✅ You kept $rate% of your income. 20% or more is a healthy savings rate.",
                    "✅ Kamu menyisihkan $rate% dari pemasukan. 20% atau lebih termasuk sehat.")
                else -> tr("💡 You kept $rate% of your income. Aim for at least 20%.",
                    "💡 Kamu menyisihkan $rate% dari pemasukan. Usahakan minimal 20%.")
            }
        } else if (d.expense > 0 && (isPast || today.dayOfMonth > 10)) {
            out += tr("💼 No income recorded this month. Add your salary to see your savings rate.",
                "💼 Belum ada pemasukan bulan ini. Catat gajimu untuk melihat rasio tabungan.")
        }

        // 2. Pace projection for the current month
        if (isCurrent && d.expense > 0) {
            val day = today.dayOfMonth
            val len = d.ym.lengthOfMonth()
            if (day in 5 until len) {
                val projected = d.expense * len / day
                val budgetTotal = d.budget.sumOf { it.budget }
                out += if (budgetTotal > 0 && projected > budgetTotal)
                    tr("📈 At this pace you'll spend about ${Money.fmt(projected)} this month — ${Money.fmt(projected - budgetTotal)} over your total budget.",
                        "📈 Dengan laju ini, pengeluaranmu bulan ini sekitar ${Money.fmt(projected)} — ${Money.fmt(projected - budgetTotal)} di atas total anggaran.")
                else tr("📈 At this pace you'll spend about ${Money.fmt(projected)} this month.",
                    "📈 Dengan laju ini, pengeluaranmu bulan ini sekitar ${Money.fmt(projected)}.")
            }
        }

        // 3. Biggest category share
        val top = d.cats.firstOrNull()
        if (top != null && d.expense > 0) {
            val share = Math.round(top.total * 100.0 / d.expense).toInt()
            if (share >= 30) out += tr("🔎 ${top.label} takes $share% of your spending.", "🔎 ${top.label} menghabiskan $share% dari pengeluaranmu.")
        }

        // 4. Biggest category increase vs last month
        val inc = d.cats.mapNotNull { c ->
            val prev = d.prevCats[c.catId] ?: return@mapNotNull null
            if (prev <= 0) return@mapNotNull null
            val diff = c.total - prev
            if (diff >= 50_000 && pct(c.total, prev) >= 20) Triple(c, diff, pct(c.total, prev)) else null
        }.maxByOrNull { it.second }
        if (inc != null && !isCurrent) {
            out += tr("⬆️ ${inc.first.label} rose ${inc.third}% (${Money.fmt(inc.second)}) vs last month.",
                "⬆️ ${inc.first.label} naik ${inc.third}% (${Money.fmt(inc.second)}) dibanding bulan lalu.")
        } else if (inc != null) {
            out += tr("⬆️ ${inc.first.label} is already ${Money.fmt(inc.second)} above last month's total.",
                "⬆️ ${inc.first.label} sudah ${Money.fmt(inc.second)} di atas total bulan lalu.")
        }

        // 5. Budgets
        val over = d.budget.filter { it.budget > 0 && it.spent > it.budget }
        val near = d.budget.filter { it.budget > 0 && it.spent <= it.budget && it.pct >= budgetThreshold }
        if (over.isNotEmpty()) {
            out += tr("🚨 Over budget: ", "🚨 Melebihi anggaran: ") + over.joinToString(", ") { "${it.cat.label} (${it.pct}%)" }
        } else if (near.isNotEmpty() && isCurrent) {
            out += tr("🟠 Close to the limit: ", "🟠 Hampir habis: ") + near.joinToString(", ") { "${it.cat.label} (${it.pct}%)" }
        }

        // 6. Weekend share
        if (d.expense > 0) {
            var weekend = 0L
            for (i in d.daily.indices) {
                val dow = d.ym.atDay(i + 1).dayOfWeek
                if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) weekend += d.daily[i]
            }
            val share = Math.round(weekend * 100.0 / d.expense).toInt()
            if (share >= 45) out += tr("🗓️ $share% of your spending happened on weekends.", "🗓️ $share% pengeluaranmu terjadi di akhir pekan.")
        }

        // 7. Small purchases add up
        val small = d.expenseAmounts.filter { it < SMALL }
        if (small.size >= 10) {
            out += tr("☕ ${small.size} small purchases under ${Money.fmt(SMALL)} added up to ${Money.fmt(small.sum())}.",
                "☕ ${small.size} pembelian kecil di bawah ${Money.fmt(SMALL)} totalnya ${Money.fmt(small.sum())}.")
        }

        // 8. Month-over-month total (past months only; the current month is incomplete)
        if (!isCurrent && d.prevExpense > 0) {
            val p = pct(d.expense, d.prevExpense)
            if (p <= -10) out += tr("👏 You spent ${-p}% less than the month before.", "👏 Pengeluaranmu ${-p}% lebih rendah dari bulan sebelumnya.")
        }
        return out.take(6)
    }
}
