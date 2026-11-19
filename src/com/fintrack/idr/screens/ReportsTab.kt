package com.fintrack.idr.screens

import android.content.Intent
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import com.fintrack.idr.data.CatSum
import com.fintrack.idr.data.Export
import com.fintrack.idr.data.Insights
import com.fintrack.idr.data.ReportData
import com.fintrack.idr.data.Repo
import com.fintrack.idr.data.TxRow
import com.fintrack.idr.ui.BarChart
import com.fintrack.idr.ui.C
import com.fintrack.idr.ui.Icon
import com.fintrack.idr.ui.Live
import com.fintrack.idr.ui.MATCH
import com.fintrack.idr.ui.Ring
import com.fintrack.idr.ui.ViewModel
import com.fintrack.idr.ui.WRAP
import com.fintrack.idr.ui.add
import com.fintrack.idr.ui.amount
import com.fintrack.idr.ui.card
import com.fintrack.idr.ui.chip
import com.fintrack.idr.ui.dp
import com.fintrack.idr.ui.dpf
import com.fintrack.idr.ui.emojiBadge
import com.fintrack.idr.ui.hbox
import com.fintrack.idr.ui.heading
import com.fintrack.idr.ui.iconButton
import com.fintrack.idr.ui.label
import com.fintrack.idr.ui.meter
import com.fintrack.idr.ui.oval
import com.fintrack.idr.ui.pad
import com.fintrack.idr.ui.scroll
import com.fintrack.idr.ui.txRow
import com.fintrack.idr.ui.vbox
import com.fintrack.idr.util.D
import com.fintrack.idr.util.Money
import com.fintrack.idr.util.Prefs
import com.fintrack.idr.util.TxType
import com.fintrack.idr.util.tr
import java.time.LocalDate
import java.time.YearMonth

class ReportsVM : ViewModel() {
    var ym: YearMonth = YearMonth.now()
    var catType = TxType.EXPENSE
    var showAllCats = false
    val data = Live<ReportData?>(null)
    private var ver = -1L
    private var loadedYm: YearMonth? = null

    fun load(force: Boolean) {
        val v = Repo.version
        val m = ym
        if (!force && v == ver && m == loadedYm && data.value != null) return
        Repo.async({ build(m) }) { ver = v; loadedYm = m; data.set(it) }
    }

    private fun build(ym: YearMonth): ReportData {
        val from = D.first(ym)
        val to = D.last(ym)
        val prev = ym.minusMonths(1)
        val cur = Repo.totals(from, to)
        val pv = Repo.totals(prev)
        val daily = LongArray(ym.lengthOfMonth())
        for ((day, v) in Repo.daily(from, to)) daily[(day - from).toInt()] = v[1]
        val months = (5 downTo 0).map { k ->
            val m = ym.minusMonths(k.toLong())
            val t = Repo.totals(m)
            Export.MonthSum(m, t[0], t[1])
        }
        val expenseRows = Repo.txRows(com.fintrack.idr.data.TxFilter(from = from, to = to, type = TxType.EXPENSE))
        val d = ReportData(
            ym = ym, income = cur[0], expense = cur[1], prevIncome = pv[0], prevExpense = pv[1],
            cats = Repo.byCategory(TxType.EXPENSE, from, to),
            prevCats = Repo.byCategory(TxType.EXPENSE, D.first(prev), D.last(prev)).associate { it.catId to it.total },
            incomeCats = Repo.byCategory(TxType.INCOME, from, to),
            daily = daily, months = months,
            highest = Repo.extremeExpenses(from, to, 3, true),
            lowest = Repo.extremeExpenses(from, to, 3, false),
            budget = Repo.budgetLines(from, to, true),
            expenseAmounts = expenseRows.map { it.tx.amount },
        )
        d.insights = Insights.build(d, LocalDate.now(), Prefs.budgetThreshold)
        return d
    }
}

class ReportsTab(a: MainActivity) : Tab(a) {
    private val model = a.vm.reports
    private val body: LinearLayout = a.vbox { pad(16, 8, 16, 28) }
    override val view = a.scroll(body)

    init {
        model.data.observe(a) { d -> if (d != null) render(d) }
    }

    override fun refresh(force: Boolean) = model.load(force)

    private fun render(d: ReportData) {
        body.removeAllViews()
        header(d)
        summary(d)
        trend(d)
        dailyChart(d)
        monthsChart(d)
        categories(d)
        extremes(d)
        if (d.budget.isNotEmpty()) budgets(d)
        insights(d)
    }

    private fun header(d: ReportData) = with(a) {
        body.add(hbox {
            add(label(tr("Reports", "Laporan"), 24f, C.text, true, 1), 0, WRAP, 1f)
            add(iconButton(Icon.EXPORT, C.text, desc = tr("Export", "Ekspor")) { ExportFlow.start(a, model.ym) }, dp(44), dp(44))
        })
        body.add(hbox {
            background = com.fintrack.idr.ui.rounded(C.card, dpf(16f))
            add(iconButton(Icon.CHEV_L, C.text, desc = tr("Previous month", "Bulan sebelumnya")) { model.ym = model.ym.minusMonths(1); model.load(false) }, dp(48), dp(48))
            add(label(D.month(d.ym), 16f, C.text, true, 1, Gravity.CENTER), 0, WRAP, 1f)
            add(iconButton(Icon.CHEV_R, C.text, desc = tr("Next month", "Bulan berikutnya")) { model.ym = model.ym.plusMonths(1); model.load(false) }, dp(48), dp(48))
        }, t = 8, b = 14)
    }

    private fun summary(d: ReportData) = with(a) {
        body.add(card {
            add(hbox {
                val left = vbox()
                left.add(label(tr("Income", "Pemasukan"), 13f, C.text2))
                left.add(amount(Money.fmt(d.income), 17f, C.income), t = 2)
                left.add(label(tr("Expense", "Pengeluaran"), 13f, C.text2), t = 10)
                left.add(amount(Money.fmt(d.expense), 17f, C.expense), t = 2)
                left.add(label(tr("Net", "Selisih"), 13f, C.text2), t = 10)
                val net = d.income - d.expense
                left.add(amount((if (net > 0) "+" else "") + Money.fmt(net), 17f, if (net < 0) C.expense else C.text), t = 2)
                add(left, 0, WRAP, 1f)
                if (d.income > 0) {
                    val spent = Math.round(d.expense * 100.0 / d.income).toInt()
                    val ring = Ring(context)
                    ring.set(spent, "$spent%", tr("of income spent", "pemasukan terpakai"),
                        if (spent > 100) C.expense else if (spent >= 80) C.warn else C.meter)
                    add(ring, dp(124), dp(124))
                }
            })
        }, b = 14)
    }

    private fun change(now: Long, prev: Long): String {
        if (prev == 0L) return if (now == 0L) "–" else tr("new", "baru")
        val p = Math.round((now - prev) * 100.0 / prev).toInt()
        return (if (p > 0) "▲ " else if (p < 0) "▼ " else "") + Math.abs(p) + "%"
    }

    private fun trend(d: ReportData) = with(a) {
        body.add(card {
            add(heading(tr("Compared with ${D.month(d.ym.minusMonths(1))}", "Dibanding ${D.month(d.ym.minusMonths(1))}")))
            if (d.ym == YearMonth.now()) add(label(tr("This month isn't over yet, so totals will still grow.", "Bulan ini belum selesai, jadi totalnya masih bisa bertambah."), 12.5f, C.text3), t = 2)
            fun row(name: String, now: Long, prev: Long, upIsBad: Boolean) = hbox {
                add(label(name, 14f, C.text2), 0, WRAP, 1f)
                add(amount(Money.fmt(prev) + "  →  " + Money.fmt(now), 14f, C.text, false), WRAP, WRAP)
                val up = now > prev
                val col = if (now == prev || prev == 0L) C.text2 else if (up == upIsBad) C.expense else C.income
                add(label(change(now, prev), 13.5f, col, true, 1, Gravity.END), dp(64), WRAP)
            }
            add(row(tr("Expense", "Pengeluaran"), d.expense, d.prevExpense, true), t = 10)
            add(row(tr("Income", "Pemasukan"), d.income, d.prevIncome, false), t = 8)

            val changes = d.cats.map { it to (it.total - (d.prevCats[it.catId] ?: 0L)) } +
                d.prevCats.filter { (id, _) -> d.cats.none { it.catId == id } }.map { (id, v) -> CatSum(id, "", "", 0, 0) to -v }
            val biggest = changes.filter { it.first.label.isNotEmpty() && Math.abs(it.second) > 0 }
                .sortedByDescending { Math.abs(it.second) }.take(3)
            if (biggest.isNotEmpty()) {
                add(label(tr("Biggest category changes", "Perubahan kategori terbesar"), 13f, C.text2, true), t = 14)
                for ((c, diff) in biggest) {
                    add(hbox {
                        add(label(c.icon + "  " + c.label, 14f, C.text, false, 1), 0, WRAP, 1f)
                        add(amount((if (diff > 0) "+" else "") + Money.fmt(diff), 14f, if (diff > 0) C.expense else C.income), WRAP, WRAP)
                    }, t = 6)
                }
            }
        }, b = 14)
    }

    private fun dailyChart(d: ReportData) = with(a) {
        body.add(card {
            add(heading(tr("Daily spending", "Pengeluaran harian")))
            val max = d.daily.maxOrNull() ?: 0L
            if (max == 0L) {
                add(label(tr("No expenses this month.", "Tidak ada pengeluaran bulan ini."), 14f, C.text2), t = 6)
                return@card
            }
            val avg = d.expense / (if (d.ym == YearMonth.now()) LocalDate.now().dayOfMonth else d.ym.lengthOfMonth())
            add(label(tr("Average ${Money.fmt(avg)}/day · tap a bar for details", "Rata-rata ${Money.fmt(avg)}/hari · ketuk batang untuk detail"), 13f, C.text2), t = 2)
            val chart = BarChart(context)
            chart.labelEvery = 5
            if (d.ym == YearMonth.now()) chart.highlight = LocalDate.now().dayOfMonth - 1
            chart.tooltip = { i -> listOf(D.dayShort(D.first(d.ym) + i), Money.fmt(d.daily[i])) }
            chart.set((1..d.daily.size).map { it.toString() }, listOf(d.daily), intArrayOf(C.meter))
            add(chart, MATCH, dp(170), t = 12)
        }, b = 14)
    }

    private fun monthsChart(d: ReportData) = with(a) {
        body.add(card {
            add(heading(tr("Income vs expense, last 6 months", "Pemasukan vs pengeluaran, 6 bulan terakhir")))
            add(hbox {
                fun legend(color: Int, name: String) {
                    add(android.view.View(context).apply { background = oval(color) }, dp(10), dp(10), r = 6)
                    add(label(name, 13f, C.text2), WRAP, WRAP, r = 16)
                }
                legend(C.chartIncome, tr("Income", "Pemasukan"))
                legend(C.chartExpense, tr("Expense", "Pengeluaran"))
            }, t = 6)
            val chart = BarChart(context)
            chart.highlight = d.months.size - 1
            chart.tooltip = { i ->
                val m = d.months[i]
                listOf(D.month(m.ym), tr("In ", "Masuk ") + Money.fmt(m.income), tr("Out ", "Keluar ") + Money.fmt(m.expense))
            }
            chart.set(d.months.map { D.monthShort(it.ym) }, listOf(
                d.months.map { it.income }.toLongArray(), d.months.map { it.expense }.toLongArray()
            ), intArrayOf(C.chartIncome, C.chartExpense))
            add(chart, MATCH, dp(180), t = 10)
        }, b = 14)
    }

    private fun catRow(c: CatSum, total: Long, maxV: Long): LinearLayout = with(a) {
        vbox {
            pad(0, 8, 0, 4)
            add(hbox {
                add(emojiBadge(c.icon, 34), dp(34), dp(34))
                add(label(c.label, 14.5f, C.text, true, 1), 0, WRAP, 1f, l = 10)
                val share = if (total > 0) Math.round(c.total * 100.0 / total).toInt() else 0
                add(label("$share%", 13f, C.text2), WRAP, WRAP, r = 10)
                add(amount(Money.fmt(c.total), 14.5f), WRAP, WRAP)
            })
            val m = meter(if (maxV > 0) (c.total * 100 / maxV).toInt() else 0, 101, C.meter)
            add(m, MATCH, dp(6), l = 44, t = 4)
        }
    }

    private fun categories(d: ReportData) = with(a) {
        body.add(card {
            add(hbox {
                add(heading(tr("By category", "Per kategori")), 0, WRAP, 1f)
                add(chip(tr("Expense", "Pengeluaran"), model.catType == TxType.EXPENSE) { model.catType = TxType.EXPENSE; render(d) }, WRAP, WRAP, r = 6)
                add(chip(tr("Income", "Pemasukan"), model.catType == TxType.INCOME) { model.catType = TxType.INCOME; render(d) }, WRAP, WRAP)
            })
            val list = if (model.catType == TxType.EXPENSE) d.cats else d.incomeCats
            if (list.isEmpty()) {
                add(label(tr("Nothing recorded this month.", "Belum ada catatan bulan ini."), 14f, C.text2), t = 8)
                return@card
            }
            val total = list.sumOf { it.total }
            val maxV = list.first().total
            val isExp = model.catType == TxType.EXPENSE
            if (list.size <= 5 || model.showAllCats) {
                add(label(if (list.size <= 5) tr("All categories, highest first", "Semua kategori, dari terbesar")
                else tr("All ${list.size} categories", "Semua ${list.size} kategori"), 13f, C.text2, true), t = 10)
                for (c in list) add(catRow(c, total, maxV))
            } else {
                add(label(if (isExp) tr("Top 5 highest spending", "5 pengeluaran tertinggi") else tr("Top 5 highest", "5 tertinggi"), 13f, C.text2, true), t = 10)
                for (c in list.take(5)) add(catRow(c, total, maxV))
                val lowest = list.drop(5).reversed().take(5)
                add(label(if (isExp) tr("Top 5 lowest spending", "5 pengeluaran terendah") else tr("Top 5 lowest", "5 terendah"), 13f, C.text2, true), t = 14)
                for (c in lowest) add(catRow(c, total, maxV))
            }
            if (list.size > 5) {
                add(label(if (model.showAllCats) tr("Show top & lowest only", "Tampilkan tertinggi & terendah saja") else tr("Show all ${list.size} categories", "Tampilkan semua ${list.size} kategori"),
                    14f, C.accent, true).pad(0, 10, 0, 0).apply {
                    setOnClickListener { model.showAllCats = !model.showAllCats; render(d) }
                }, WRAP, WRAP)
            }
        }, b = 14)
    }

    private fun extremes(d: ReportData) = with(a) {
        if (d.highest.isEmpty()) return@with
        body.add(card(4) {
            fun group(title: String, rows: List<TxRow>) {
                add(label(title, 13f, C.text2, true).pad(12, 12, 12, 2))
                for (r in rows) add(txRow(r, showDate = true) { TxEditActivity.edit(a, r.tx.id) })
            }
            add(heading(tr("Largest & smallest expenses", "Pengeluaran terbesar & terkecil")).pad(12, 12, 12, 0))
            group(tr("Highest", "Tertinggi"), d.highest)
            val low = d.lowest.filter { l -> d.highest.none { it.tx.id == l.tx.id } }
            if (low.isNotEmpty()) group(tr("Lowest", "Terendah"), low)
        }, b = 14)
    }

    private fun budgets(d: ReportData) = with(a) {
        body.add(card {
            add(hbox {
                add(heading(tr("Budgets", "Anggaran")), 0, WRAP, 1f)
                add(label(tr("Edit", "Ubah"), 14f, C.accent, true).pad(8, 4).apply {
                    setOnClickListener { startActivity(Intent(a, BudgetsActivity::class.java)) }
                }, WRAP, WRAP)
            })
            for (b in d.budget) {
                add(hbox {
                    add(label(b.cat.icon + "  " + b.cat.label, 14f, C.text, true, 1), 0, WRAP, 1f)
                    add(label("${b.pct}%", 13.5f, if (b.pct >= 100) C.expense else if (b.pct >= Prefs.budgetThreshold) C.warn else C.text2, true), WRAP, WRAP)
                }, t = 12)
                add(meter(b.pct, Prefs.budgetThreshold), MATCH, dp(8), t = 6)
                add(label(Money.fmt(b.spent) + " / " + Money.fmt(b.budget), 12.5f, C.text2), t = 4)
            }
        }, b = 14)
    }

    private fun insights(d: ReportData) = with(a) {
        body.add(card {
            add(hbox {
                add(ImageView(context).apply { setImageDrawable(Icon(Icon.INFO, C.text)) }, dp(20), dp(20), r = 8)
                add(heading(tr("Insights", "Wawasan")), 0, WRAP, 1f)
            })
            for (s in d.insights) add(label(s, 14f, C.text, false), t = 10)
        }, b = 14)
    }
}
