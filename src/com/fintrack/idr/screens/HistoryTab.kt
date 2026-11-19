package com.fintrack.idr.screens

import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.fintrack.idr.data.Category
import com.fintrack.idr.data.Repo
import com.fintrack.idr.data.TxFilter
import com.fintrack.idr.data.TxRow
import com.fintrack.idr.data.Wallet
import com.fintrack.idr.ui.C
import com.fintrack.idr.ui.Icon
import com.fintrack.idr.ui.Live
import com.fintrack.idr.ui.MATCH
import com.fintrack.idr.ui.TxRowView
import com.fintrack.idr.ui.ViewModel
import com.fintrack.idr.ui.WRAP
import com.fintrack.idr.ui.add
import com.fintrack.idr.ui.addF
import com.fintrack.idr.ui.amount
import com.fintrack.idr.ui.chip
import com.fintrack.idr.ui.choose
import com.fintrack.idr.ui.dp
import com.fintrack.idr.ui.dpf
import com.fintrack.idr.ui.emptyState
import com.fintrack.idr.ui.hbox
import com.fintrack.idr.ui.hideKeyboard
import com.fintrack.idr.ui.hscroll
import com.fintrack.idr.ui.iconButton
import com.fintrack.idr.ui.label
import com.fintrack.idr.ui.pad
import com.fintrack.idr.ui.pickDate
import com.fintrack.idr.ui.rounded
import com.fintrack.idr.ui.toast
import com.fintrack.idr.ui.vbox
import com.fintrack.idr.util.D
import com.fintrack.idr.util.Money
import com.fintrack.idr.util.TxType
import com.fintrack.idr.util.tr
import java.time.YearMonth

enum class Period { THIS_MONTH, LAST_MONTH, LAST_30, THIS_YEAR, ALL, CUSTOM;
    fun label(from: Long, to: Long): String = when (this) {
        THIS_MONTH -> tr("This month", "Bulan ini")
        LAST_MONTH -> tr("Last month", "Bulan lalu")
        LAST_30 -> tr("Last 30 days", "30 hari terakhir")
        THIS_YEAR -> tr("This year", "Tahun ini")
        ALL -> tr("All time", "Semua waktu")
        CUSTOM -> D.dayShort(from) + " – " + D.day(to)
    }
}

class HistoryVM : ViewModel() {
    var period = Period.THIS_MONTH
    var periodTouched = false
    var customFrom = D.today() - 30
    var customTo = D.today()
    var type: Int? = null
    var categoryId: Long? = null
    var walletId: Long? = null
    var search = ""
    var categories: List<Category> = emptyList()
    var wallets: List<Wallet> = emptyList()
    val rows = Live<List<TxRow>?>(null)
    private var ver = -1L
    private var key = ""

    fun range(): Pair<Long?, Long?> {
        val t = D.today()
        val ym = YearMonth.now()
        return when (period) {
            Period.THIS_MONTH -> D.first(ym) to D.last(ym)
            Period.LAST_MONTH -> D.first(ym.minusMonths(1)) to D.last(ym.minusMonths(1))
            Period.LAST_30 -> (t - 29) to t
            Period.THIS_YEAR -> D.first(YearMonth.of(ym.year, 1)) to D.last(YearMonth.of(ym.year, 12))
            Period.ALL -> null to null
            Period.CUSTOM -> customFrom to customTo
        }
    }

    fun filter(): TxFilter {
        val (f, t) = range()
        return TxFilter(f, t, type, categoryId, walletId, search)
    }

    val isFiltered get() = periodTouched || type != null || categoryId != null || walletId != null || search.isNotBlank()

    fun reset() {
        period = Period.THIS_MONTH; periodTouched = false; type = null; categoryId = null; walletId = null; search = ""
    }

    fun load(force: Boolean) {
        val f = filter()
        val k = f.toString()
        val v = Repo.version
        if (!force && v == ver && k == key && rows.value != null) return
        Repo.async({
            categories = Repo.categories()
            wallets = Repo.wallets(includeArchived = true)
            Repo.txRows(f)
        }) { ver = v; key = k; rows.set(it) }
    }
}

class HistoryTab(a: MainActivity) : Tab(a) {
    private val vm = a.vm.history
    private val handler = Handler(Looper.getMainLooper())
    private val chips: LinearLayout = a.hbox { pad(16, 0, 16, 0) }
    private val summary: TextView = a.label("", 13f, C.text2, false, 2)
    private val list = ListView(a)
    private val adapter = Adapter()
    private lateinit var searchBox: EditText
    override val view: View

    private sealed class Item {
        class Header(val day: Long, val net: Long) : Item()
        class Row(val r: TxRow) : Item()
    }

    private var items: List<Item> = emptyList()

    init {
        view = a.vbox {
            add(a.hbox {
                pad(16, 8, 8, 4)
                add(a.label(tr("Transactions", "Transaksi"), 24f, C.text, true, 1), 0, WRAP, 1f)
                add(a.iconButton(Icon.CALENDAR, C.text, desc = tr("Calendar view", "Tampilan kalender")) {
                    a.startActivity(Intent(a, CalendarActivity::class.java))
                }, dp(44), dp(44))
            })
            add(searchBar(), MATCH, WRAP, l = 16, r = 16, t = 4)
            add(a.hscroll(chips), MATCH, WRAP, t = 10)
            add(summary, MATCH, WRAP, l = 18, r = 18, t = 8, b = 4)
            val frame = FrameLayout(a)
            list.divider = null
            list.dividerHeight = 0
            list.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            list.setPadding(dp(8), 0, dp(8), dp(24))
            list.clipToPadding = false
            list.adapter = adapter
            list.setOnItemClickListener { _, _, pos, _ ->
                val it = items.getOrNull(pos)
                if (it is Item.Row) TxEditActivity.edit(a, it.r.tx.id)
            }
            list.setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, state: Int) { if (state != 0) a.hideKeyboard() }
                override fun onScroll(v: AbsListView?, f: Int, c: Int, t: Int) {}
            })
            frame.addF(list)
            val empty = a.emptyState("🔍", tr("Nothing here", "Tidak ada transaksi"),
                tr("No transactions match these filters.", "Tidak ada transaksi yang cocok dengan filter ini."))
            frame.addF(empty, MATCH, WRAP)
            list.emptyView = empty
            add(frame, MATCH, 0, 1f)
        }
        vm.rows.observe(a) { rows -> if (rows != null) render(rows) }
        renderChips()
    }

    private fun searchBar(): View = a.hbox {
        background = rounded(C.card2, dpf(14f))
        pad(12, 0, 4, 0)
        add(ImageView(a).apply { setImageDrawable(Icon(Icon.SEARCH, C.text2)) }, dp(20), dp(20))
        searchBox = EditText(a).apply {
            hint = tr("Search name or description", "Cari nama atau keterangan")
            setText(vm.search)
            textSize = 15f
            setTextColor(C.text)
            setHintTextColor(C.text3)
            background = null
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable) {
                    val q = s.toString()
                    if (q == vm.search) return
                    vm.search = q
                    // Searching looks across all time unless the user picked a period explicitly.
                    if (!vm.periodTouched) vm.period = if (q.isBlank()) Period.THIS_MONTH else Period.ALL
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({ renderChips(); vm.load(false) }, 250)
                }
            })
        }
        add(searchBox, 0, dp(46), 1f, l = 8)
        add(a.iconButton(Icon.CLOSE, C.text2, 40, tr("Clear", "Hapus")) { searchBox.setText("") }, dp(40), dp(40))
    }

    private fun renderChips() {
        chips.removeAllViews()
        val (f, t) = vm.range()
        fun add(text: String, on: Boolean, click: () -> Unit) = chips.add(a.chip("$text ▾", on, click), WRAP, WRAP, r = 8)
        add(vm.period.label(f ?: 0, t ?: 0), vm.periodTouched) { pickPeriod() }
        add(vm.type?.let { TxType.label(it) } ?: tr("All types", "Semua jenis"), vm.type != null) {
            val opts = listOf(tr("All types", "Semua jenis"), TxType.label(TxType.EXPENSE), TxType.label(TxType.INCOME), TxType.label(TxType.TRANSFER))
            val cur = when (vm.type) { null -> 0; TxType.EXPENSE -> 1; TxType.INCOME -> 2; else -> 3 }
            a.choose(tr("Transaction type", "Jenis transaksi"), opts, cur) { i ->
                vm.type = when (i) { 0 -> null; 1 -> TxType.EXPENSE; 2 -> TxType.INCOME; else -> TxType.TRANSFER }
                changed()
            }
        }
        val cat = vm.categories.firstOrNull { it.id == vm.categoryId }
        add(cat?.let { it.icon + " " + it.label } ?: tr("All categories", "Semua kategori"), cat != null) {
            val cats = vm.categories.filter { vm.type == null || it.type == vm.type }
            val opts = listOf(tr("All categories", "Semua kategori")) + cats.map { it.icon + "  " + it.label }
            a.choose(tr("Category", "Kategori"), opts, cats.indexOfFirst { it.id == vm.categoryId } + 1) { i ->
                vm.categoryId = if (i == 0) null else cats[i - 1].id
                changed()
            }
        }
        val w = vm.wallets.firstOrNull { it.id == vm.walletId }
        add(w?.let { it.icon + " " + it.name } ?: tr("All wallets", "Semua dompet"), w != null) {
            val opts = listOf(tr("All wallets", "Semua dompet")) + vm.wallets.map { it.icon + "  " + it.name }
            a.choose(tr("Wallet", "Dompet"), opts, vm.wallets.indexOfFirst { it.id == vm.walletId } + 1) { i ->
                vm.walletId = if (i == 0) null else vm.wallets[i - 1].id
                changed()
            }
        }
        if (vm.isFiltered) {
            chips.add(a.label(tr("Reset", "Atur ulang"), 14f, C.accent, true).pad(10, 8).apply {
                setOnClickListener { vm.reset(); searchBox.setText(""); changed() }
            }, WRAP, WRAP)
        }
    }

    private fun pickPeriod() {
        val ps = Period.values()
        val names = ps.map { if (it == Period.CUSTOM) tr("Custom range…", "Rentang khusus…") else it.label(0, 0) }
        a.choose(tr("Period", "Periode"), names, ps.indexOf(vm.period)) { i ->
            val p = ps[i]
            if (p == Period.CUSTOM) {
                a.toast(tr("Choose the start date", "Pilih tanggal mulai"))
                a.pickDate(vm.customFrom) { from ->
                    a.toast(tr("Choose the end date", "Pilih tanggal akhir"))
                    a.pickDate(maxOf(from, vm.customTo)) { to ->
                        vm.customFrom = minOf(from, to); vm.customTo = maxOf(from, to)
                        vm.period = Period.CUSTOM; vm.periodTouched = true; changed()
                    }
                }
            } else {
                vm.period = p
                vm.periodTouched = p != Period.THIS_MONTH
                changed()
            }
        }
    }

    private fun changed() {
        renderChips()
        vm.load(false)
    }

    override fun refresh(force: Boolean) {
        renderChips()
        vm.load(force)
    }

    private fun render(rows: List<TxRow>) {
        renderChips()
        val out = ArrayList<Item>(rows.size + 32)
        var day = Long.MIN_VALUE
        var inc = 0L
        var exp = 0L
        val byDay = rows.groupBy { it.tx.date }
        for (r in rows) {
            if (r.tx.date != day) {
                day = r.tx.date
                val net = byDay[day]!!.sumOf { if (it.tx.type == TxType.INCOME) it.tx.amount else if (it.tx.type == TxType.EXPENSE) -it.tx.amount else 0L }
                out += Item.Header(day, net)
            }
            out += Item.Row(r)
            if (r.tx.type == TxType.INCOME) inc += r.tx.amount
            if (r.tx.type == TxType.EXPENSE) exp += r.tx.amount
        }
        items = out
        summary.text = tr("${rows.size} transactions · In ${Money.fmt(inc)} · Out ${Money.fmt(exp)}",
            "${rows.size} transaksi · Masuk ${Money.fmt(inc)} · Keluar ${Money.fmt(exp)}")
        adapter.notifyDataSetChanged()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int): Any = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (items[p] is Item.Header) 0 else 1
        override fun isEnabled(p: Int) = items[p] is Item.Row

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            return when (val it = items[p]) {
                is Item.Header -> {
                    val v = (convert as? LinearLayout) ?: a.hbox {
                        pad(10, 16, 12, 6)
                        add(a.label("", 13f, C.text2, true, 1), 0, WRAP, 1f)
                        add(a.amount("", 13f, C.text2, true), WRAP, WRAP)
                    }
                    (v.getChildAt(0) as TextView).text = D.relative(it.day)
                    (v.getChildAt(1) as TextView).text = if (it.net == 0L) "" else (if (it.net > 0) "+" else "") + Money.fmt(it.net)
                    v
                }
                is Item.Row -> {
                    val v = (convert as? TxRowView) ?: TxRowView(a).apply { isClickable = false; isFocusable = false }
                    v.bind(it.r, false)
                    v
                }
            }
        }
    }
}
