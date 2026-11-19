package com.fintrack.idr.screens

import android.content.Intent
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import com.fintrack.idr.data.BudgetLine
import com.fintrack.idr.data.Freq
import com.fintrack.idr.data.Goal
import com.fintrack.idr.data.Recurring
import com.fintrack.idr.data.Repo
import com.fintrack.idr.data.TxFilter
import com.fintrack.idr.data.TxRow
import com.fintrack.idr.data.Wallet
import com.fintrack.idr.ui.C
import com.fintrack.idr.ui.Icon
import com.fintrack.idr.ui.Live
import com.fintrack.idr.ui.MATCH
import com.fintrack.idr.ui.ViewModel
import com.fintrack.idr.ui.WRAP
import com.fintrack.idr.ui.add
import com.fintrack.idr.ui.amount
import com.fintrack.idr.ui.card
import com.fintrack.idr.ui.clickable
import com.fintrack.idr.ui.dp
import com.fintrack.idr.ui.dpf
import com.fintrack.idr.ui.emojiBadge
import com.fintrack.idr.ui.emptyState
import com.fintrack.idr.ui.hbox
import com.fintrack.idr.ui.hscroll
import com.fintrack.idr.ui.iconButton
import com.fintrack.idr.ui.label
import com.fintrack.idr.ui.meter
import com.fintrack.idr.ui.onClick
import com.fintrack.idr.ui.pad
import com.fintrack.idr.ui.rounded
import com.fintrack.idr.ui.scroll
import com.fintrack.idr.ui.secondaryButton
import com.fintrack.idr.ui.sectionHeader
import com.fintrack.idr.ui.txRow
import com.fintrack.idr.ui.vbox
import com.fintrack.idr.util.D
import com.fintrack.idr.util.Money
import com.fintrack.idr.util.Prefs
import com.fintrack.idr.util.Privacy
import com.fintrack.idr.util.tr

class Dash(
    val total: Long, val income: Long, val expense: Long,
    val budget: List<BudgetLine>, val wallets: List<Wallet>, val recent: List<TxRow>,
    val upcoming: List<Recurring>, val goals: List<Goal>, val daysLeft: Int,
)

class HomeVM : ViewModel() {
    val state = Live<Dash?>(null)
    private var ver = -1L

    fun load(force: Boolean) {
        val v = Repo.version
        if (!force && v == ver && state.value != null) return
        Repo.async({ build() }) { ver = v; state.set(it) }
    }

    private fun build(): Dash {
        val today = D.today()
        val ym = D.ym(today)
        val from = D.first(ym)
        val to = D.last(ym)
        val t = Repo.totals(from, to)
        val wallets = Repo.wallets()
        val upcoming = Repo.recurringRules().filter { !it.finished && it.nextDate in today..(today + 7) }.sortedBy { it.nextDate }.take(4)
        return Dash(
            total = wallets.sumOf { it.balance }, income = t[0], expense = t[1],
            budget = Repo.budgetLines(from, to, true), wallets = wallets,
            recent = Repo.txRows(TxFilter(limit = 6)), upcoming = upcoming,
            goals = Repo.goals().filter { !it.done }.take(2),
            daysLeft = (to - today + 1).toInt(),
        )
    }
}

class HomeTab(a: MainActivity) : Tab(a) {
    private val model = a.vm.home
    private val body: LinearLayout = a.vbox { pad(16, 8, 16, 28) }
    override val view = a.scroll(body)

    init {
        model.state.observe(a) { d -> if (d != null) render(d) }
    }

    override fun refresh(force: Boolean) = model.load(force)

    private fun render(d: Dash) {
        body.removeAllViews()
        header()
        balanceCard(d)
        budgetCard(d)
        wallets(d.wallets)
        if (d.upcoming.isNotEmpty()) upcoming(d.upcoming)
        if (d.goals.isNotEmpty()) goals(d.goals)
        recent(d.recent)
    }

    private fun header() = with(a) {
        body.add(hbox {
            val left = vbox()
            left.add(label(tr("This month", "Bulan ini"), 13f, C.text2))
            left.add(label(D.month(D.ym(D.today())), 24f, C.text, true, 1))
            add(left, 0, WRAP, 1f)
            add(iconButton(Icon.CALENDAR, C.text, desc = tr("Calendar", "Kalender")) {
                startActivity(Intent(a, CalendarActivity::class.java))
            }, dp(44), dp(44))
            add(iconButton(Icon.SETTINGS, C.text, desc = tr("Settings", "Pengaturan")) {
                startActivity(Intent(a, SettingsActivity::class.java))
            }, dp(44), dp(44))
        }, b = 12)
    }

    private fun balanceCard(d: Dash) = with(a) {
        val dim = 0xB3010201.toInt()
        val hidden = Privacy.hidden
        val c = vbox {
            background = rounded(C.brand, dpf(24f))
            pad(20, 18, 20, 18)
        }
        c.add(label(tr("Total balance", "Total saldo"), 14f, dim, true))
        // Eye icon: open = amounts visible, crossed = amounts masked (for this session).
        c.add(hbox {
            add(amount(Money.fmt(d.total), 32f, C.onBrand, true), 0, WRAP, 1f)
            add(iconButton(if (hidden) Icon.EYE_OFF else Icon.EYE, C.onBrand, 44,
                if (hidden) tr("Show amounts", "Tampilkan nominal") else tr("Hide amounts", "Sembunyikan nominal")) {
                Privacy.hidden = !Privacy.hidden
                Repo.markChanged() // other screens rebuild their text on next view
                model.state.value?.let { render(it) }
            }, dp(44), dp(44), l = 8)
        })
        c.add(label(tr("Across ${d.wallets.size} wallets", "Di ${d.wallets.size} dompet"), 13f, dim), t = 2)
        val row = hbox()
        fun mini(icon: Int, title: String, v: Long) = vbox {
            background = rounded(0x1A010201, dpf(16f))
            pad(12, 10)
            add(hbox {
                add(ImageView(context).apply { setImageDrawable(Icon(icon, C.onBrand)) }, dp(16), dp(16))
                add(label(title, 12.5f, dim, true, 1), WRAP, WRAP, l = 6)
            })
            add(amount(Money.fmt(v), 16f, C.onBrand, true), t = 4)
        }
        row.add(mini(Icon.ARROW_DOWN, tr("Income", "Pemasukan"), d.income), 0, WRAP, 1f, r = 6)
        row.add(mini(Icon.ARROW_UP, tr("Expense", "Pengeluaran"), d.expense), 0, WRAP, 1f, l = 6)
        c.add(row, t = 14)
        val net = d.income - d.expense
        c.add(label(
            tr("Remaining this month (income - expense): ", "Sisa bulan ini (pemasukan - pengeluaran): ") +
                (if (net > 0 && !hidden) "+" else "") + Money.fmt(net),
            13f, C.onBrand, true, 2
        ), t = 10)
        body.add(c, b = 16)
    }

    private fun budgetCard(d: Dash) = with(a) {
        val total = d.budget.sumOf { it.budget }
        val used = d.budget.sumOf { it.spent }
        val c = card {
            add(sectionHeader(tr("Monthly budget", "Anggaran bulanan"), tr("Details", "Detail")) {
                startActivity(Intent(a, BudgetsActivity::class.java))
            })
            if (total == 0L) {
                add(label(tr("Set spending limits per category to see how much you can still spend this month.",
                    "Atur batas pengeluaran per kategori untuk melihat sisa yang masih bisa dipakai bulan ini."), 14f, C.text2), t = 6)
                add(secondaryButton(tr("Set a budget", "Atur anggaran")) { startActivity(Intent(a, BudgetsActivity::class.java)) }, WRAP, WRAP, t = 12)
            } else {
                val pct = (used * 100 / total).toInt()
                add(hbox {
                    add(amount(Money.fmt(used), 20f), WRAP, WRAP)
                    add(label(" / " + Money.fmt(total), 14f, C.text2, false, 1), 0, WRAP, 1f)
                    add(label("$pct%", 18f, if (pct >= 100) C.expense else if (pct >= Prefs.budgetThreshold) C.warn else C.text, true), WRAP, WRAP)
                }, t = 8)
                add(meter(pct, Prefs.budgetThreshold), MATCH, dp(10), t = 10)
                val left = total - used
                val msg = if (left >= 0) {
                    val perDay = if (d.daysLeft > 0) left / d.daysLeft else left
                    tr("${Money.fmt(left)} left · ${d.daysLeft} days to go · about ${Money.fmt(perDay)}/day",
                        "Sisa ${Money.fmt(left)} · ${d.daysLeft} hari lagi · sekitar ${Money.fmt(perDay)}/hari")
                } else tr("Over budget by ${Money.fmt(-left)}", "Melebihi anggaran ${Money.fmt(-left)}")
                add(label(msg, 13f, if (left < 0) C.expense else C.text2, false, 2), t = 8)
                val over = d.budget.count { it.spent > it.budget }
                if (over > 0) add(label(tr("$over categories over budget", "$over kategori melebihi anggaran"), 13f, C.expense, true), t = 4)
            }
        }
        body.add(c, b = 16)
    }

    private fun wallets(ws: List<Wallet>) = with(a) {
        body.add(sectionHeader(tr("Wallets", "Dompet"), tr("Manage", "Kelola")) {
            startActivity(Intent(a, WalletsActivity::class.java))
        }, b = 6)
        val row = hbox()
        for (w in ws) {
            row.add(vbox {
                background = rounded(C.card, dpf(18f))
                pad(14, 12)
                clickable(18, C.card)
                setOnClickListener { startActivity(Intent(a, WalletsActivity::class.java)) }
                add(label(w.icon + "  " + w.name, 13f, C.text2, true, 1))
                add(amount(Money.fmt(w.balance), 16f, if (w.balance < 0 && !Privacy.hidden) C.expense else C.text), t = 4)
            }, dp(160), WRAP, r = 10)
        }
        row.add(vbox {
            gravity = Gravity.CENTER
            background = rounded(C.card2, dpf(18f))
            clickable(18, C.card2)
            pad(14, 12)
            setOnClickListener { startActivity(Intent(a, TxEditActivity::class.java).putExtra(TxEditActivity.EXTRA_TYPE, com.fintrack.idr.util.TxType.TRANSFER)) }
            add(ImageView(context).apply { setImageDrawable(Icon(Icon.TRANSFER, C.text)) }, dp(22), dp(22))
            add(label(tr("Transfer", "Transfer"), 13f, C.text, true, 1, Gravity.CENTER), WRAP, WRAP, t = 4)
        }, dp(110), MATCH)
        body.add(hscroll(row), b = 16)
    }

    private fun upcoming(list: List<Recurring>) = with(a) {
        body.add(sectionHeader(tr("Upcoming in 7 days", "Mendatang 7 hari"), tr("All", "Semua")) {
            startActivity(Intent(a, RecurringActivity::class.java))
        }, b = 6)
        body.add(card(4) {
            for (r in list) {
                add(hbox {
                    pad(12, 10)
                    add(emojiBadge(r.catIcon, 40), dp(40), dp(40))
                    val mid = vbox()
                    mid.add(label(r.title.ifBlank { r.catLabel }, 15f, C.text, true, 1))
                    mid.add(label(D.relative(r.nextDate) + " · " + Freq.label(r.freq, r.every), 13f, C.text2, false, 1))
                    add(mid, 0, WRAP, 1f, l = 12, r = 8)
                    add(amount(Money.signed(r.amount, r.type), 15f, C.typeColor(r.type)), WRAP, WRAP)
                })
            }
        }, b = 16)
    }

    private fun goals(list: List<Goal>) = with(a) {
        body.add(sectionHeader(tr("Savings goals", "Target tabungan"), tr("All", "Semua")) {
            startActivity(Intent(a, GoalsActivity::class.java))
        }, b = 6)
        body.add(card {
            list.forEachIndexed { i, g ->
                add(hbox {
                    add(label(g.icon + "  " + g.name, 15f, C.text, true, 1), 0, WRAP, 1f)
                    add(label("${g.pct}%", 14f, C.text, true), WRAP, WRAP)
                }, t = if (i == 0) 0 else 14)
                add(meter(g.pct, 101, C.income), MATCH, dp(8), t = 8)
                add(label(Money.fmt(g.saved) + " / " + Money.fmt(g.target), 13f, C.text2), t = 6)
            }
        }, b = 16)
    }

    private fun recent(list: List<TxRow>) = with(a) {
        body.add(sectionHeader(tr("Recent transactions", "Transaksi terakhir"), tr("See all", "Lihat semua")) { a.openHistory() }, b = 6)
        if (list.isEmpty()) {
            body.add(card {
                add(emptyState("🧾", tr("No transactions yet", "Belum ada transaksi"),
                    tr("Tap + to record your first income or expense.", "Ketuk + untuk mencatat pemasukan atau pengeluaran pertama."),
                    tr("Add transaction", "Tambah transaksi")) { a.openAdd() })
            })
            return@with
        }
        body.add(card(4) {
            for (r in list) add(txRow(r, showDate = true) { TxEditActivity.edit(a, r.tx.id) })
        })
    }
}
