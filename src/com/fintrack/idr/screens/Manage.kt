package com.fintrack.idr.screens

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import com.fintrack.idr.data.BudgetLine
import com.fintrack.idr.data.Category
import com.fintrack.idr.data.Freq
import com.fintrack.idr.data.Goal
import com.fintrack.idr.data.Recurring
import com.fintrack.idr.data.Repo
import com.fintrack.idr.data.Wallet
import com.fintrack.idr.data.WalletKind
import com.fintrack.idr.ui.BaseActivity
import com.fintrack.idr.ui.C
import com.fintrack.idr.ui.Icon
import com.fintrack.idr.ui.MATCH
import com.fintrack.idr.ui.WRAP
import com.fintrack.idr.ui.add
import com.fintrack.idr.ui.amount
import com.fintrack.idr.ui.amountDialog
import com.fintrack.idr.ui.card
import com.fintrack.idr.ui.chip
import com.fintrack.idr.ui.choose
import com.fintrack.idr.ui.clickable
import com.fintrack.idr.ui.confirm
import com.fintrack.idr.ui.dialog
import com.fintrack.idr.ui.dialogBody
import com.fintrack.idr.ui.dp
import com.fintrack.idr.ui.dpf
import com.fintrack.idr.ui.emojiBadge
import com.fintrack.idr.ui.emojiPicker
import com.fintrack.idr.ui.emptyState
import com.fintrack.idr.ui.flow
import com.fintrack.idr.ui.hbox
import com.fintrack.idr.ui.iconButton
import com.fintrack.idr.ui.input
import com.fintrack.idr.ui.label
import com.fintrack.idr.ui.meter
import com.fintrack.idr.ui.moneyInput
import com.fintrack.idr.ui.pad
import com.fintrack.idr.ui.pickDate
import com.fintrack.idr.ui.rounded
import com.fintrack.idr.ui.scroll
import com.fintrack.idr.ui.toast
import com.fintrack.idr.ui.topBar
import com.fintrack.idr.ui.vbox
import com.fintrack.idr.util.D
import com.fintrack.idr.util.Money
import com.fintrack.idr.util.Prefs
import com.fintrack.idr.util.TxType
import com.fintrack.idr.util.tr
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Base for simple list screens: top bar + scrolling body rebuilt on every load. */
abstract class ListScreen : BaseActivity() {
    protected lateinit var body: LinearLayout
    abstract val screenTitle: String
    open val addLabel: String? = null
    open fun onAdd() {}
    abstract fun load()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = vbox { pad(16, 4, 16, 28) }
        setContentView(vbox {
            add(topBar(screenTitle) {
                if (addLabel != null) add(iconButton(Icon.PLUS, C.text, desc = addLabel) { onAdd() }, dp(44), dp(44))
            })
            add(scroll(body), MATCH, 0, 1f)
        })
    }

    override fun onResume() {
        super.onResume()
        load()
    }
}

// =================================================================== Wallets

class WalletsActivity : ListScreen() {
    override val screenTitle get() = tr("Wallets", "Dompet")
    override val addLabel get() = tr("Add wallet", "Tambah dompet")
    override fun onAdd() = walletDialog(null) { load() }

    override fun load() = Repo.async({ Repo.wallets(includeArchived = true) }) { render(it) }

    private fun render(ws: List<Wallet>) {
        body.removeAllViews()
        val active = ws.filter { !it.archived }
        body.add(card {
            add(label(tr("Total balance", "Total saldo"), 14f, C.text2))
            add(amount(Money.fmt(active.sumOf { it.balance }), 26f), t = 4)
            add(label(tr("Transfers between wallets don't change the total.", "Transfer antar dompet tidak mengubah total."), 12.5f, C.text3), t = 4)
        }, b = 14)
        body.add(card(4) { for (w in active) add(row(w)) }, b = 14)
        val archived = ws.filter { it.archived }
        if (archived.isNotEmpty()) {
            body.add(label(tr("Archived", "Diarsipkan"), 13f, C.text2, true).pad(4, 6, 0, 6))
            body.add(card(4) { for (w in archived) add(row(w)) })
        }
    }

    private fun row(w: Wallet) = hbox {
        pad(12, 10)
        minimumHeight = dp(64)
        clickable(14)
        setOnClickListener { options(w) }
        add(emojiBadge(w.icon, 42), dp(42), dp(42))
        val mid = vbox()
        mid.add(label(w.name, 15f, C.text, true, 1))
        mid.add(label(WalletKind.label(w.kind) + if (w.archived) " · " + tr("archived", "diarsipkan") else "", 13f, C.text2, false, 1))
        add(mid, 0, WRAP, 1f, l = 12, r = 8)
        add(amount(Money.fmt(w.balance), 15f, if (w.balance < 0) C.expense else C.text), WRAP, WRAP)
    }

    private fun options(w: Wallet) {
        val opts = listOf(
            tr("Edit", "Ubah"),
            tr("Transfer from this wallet", "Transfer dari dompet ini"),
            if (w.archived) tr("Unarchive", "Batal arsipkan") else tr("Archive (hide)", "Arsipkan (sembunyikan)"),
            tr("Delete", "Hapus"),
        )
        choose(w.icon + " " + w.name, opts, -1) { i ->
            when (i) {
                0 -> walletDialog(w) { load() }
                1 -> {
                    Prefs.lastWallet = w.id
                    startActivity(Intent(this, TxEditActivity::class.java).putExtra(TxEditActivity.EXTRA_TYPE, TxType.TRANSFER))
                }
                2 -> Repo.async({ Repo.setWalletArchived(w.id, !w.archived) }) { load() }
                3 -> Repo.async({ Repo.walletUsage(w.id) }) { n ->
                    if (n > 0) confirm(tr("Wallet is in use", "Dompet sedang dipakai"),
                        tr("$n transactions or recurring rules use this wallet. Deleting it would break your history. Archive it instead?",
                            "$n transaksi atau aturan berulang memakai dompet ini. Menghapusnya akan merusak riwayat. Arsipkan saja?"),
                        tr("Archive", "Arsipkan")) { Repo.async({ Repo.setWalletArchived(w.id, true) }) { load() } }
                    else confirm(tr("Delete ${w.name}?", "Hapus ${w.name}?"), tr("This cannot be undone.", "Tindakan ini tidak bisa dibatalkan."),
                        tr("Delete", "Hapus"), danger = true) { Repo.async({ Repo.deleteWallet(w.id) }) { load() } }
                }
            }
        }
    }
}

// =================================================================== Categories

class CategoriesActivity : ListScreen() {
    private var type = TxType.EXPENSE
    override val screenTitle get() = tr("Categories", "Kategori")
    override val addLabel get() = tr("Add category", "Tambah kategori")
    override fun onAdd() = categoryDialog(null, type) { load() }

    override fun load() = Repo.async({ Repo.categories(type) }) { render(it) }

    private fun render(cats: List<Category>) {
        body.removeAllViews()
        body.add(hbox {
            add(chip(tr("Expense", "Pengeluaran"), type == TxType.EXPENSE) { type = TxType.EXPENSE; load() }, WRAP, WRAP, r = 8)
            add(chip(tr("Income", "Pemasukan"), type == TxType.INCOME) { type = TxType.INCOME; load() }, WRAP, WRAP)
        }, b = 12)
        body.add(card(4) {
            for (c in cats) add(hbox {
                pad(12, 10)
                minimumHeight = dp(60)
                clickable(14)
                setOnClickListener { options(c, cats) }
                add(emojiBadge(c.icon, 40), dp(40), dp(40))
                add(label(c.label, 15f, C.text, true, 1), 0, WRAP, 1f, l = 12)
                if (c.budget > 0) add(label(tr("Budget ", "Anggaran ") + Money.compact(c.budget), 13f, C.text2), WRAP, WRAP)
            })
        })
    }

    private fun options(c: Category, all: List<Category>) {
        val opts = ArrayList<String>()
        opts += tr("Edit name & icon", "Ubah nama & ikon")
        if (c.type == TxType.EXPENSE) opts += tr("Set monthly budget", "Atur anggaran bulanan")
        opts += tr("Delete", "Hapus")
        choose(c.icon + " " + c.label, opts, -1) { i ->
            when (opts[i]) {
                opts[0] -> categoryDialog(c, c.type) { load() }
                tr("Set monthly budget", "Atur anggaran bulanan") -> amountDialog(c.label, c.budget, allowZero = true,
                    note = tr("Enter 0 to remove the budget.", "Isi 0 untuk menghapus anggaran.")) { v -> Repo.async({ Repo.setBudget(c.id, v) }) { load() } }
                else -> delete(c, all)
            }
        }
    }

    private fun delete(c: Category, all: List<Category>) {
        Repo.async({ Repo.categoryUsage(c.id) }) { n ->
            if (n == 0L) {
                confirm(tr("Delete ${c.label}?", "Hapus ${c.label}?"), tr("This category isn't used anywhere.", "Kategori ini tidak dipakai di mana pun."),
                    tr("Delete", "Hapus"), danger = true) { Repo.async({ Repo.deleteCategory(c.id, null) }) { load() } }
                return@async
            }
            val others = all.filter { it.id != c.id }
            if (others.isEmpty()) { toast(tr("Create another category first", "Buat kategori lain terlebih dahulu")); return@async }
            choose(tr("$n items use ${c.label}. Move them to:", "$n data memakai ${c.label}. Pindahkan ke:"), others.map { it.icon + "  " + it.label }, -1) { i ->
                Repo.async({ Repo.deleteCategory(c.id, others[i].id) }) { toast(tr("Deleted", "Dihapus")); load() }
            }
        }
    }
}

// =================================================================== Budgets

class BudgetsActivity : ListScreen() {
    private var ym = YearMonth.now()
    override val screenTitle get() = tr("Monthly budgets", "Anggaran bulanan")

    override fun load() {
        val m = ym
        Repo.async({ Repo.budgetLines(D.first(m), D.last(m), false) }) { render(it) }
    }

    private fun render(lines: List<BudgetLine>) {
        body.removeAllViews()
        body.add(hbox {
            background = rounded(C.card, dpf(16f))
            add(iconButton(Icon.CHEV_L, C.text, desc = tr("Previous month", "Bulan sebelumnya")) { ym = ym.minusMonths(1); load() }, dp(48), dp(48))
            add(label(D.month(ym), 16f, C.text, true, 1, Gravity.CENTER), 0, WRAP, 1f)
            add(iconButton(Icon.CHEV_R, C.text, desc = tr("Next month", "Bulan berikutnya")) { ym = ym.plusMonths(1); load() }, dp(48), dp(48))
        }, b = 12)
        val withB = lines.filter { it.budget > 0 }
        val total = withB.sumOf { it.budget }
        val spent = withB.sumOf { it.spent }
        body.add(card {
            add(label(tr("Total budget", "Total anggaran"), 14f, C.text2))
            add(hbox {
                add(amount(Money.fmt(spent), 22f), WRAP, WRAP)
                add(label(" / " + Money.fmt(total), 15f, C.text2, false, 1), 0, WRAP, 1f)
            }, t = 4)
            val pct = if (total > 0) (spent * 100 / total).toInt() else 0
            add(meter(pct, Prefs.budgetThreshold), MATCH, dp(10), t = 10)
            add(label(tr("Budgets repeat every month. Only categories with a budget count here.",
                "Anggaran berlaku setiap bulan. Hanya kategori yang punya anggaran dihitung di sini."), 12.5f, C.text3), t = 8)
        }, b = 14)

        if (withB.isNotEmpty()) {
            body.add(label(tr("With budget", "Dengan anggaran"), 13f, C.text2, true).pad(4, 0, 0, 6))
            body.add(card(4) { for (l in withB) add(row(l)) }, b = 14)
        }
        val without = lines.filter { it.budget == 0L }
        if (without.isNotEmpty()) {
            body.add(label(tr("No budget yet — tap to set one", "Belum ada anggaran — ketuk untuk mengatur"), 13f, C.text2, true).pad(4, 0, 0, 6))
            body.add(card(4) { for (l in without) add(row(l)) })
        }
    }

    private fun row(l: BudgetLine) = vbox {
        pad(12, 10)
        clickable(14)
        setOnClickListener { edit(l) }
        add(hbox {
            add(emojiBadge(l.cat.icon, 36), dp(36), dp(36))
            val mid = vbox()
            mid.add(label(l.cat.label, 15f, C.text, true, 1))
            mid.add(label(if (l.budget > 0) Money.fmt(l.spent) + " / " + Money.fmt(l.budget)
            else tr("Spent ", "Terpakai ") + Money.fmt(l.spent), 13f, C.text2, false, 1))
            add(mid, 0, WRAP, 1f, l = 12)
            if (l.budget > 0) add(label("${l.pct}%", 15f, if (l.pct >= 100) C.expense else if (l.pct >= Prefs.budgetThreshold) C.warn else C.text, true), WRAP, WRAP)
            else add(label(tr("Set", "Atur"), 14f, C.accent, true), WRAP, WRAP)
        })
        if (l.budget > 0) {
            add(meter(l.pct, Prefs.budgetThreshold), MATCH, dp(8), l = 48, t = 8)
            val left = l.left
            add(label(if (left >= 0) tr("${Money.fmt(left)} left", "Sisa ${Money.fmt(left)}") else tr("Over by ${Money.fmt(-left)}", "Lebih ${Money.fmt(-left)}"),
                12.5f, if (left < 0) C.expense else C.text3), l = 48, t = 4)
        }
    }

    private fun edit(l: BudgetLine) {
        // Suggest a starting point from the last three months.
        Repo.async({
            (1..3).map { k ->
                val m = YearMonth.now().minusMonths(k.toLong())
                Repo.budgetLines(D.first(m), D.last(m), false).firstOrNull { it.cat.id == l.cat.id }?.spent ?: 0L
            }
        }) { hist ->
            val avg = hist.sum() / 3
            amountDialog(tr("Budget for ", "Anggaran ") + l.cat.label, l.budget, allowZero = true,
                note = tr("Average spending over the last 3 months: ${Money.fmt(avg)}. Enter 0 to remove.",
                    "Rata-rata pengeluaran 3 bulan terakhir: ${Money.fmt(avg)}. Isi 0 untuk menghapus.")) { v ->
                Repo.async({ Repo.setBudget(l.cat.id, v) }) { load() }
            }
        }
    }
}

// =================================================================== Savings goals

class GoalsActivity : ListScreen() {
    override val screenTitle get() = tr("Savings goals", "Target tabungan")
    override val addLabel get() = tr("Add goal", "Tambah target")
    override fun onAdd() = goalDialog(null)

    override fun load() = Repo.async({ Repo.goals() }) { render(it) }

    private fun render(goals: List<Goal>) {
        body.removeAllViews()
        if (goals.isEmpty()) {
            body.add(card {
                add(emptyState("🎯", tr("No savings goals yet", "Belum ada target tabungan"),
                    tr("Set a target like a Wedding Fund or Emergency Fund and track how close you are.",
                        "Buat target seperti Dana Nikah atau Dana Darurat dan pantau progresnya."),
                    tr("Add goal", "Tambah target")) { goalDialog(null) })
            })
            return
        }
        body.add(label(tr("Goals track money you set aside. Adding money here doesn't change wallet balances — move the money to a savings account with a transfer if you want both to match.",
            "Target mencatat uang yang kamu sisihkan. Menambah dana di sini tidak mengubah saldo dompet — pindahkan uangnya ke rekening tabungan lewat transfer jika ingin keduanya sama."),
            12.5f, C.text3).pad(4, 0, 4, 10))
        for (g in goals) body.add(goalCard(g), b = 12)
    }

    private fun goalCard(g: Goal) = card {
        clickable(18, C.card)
        setOnClickListener { options(g) }
        add(hbox {
            add(emojiBadge(g.icon, 44, C.brandSoft), dp(44), dp(44))
            val mid = vbox()
            mid.add(label(g.name, 16f, C.text, true, 1))
            mid.add(label(Money.fmt(g.saved) + " / " + Money.fmt(g.target), 13.5f, C.text2, false, 1))
            add(mid, 0, WRAP, 1f, l = 12)
            add(label("${g.pct}%", 17f, C.text, true), WRAP, WRAP)
        })
        add(meter(g.pct, 101, C.income), MATCH, dp(10), t = 12)
        val msg = when {
            g.done -> tr("🎉 Goal reached!", "🎉 Target tercapai!")
            g.deadline != null -> {
                val months = maxOf(1L, ChronoUnit.MONTHS.between(YearMonth.now(), D.ym(g.deadline)))
                val need = (g.target - g.saved + months - 1) / months
                if (g.deadline < D.today()) tr("Target date ${D.day(g.deadline)} has passed", "Tanggal target ${D.day(g.deadline)} sudah lewat")
                else tr("By ${D.day(g.deadline)} · save about ${Money.fmt(need)}/month", "Sampai ${D.day(g.deadline)} · sisihkan sekitar ${Money.fmt(need)}/bulan")
            }
            else -> tr("${Money.fmt(g.target - g.saved)} to go", "Kurang ${Money.fmt(g.target - g.saved)}")
        }
        add(label(msg, 13f, if (g.done) C.income else C.text2), t = 8)
    }

    private fun options(g: Goal) {
        val opts = listOf(tr("Add money", "Tambah dana"), tr("Withdraw", "Ambil dana"), tr("History", "Riwayat"), tr("Edit", "Ubah"), tr("Delete", "Hapus"))
        choose(g.icon + " " + g.name, opts, -1) { i ->
            when (i) {
                0 -> amountDialog(tr("Add to ", "Tambah ke ") + g.name, 0) { v -> Repo.async({ Repo.addGoalEntry(g.id, v, D.today(), "") }) {
                    if (g.saved + v >= g.target && !g.done) toast(tr("🎉 Goal reached!", "🎉 Target tercapai!"))
                    load()
                } }
                1 -> amountDialog(tr("Withdraw from ", "Ambil dari ") + g.name, 0, note = tr("Saved: ", "Terkumpul: ") + Money.fmt(g.saved)) { v ->
                    Repo.async({ Repo.addGoalEntry(g.id, -minOf(v, g.saved), D.today(), "") }) { load() }
                }
                2 -> history(g)
                3 -> goalDialog(g)
                4 -> confirm(tr("Delete ${g.name}?", "Hapus ${g.name}?"), tr("Its history will be deleted too.", "Riwayatnya juga akan dihapus."),
                    tr("Delete", "Hapus"), danger = true) { Repo.async({ Repo.deleteGoal(g.id) }) { load() } }
            }
        }
    }

    private fun history(g: Goal) {
        Repo.async({ Repo.goalEntries(g.id) }) { entries ->
            val body = dialogBody {
                if (entries.isEmpty()) add(label(tr("No entries yet.", "Belum ada catatan."), 14f, C.text2), t = 8)
                for (e in entries.take(100)) add(hbox {
                    pad(0, 8)
                    add(label(D.day(e.date), 14f, C.text2), 0, WRAP, 1f)
                    add(amount((if (e.amount > 0) "+" else "") + Money.fmt(e.amount), 15f, if (e.amount < 0) C.expense else C.income), WRAP, WRAP)
                })
            }
            dialog().setTitle(g.name).setView(scroll(body)).setPositiveButton(tr("Close", "Tutup"), null).show()
        }
    }

    private fun goalDialog(existing: Goal?) {
        var icon = existing?.icon ?: "🎯"
        var deadline: Long? = existing?.deadline
        val name = input(tr("Goal name (e.g. Wedding Fund)", "Nama target (mis. Dana Nikah)"), existing?.name ?: "")
        val target = input("0").moneyInput(existing?.target ?: 0)
        val badge = emojiBadge(icon, 52, C.brandSoft).apply { setOnClickListener { emojiPicker { e -> icon = e; text = e } } }
        val dl = flow()
        fun renderDl() {
            dl.removeAllViews()
            dl.addView(chip(tr("No date", "Tanpa tanggal"), deadline == null) { deadline = null; renderDl() })
            dl.addView(chip("📅 " + (deadline?.let { D.day(it) } ?: tr("Pick date", "Pilih tanggal")), deadline != null) {
                pickDate(deadline ?: (D.today() + 365)) { deadline = it; renderDl() }
            })
        }
        renderDl()
        val body = dialogBody {
            add(hbox {
                add(badge, dp(52), dp(52))
                add(label(tr("Tap to change icon", "Ketuk untuk ganti ikon"), 14f, C.accent, true), 0, WRAP, 1f, l = 12)
            })
            add(name, MATCH, WRAP, t = 12)
            add(label(tr("Target amount", "Jumlah target"), 13f, C.text2, true), t = 12)
            add(hbox { add(label("Rp", 18f, C.text2, true), WRAP, WRAP, r = 8); add(target, 0, WRAP, 1f) }, t = 6)
            add(label(tr("Target date (optional)", "Tanggal target (opsional)"), 13f, C.text2, true), t = 12)
            add(dl, MATCH, WRAP, t = 6)
        }
        val d = dialog().setTitle(if (existing == null) tr("New savings goal", "Target tabungan baru") else tr("Edit goal", "Ubah target"))
            .setView(scroll(body)).setPositiveButton(tr("Save", "Simpan"), null).setNegativeButton(tr("Cancel", "Batal"), null).create()
        d.setOnShowListener {
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                val t = Money.parse(target.text)
                if (n.isEmpty()) { name.error = tr("Required", "Wajib diisi"); return@setOnClickListener }
                if (t <= 0) { target.error = tr("Enter an amount", "Masukkan jumlah"); return@setOnClickListener }
                d.dismiss()
                val g = Goal(existing?.id ?: 0, n, icon, t, deadline, existing?.created ?: System.currentTimeMillis(), 0)
                Repo.async({ Repo.saveGoal(g) }) { load() }
            }
        }
        d.show()
    }
}

// =================================================================== Recurring

class RecurringActivity : ListScreen() {
    override val screenTitle get() = tr("Recurring transactions", "Transaksi berulang")
    override val addLabel get() = tr("Add recurring", "Tambah berulang")
    override fun onAdd() = TxEditActivity.newRule(this)

    override fun load() = Repo.async({ Repo.recurringRules(includeInactive = true) }) { render(it) }

    /** Rough monthly equivalent of a rule (for the "fixed commitments" summary). */
    private fun monthly(r: Recurring): Long {
        val per = when (r.freq) {
            Freq.DAILY -> r.amount * 365 / 12
            Freq.WEEKLY -> r.amount * 52 / 12
            Freq.MONTHLY -> r.amount
            else -> r.amount / 12
        }
        return per / maxOf(1, r.every)
    }

    private fun render(rules: List<Recurring>) {
        body.removeAllViews()
        if (rules.isEmpty()) {
            body.add(card {
                add(emptyState("🔁", tr("Nothing recurring yet", "Belum ada transaksi berulang"),
                    tr("Add salary, rent, installments or subscriptions once and they're recorded automatically on their date.",
                        "Tambahkan gaji, sewa, cicilan, atau langganan sekali saja, dan akan tercatat otomatis di tanggalnya."),
                    tr("Add recurring", "Tambah berulang")) { onAdd() })
            })
            return
        }
        val live = rules.filter { it.active && !it.finished }
        val out = live.filter { it.type == TxType.EXPENSE }.sumOf { monthly(it) }
        val inc = live.filter { it.type == TxType.INCOME }.sumOf { monthly(it) }
        body.add(card {
            add(label(tr("Fixed monthly commitments (approx.)", "Komitmen tetap per bulan (perkiraan)"), 14f, C.text2))
            add(amount(Money.fmt(out), 22f, C.expense), t = 4)
            if (inc > 0) add(label(tr("Recurring income ≈ ${Money.fmt(inc)}/month · ${Math.round(out * 100.0 / inc)}% of it is already committed",
                "Pemasukan berulang ≈ ${Money.fmt(inc)}/bulan · ${Math.round(out * 100.0 / inc)}% sudah terpakai untuk komitmen"), 13f, C.text2), t = 6)
        }, b = 14)
        body.add(card(4) {
            for (r in rules) add(hbox {
                pad(12, 10)
                minimumHeight = dp(64)
                clickable(14)
                setOnClickListener { TxEditActivity.editRule(this@RecurringActivity, r.id) }
                add(emojiBadge(r.catIcon, 42), dp(42), dp(42))
                val mid = vbox()
                mid.add(label(r.title.ifBlank { r.catLabel }, 15f, if (r.active && !r.finished) C.text else C.text3, true, 1))
                val status = when {
                    !r.active -> tr("Paused", "Dijeda")
                    r.finished -> tr("Ended", "Selesai")
                    else -> tr("Next: ", "Berikutnya: ") + D.relative(r.nextDate)
                }
                mid.add(label(Freq.label(r.freq, r.every) + " · " + status, 13f, C.text2, false, 1))
                add(mid, 0, WRAP, 1f, l = 12, r = 8)
                add(amount(Money.signed(r.amount, r.type), 15f, if (r.active) C.typeColor(r.type) else C.text3), WRAP, WRAP)
            })
        })
    }
}
