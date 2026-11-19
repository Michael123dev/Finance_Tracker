package com.fintrack.idr.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.fintrack.idr.ui.BaseActivity
import com.fintrack.idr.ui.C
import com.fintrack.idr.ui.Icon
import com.fintrack.idr.ui.MATCH
import com.fintrack.idr.ui.ViewModel
import com.fintrack.idr.ui.WRAP
import com.fintrack.idr.ui.add
import com.fintrack.idr.ui.addF
import com.fintrack.idr.ui.dialog
import com.fintrack.idr.ui.divider
import com.fintrack.idr.ui.dp
import com.fintrack.idr.ui.hbox
import com.fintrack.idr.ui.label
import com.fintrack.idr.ui.oval
import com.fintrack.idr.ui.vbox
import com.fintrack.idr.util.CrashLog
import com.fintrack.idr.util.Prefs
import com.fintrack.idr.util.TxType
import com.fintrack.idr.util.tr

abstract class Tab(val a: MainActivity) {
    abstract val view: View
    abstract fun refresh(force: Boolean = false)
}

class MainVM : ViewModel() {
    var tab = 0
    val home = HomeVM()
    val history = HistoryVM()
    val reports = ReportsVM()
}

class MainActivity : BaseActivity() {
    lateinit var vm: MainVM
    private val tabs = arrayOfNulls<Tab>(4)
    private var current = -1
    private lateinit var content: FrameLayout
    private val navIcons = ArrayList<ImageView>()
    private val navLabels = ArrayList<TextView>()
    private val navKinds = intArrayOf(Icon.HOME, Icon.LIST, Icon.CHART, Icon.MORE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = viewModel { MainVM() }
        content = FrameLayout(this)
        val root = vbox {
            add(content, MATCH, 0, 1f)
            add(divider(), MATCH, 1)
            add(bottomBar(), MATCH, dp(68))
        }
        setContentView(root)
        select(savedInstanceState?.getInt("tab", vm.tab) ?: vm.tab)
        firstRunChecks()
    }

    private fun bottomBar(): LinearLayout = hbox {
        setBackgroundColor(C.card)
        val names = listOf(tr("Home", "Beranda"), tr("History", "Riwayat"), tr("Reports", "Laporan"), tr("More", "Lainnya"))
        for (i in 0 until 5) {
            if (i == 2) {
                val fab = FrameLayout(context)
                val circle = ImageView(context).apply {
                    setImageDrawable(Icon(Icon.PLUS, C.onBrand))
                    background = oval(C.brand)
                    val p = dp(13); setPadding(p, p, p, p)
                    elevation = dp(2).toFloat()
                    contentDescription = tr("Add transaction", "Tambah transaksi")
                    isClickable = true
                    setOnClickListener { openAdd() }
                }
                fab.addF(circle, dp(52), dp(52), Gravity.CENTER)
                add(fab, 0, MATCH, 1f)
                continue
            }
            val idx = if (i < 2) i else i - 1
            val item = vbox {
                gravity = Gravity.CENTER
                isClickable = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(C.ripple), null, null)
                setOnClickListener { select(idx) }
                contentDescription = names[idx]
            }
            val ic = ImageView(context).apply { setImageDrawable(Icon(navKinds[idx], C.text3)) }
            val tv = label(names[idx], 11.5f, C.text3, false, 1, Gravity.CENTER)
            item.add(ic, dp(24), dp(24))
            item.add(tv, WRAP, WRAP, t = 3)
            navIcons += ic
            navLabels += tv
            add(item, 0, MATCH, 1f)
        }
    }

    fun select(i: Int) {
        if (i == current) { tabs[i]?.refresh(); return }
        current = i
        vm.tab = i
        val tab = tabs[i] ?: when (i) {
            0 -> HomeTab(this)
            1 -> HistoryTab(this)
            2 -> ReportsTab(this)
            else -> MoreTab(this)
        }.also { tabs[i] = it }
        content.removeAllViews()
        (tab.view.parent as? FrameLayout)?.removeView(tab.view)
        content.addView(tab.view, FrameLayout.LayoutParams(MATCH, MATCH))
        for (k in navIcons.indices) {
            val on = k == i
            val col = if (on) C.text else C.text3
            navIcons[k].setImageDrawable(Icon(navKinds[k], col))
            navLabels[k].setTextColor(col)
            navLabels[k].typeface = if (on) com.fintrack.idr.ui.MEDIUM else com.fintrack.idr.ui.REGULAR
        }
        tab.refresh()
    }

    fun openAdd(type: Int = TxType.EXPENSE) {
        startActivity(Intent(this, TxEditActivity::class.java).putExtra(TxEditActivity.EXTRA_TYPE, type))
    }

    fun openHistory() = select(1)

    override fun onResume() {
        super.onResume()
        if (current >= 0) tabs[current]?.refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (ExportFlow.onResult(this, requestCode, resultCode, data)) return
        BackupFlow.onResult(this, requestCode, resultCode, data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current != 0) select(0) else super.onBackPressed()
    }

    private fun firstRunChecks() {
        CrashLog.take(this)?.let { log ->
            dialog().setTitle(tr("The app closed unexpectedly last time", "Aplikasi tertutup tiba-tiba sebelumnya"))
                .setMessage(tr("You can copy the error details and send them to whoever maintains the app.\n\n", "Kamu bisa menyalin detail error dan mengirimkannya ke pengembang.\n\n") + log.take(1500))
                .setPositiveButton(tr("Copy details", "Salin detail")) { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", log))
                }
                .setNegativeButton(tr("Close", "Tutup"), null)
                .show()
        }
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotifPermission) {
            Prefs.askedNotifPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }
}
