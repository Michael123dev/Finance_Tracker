package com.fintrack.idr.screens

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import com.fintrack.idr.data.Category
import com.fintrack.idr.data.Freq
import com.fintrack.idr.data.Receipts
import com.fintrack.idr.data.Recurring
import com.fintrack.idr.data.RecurringEngine
import com.fintrack.idr.data.Repo
import com.fintrack.idr.data.Tx
import com.fintrack.idr.data.Wallet
import com.fintrack.idr.ui.BaseActivity
import com.fintrack.idr.ui.C
import com.fintrack.idr.ui.Icon
import com.fintrack.idr.ui.MATCH
import com.fintrack.idr.ui.MEDIUM
import com.fintrack.idr.ui.ViewModel
import com.fintrack.idr.ui.WRAP
import com.fintrack.idr.ui.add
import com.fintrack.idr.ui.chip
import com.fintrack.idr.ui.confirm
import com.fintrack.idr.ui.divider
import com.fintrack.idr.ui.dp
import com.fintrack.idr.ui.dpf
import com.fintrack.idr.ui.flow
import com.fintrack.idr.ui.hbox
import com.fintrack.idr.ui.hideKeyboard
import com.fintrack.idr.ui.iconButton
import com.fintrack.idr.ui.input
import com.fintrack.idr.ui.label
import com.fintrack.idr.ui.moneyInput
import com.fintrack.idr.ui.pad
import com.fintrack.idr.ui.pickDate
import com.fintrack.idr.ui.primaryButton
import com.fintrack.idr.ui.rounded
import com.fintrack.idr.ui.scroll
import com.fintrack.idr.ui.secondaryButton
import com.fintrack.idr.ui.showKeyboard
import com.fintrack.idr.ui.toast
import com.fintrack.idr.ui.topBar
import com.fintrack.idr.ui.vbox
import com.fintrack.idr.util.BudgetAlert
import com.fintrack.idr.util.D
import com.fintrack.idr.util.FilesProvider
import com.fintrack.idr.util.Lock
import com.fintrack.idr.util.Money
import com.fintrack.idr.util.Prefs
import com.fintrack.idr.util.TxType
import com.fintrack.idr.util.tr

class TxEditVM : ViewModel() {
    var loaded = false
    var ruleMode = false
    var id = 0L
    var type = TxType.EXPENSE
    var amount = 0L
    var title = ""
    var note = ""
    var categoryId: Long? = null
    var walletId: Long? = null
    var toWalletId: Long? = null
    var date = D.today()
    var time = 0L
    var receipt: String? = null
    var originalReceipt: String? = null
    val tempReceipts = ArrayList<String>()
    var recurringId: Long? = null
    var linkedRule: Recurring? = null
    var repeat = false
    var freq = Freq.MONTHLY
    var every = 1
    var endDate: Long? = null
    var remindDays = 1
    var active = true
    var ruleCount = 0
    var ruleStart = 0L
    var lastReminded = -1L
    var origFreq = -1
    var origEvery = -1
    var origNext = -1L
    var saved = false
    var categories: List<Category> = emptyList()
    var wallets: List<Wallet> = emptyList()
    var suggestions: List<Triple<String, Int, Long?>> = emptyList()
}

class TxEditActivity : BaseActivity() {
    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_TX = "tx_id"
        const val EXTRA_RULE = "rule_id"
        const val EXTRA_RULE_MODE = "rule_mode"
        const val EXTRA_DATE = "date"
        private const val REQ_CAMERA = 601
        private const val REQ_GALLERY = 602

        fun edit(a: Activity, id: Long) = a.startActivity(Intent(a, TxEditActivity::class.java).putExtra(EXTRA_TX, id))
        fun editRule(a: Activity, id: Long) = a.startActivity(Intent(a, TxEditActivity::class.java).putExtra(EXTRA_RULE, id).putExtra(EXTRA_RULE_MODE, true))
        fun newRule(a: Activity) = a.startActivity(Intent(a, TxEditActivity::class.java).putExtra(EXTRA_RULE_MODE, true))
    }

    private lateinit var vm: TxEditVM
    private lateinit var form: LinearLayout
    private lateinit var amountField: EditText
    private var receiptBox: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = viewModel { TxEditVM() }
        if (vm.loaded) build() else load()
    }

    private fun load() {
        val txId = intent.getLongExtra(EXTRA_TX, 0)
        val ruleId = intent.getLongExtra(EXTRA_RULE, 0)
        vm.ruleMode = intent.getBooleanExtra(EXTRA_RULE_MODE, false)
        vm.type = intent.getIntExtra(EXTRA_TYPE, TxType.EXPENSE)
        val presetDate = intent.getLongExtra(EXTRA_DATE, Long.MIN_VALUE)
        if (presetDate != Long.MIN_VALUE) vm.date = presetDate
        Repo.async({
            vm.categories = Repo.categories()
            vm.wallets = Repo.wallets(includeArchived = true)
            vm.suggestions = Repo.titleSuggestions()
            if (txId > 0) Repo.tx(txId)?.let { r ->
                val t = r.tx
                vm.id = t.id; vm.type = t.type; vm.amount = t.amount; vm.title = t.title; vm.note = t.note
                vm.categoryId = t.categoryId; vm.walletId = t.walletId; vm.toWalletId = t.toWalletId
                vm.date = t.date; vm.time = t.time; vm.receipt = t.receipt; vm.originalReceipt = t.receipt
                vm.recurringId = t.recurringId
                vm.linkedRule = t.recurringId?.let { Repo.recurring(it) }
            }
            if (ruleId > 0) Repo.recurring(ruleId)?.let { r ->
                vm.id = r.id; vm.type = r.type; vm.amount = r.amount; vm.title = r.title; vm.note = r.note
                vm.categoryId = r.categoryId; vm.walletId = r.walletId; vm.toWalletId = r.toWalletId
                vm.freq = r.freq; vm.every = r.every; vm.endDate = r.endDate; vm.remindDays = r.remindDays
                vm.active = r.active; vm.ruleCount = r.count; vm.ruleStart = r.startDate; vm.lastReminded = r.lastReminded
                vm.date = r.nextDate
                vm.origFreq = r.freq; vm.origEvery = r.every; vm.origNext = r.nextDate
            }
            if (vm.walletId == null) {
                val active = vm.wallets.filter { !it.archived }
                vm.walletId = (active.firstOrNull { it.id == Prefs.lastWallet } ?: active.firstOrNull())?.id
            }
        }) {
            vm.loaded = true
            build()
            if (vm.id == 0L) showKeyboard(amountField)
        }
    }

    private val isNew get() = vm.id == 0L

    private fun build() {
        val titleText = when {
            vm.ruleMode && isNew -> tr("New recurring transaction", "Transaksi berulang baru")
            vm.ruleMode -> tr("Edit recurring transaction", "Ubah transaksi berulang")
            isNew -> tr("Add transaction", "Tambah transaksi")
            else -> tr("Edit transaction", "Ubah transaksi")
        }
        form = vbox { pad(16, 4, 16, 24) }
        val root = vbox {
            add(topBar(titleText) {
                if (!isNew) add(iconButton(Icon.TRASH, C.expense, desc = tr("Delete", "Hapus")) { delete() }, dp(44), dp(44))
            })
            add(scroll(form), MATCH, 0, 1f)
            add(divider(), MATCH, 1)
            add(primaryButton(tr("Save", "Simpan")) { save() }, MATCH, WRAP, l = 16, t = 10, r = 16, b = 12)
        }
        setContentView(root)
        renderForm()
    }

    private fun section(title: String) = form.add(label(title, 13f, C.text2, true), MATCH, WRAP, t = 18, b = 8)

    private fun renderForm() {
        form.removeAllViews()
        typeSwitch()
        amountRow()
        titleRow()
        if (vm.type != TxType.TRANSFER) categoryRow()
        walletRows()
        dateRow()
        noteRow()
        if (!vm.ruleMode) receiptRow()
        repeatRows()
    }

    private fun typeSwitch() {
        val box = hbox {
            background = rounded(C.card2, dpf(18f))
            pad(4)
        }
        for (t in intArrayOf(TxType.EXPENSE, TxType.INCOME, TxType.TRANSFER)) {
            val on = vm.type == t
            box.add(label(TxType.label(t), 14.5f, if (on) C.onBrand else C.text, on, 1, Gravity.CENTER).apply {
                background = if (on) rounded(C.brand, dpf(14f)) else null
                minHeight = dp(42)
                setOnClickListener {
                    if (vm.type == t) return@setOnClickListener
                    vm.type = t
                    // A category of the other type no longer fits.
                    if (vm.categories.none { it.id == vm.categoryId && it.type == t }) vm.categoryId = null
                    renderForm()
                }
            }, 0, WRAP, 1f)
        }
        form.add(box, MATCH, WRAP, t = 4)
    }

    private fun amountRow() {
        amountField = EditText(this).apply {
            hint = "0"
            textSize = 34f
            typeface = MEDIUM
            setTextColor(C.typeColor(vm.type))
            setHintTextColor(C.text3)
            background = null
            setPadding(0, dp(4), 0, dp(4))
            fontFeatureSettings = "tnum"
        }.moneyInput(vm.amount) { vm.amount = it }
        form.add(hbox {
            add(label("Rp", 26f, C.text2, true), WRAP, WRAP, r = 10)
            add(amountField, 0, WRAP, 1f)
        }, MATCH, WRAP, t = 14)
        form.add(divider(), MATCH, 1)
    }

    private fun titleRow() {
        section(if (vm.type == TxType.TRANSFER) tr("Name (optional)", "Nama (opsional)") else tr("Name", "Nama"))
        val ac = AutoCompleteTextView(this).apply {
            hint = when (vm.type) {
                TxType.INCOME -> tr("e.g. Salary November", "mis. Gaji November")
                TxType.TRANSFER -> tr("e.g. Top up GoPay", "mis. Isi saldo GoPay")
                else -> tr("e.g. Lunch, Fuel", "mis. Makan siang, Bensin")
            }
            setText(vm.title)
            textSize = 16f
            setTextColor(C.text)
            setHintTextColor(C.text3)
            background = rounded(C.card2, dpf(14f))
            pad(14, 12)
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            threshold = 1
            val sugg = vm.suggestions.filter { it.second == vm.type }
            setAdapter(ArrayAdapter(this@TxEditActivity, android.R.layout.simple_dropdown_item_1line, sugg.map { it.first }))
            setOnItemClickListener { parent, _, pos, _ ->
                val chosen = parent.getItemAtPosition(pos) as String
                vm.title = chosen
                // Reuse the category last used with this name.
                val cat = sugg.firstOrNull { it.first == chosen }?.third
                if (cat != null && vm.categories.any { it.id == cat }) { vm.categoryId = cat; renderForm() }
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable) { vm.title = s.toString() }
            })
        }
        form.add(ac)
    }

    private fun categoryRow() {
        section(tr("Category", "Kategori"))
        val f = flow()
        for (c in vm.categories.filter { it.type == vm.type }) {
            f.addView(chip(c.icon + " " + c.label, c.id == vm.categoryId) { vm.categoryId = c.id; renderForm() })
        }
        f.addView(chip("+ " + tr("New", "Baru"), false) {
            categoryDialog(null, vm.type) { id ->
                Repo.async({ Repo.categories() }) { vm.categories = it; vm.categoryId = id; renderForm() }
            }
        })
        form.add(f)
    }

    private fun walletChips(selected: Long?, exclude: Long?, onPick: (Long) -> Unit): View {
        val f = flow()
        val list = vm.wallets.filter { (!it.archived || it.id == selected) && it.id != exclude }
        for (w in list) f.addView(chip(w.icon + " " + w.name, w.id == selected) { onPick(w.id) })
        f.addView(chip("+ " + tr("New", "Baru"), false) {
            walletDialog(null) { id -> Repo.async({ Repo.wallets(includeArchived = true) }) { vm.wallets = it; onPick(id) } }
        })
        return f
    }

    private fun walletRows() {
        if (vm.type == TxType.TRANSFER) {
            section(tr("From wallet", "Dari dompet"))
            form.add(walletChips(vm.walletId, null) { vm.walletId = it; if (vm.toWalletId == it) vm.toWalletId = null; renderForm() })
            section(tr("To wallet", "Ke dompet"))
            form.add(walletChips(vm.toWalletId, vm.walletId) { vm.toWalletId = it; renderForm() })
        } else {
            section(tr("Wallet", "Dompet"))
            form.add(walletChips(vm.walletId, null) { vm.walletId = it; renderForm() })
        }
    }

    private fun dateRow() {
        section(if (vm.ruleMode) (if (isNew) tr("First date", "Tanggal pertama") else tr("Next date", "Tanggal berikutnya")) else tr("Date", "Tanggal"))
        val t = D.today()
        val f = flow()
        f.addView(chip(tr("Today", "Hari ini"), vm.date == t) { vm.date = t; renderForm() })
        if (!vm.ruleMode) f.addView(chip(tr("Yesterday", "Kemarin"), vm.date == t - 1) { vm.date = t - 1; renderForm() })
        val custom = vm.date != t && (vm.ruleMode || vm.date != t - 1)
        f.addView(chip("📅 " + (if (custom) D.day(vm.date) else tr("Pick date", "Pilih tanggal")), custom) {
            pickDate(vm.date) { vm.date = it; renderForm() }
        })
        form.add(f)
    }

    private fun noteRow() {
        section(tr("Description", "Keterangan"))
        val et = input(tr("Optional details", "Detail opsional"), vm.note, multiline = true)
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable) { vm.note = s.toString() }
        })
        form.add(et)
    }

    private fun receiptRow() {
        section(tr("Receipt photo", "Foto struk"))
        val box = vbox()
        receiptBox = box
        form.add(box)
        renderReceipt()
    }

    private fun renderReceipt() {
        val box = receiptBox ?: return
        box.removeAllViews()
        val name = vm.receipt
        if (name != null) {
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(C.card2, dpf(14f))
                clipToOutline = true
                contentDescription = tr("Receipt photo", "Foto struk")
                setOnClickListener { startActivity(Intent(this@TxEditActivity, ReceiptActivity::class.java).putExtra(ReceiptActivity.EXTRA_NAME, name)) }
            }
            Repo.async({ Receipts.thumbnail(applicationContext, name, dp(240)) }) { bmp -> if (bmp != null) img.setImageBitmap(bmp) }
            box.add(hbox {
                add(img, dp(96), dp(96))
                val col = vbox()
                col.add(secondaryButton(tr("View", "Lihat")) { img.performClick() }, MATCH, WRAP)
                col.add(secondaryButton(tr("Remove", "Hapus"), C.expense) { vm.receipt = null; renderReceipt() }, MATCH, WRAP, t = 8)
                add(col, 0, WRAP, 1f, l = 12)
            })
        } else {
            box.add(hbox {
                add(secondaryButton("📷 " + tr("Camera", "Kamera")) { takePhoto() }, 0, WRAP, 1f, r = 6)
                add(secondaryButton("🖼️ " + tr("Gallery", "Galeri")) { pickImage() }, 0, WRAP, 1f, l = 6)
            })
        }
    }

    private fun repeatRows() {
        if (!vm.ruleMode) {
            val rule = vm.linkedRule
            if (!isNew && rule != null) {
                section(tr("Recurring", "Berulang"))
                form.add(hbox {
                    background = rounded(C.card2, dpf(14f))
                    pad(14, 12)
                    add(label("🔁 " + tr("Created by a recurring rule (${Freq.label(rule.freq, rule.every)}). Changes here affect only this transaction.",
                        "Dibuat oleh aturan berulang (${Freq.label(rule.freq, rule.every)}). Perubahan di sini hanya untuk transaksi ini."), 13.5f, C.text), 0, WRAP, 1f)
                    add(label(tr("Manage", "Kelola"), 14f, C.accent, true).pad(8, 4).apply {
                        setOnClickListener { editRule(this@TxEditActivity, rule.id) }
                    }, WRAP, WRAP)
                })
                return
            }
            if (!isNew) return
            section(tr("Repeat", "Ulangi"))
            val sw = Switch(this).apply {
                text = tr("Record this automatically", "Catat ini otomatis")
                textSize = 15f
                setTextColor(C.text)
                isChecked = vm.repeat
                setOnCheckedChangeListener { _, on -> vm.repeat = on; renderForm() }
            }
            form.add(sw, MATCH, WRAP)
            if (vm.repeat) {
                form.add(freqChips(), MATCH, WRAP, t = 8)
                form.add(label(tr("Next ones are added automatically on their date. You can edit or stop it under More › Recurring.",
                    "Transaksi berikutnya ditambahkan otomatis di tanggalnya. Ubah atau hentikan di Lainnya › Transaksi berulang."), 12.5f, C.text3), t = 6)
            }
            return
        }
        section(tr("Repeats", "Berulang"))
        form.add(freqChips())
        form.add(hbox {
            add(label(tr("Every", "Setiap"), 15f, C.text), WRAP, WRAP)
            add(secondaryButton("−") { if (vm.every > 1) { vm.every--; renderForm() } }, dp(48), WRAP, l = 12)
            add(label(vm.every.toString(), 17f, C.text, true, 1, Gravity.CENTER), dp(48), WRAP)
            add(secondaryButton("+") { if (vm.every < 99) { vm.every++; renderForm() } }, dp(48), WRAP)
            add(label(when (vm.freq) {
                Freq.DAILY -> tr("day(s)", "hari"); Freq.WEEKLY -> tr("week(s)", "minggu")
                Freq.MONTHLY -> tr("month(s)", "bulan"); else -> tr("year(s)", "tahun")
            }, 15f, C.text), 0, WRAP, 1f, l = 12)
        }, MATCH, WRAP, t = 10)

        section(tr("Ends", "Berakhir"))
        val ends = flow()
        ends.addView(chip(tr("Never", "Tidak pernah"), vm.endDate == null) { vm.endDate = null; renderForm() })
        ends.addView(chip("📅 " + (vm.endDate?.let { tr("On ", "Pada ") + D.day(it) } ?: tr("On a date", "Pada tanggal")), vm.endDate != null) {
            pickDate(vm.endDate ?: (vm.date + 365)) { vm.endDate = it; renderForm() }
        })
        form.add(ends)

        section(tr("Remind me before", "Ingatkan sebelumnya"))
        val rem = flow()
        for ((d, l) in listOf(0 to tr("Off", "Mati"), 1 to tr("1 day", "1 hari"), 3 to tr("3 days", "3 hari"), 7 to tr("7 days", "7 hari"))) {
            rem.addView(chip(l, vm.remindDays == d) { vm.remindDays = d; renderForm() })
        }
        form.add(rem)

        if (!isNew) {
            section(tr("Status", "Status"))
            form.add(Switch(this).apply {
                text = tr("Active (uncheck to pause)", "Aktif (matikan untuk jeda)")
                textSize = 15f
                setTextColor(C.text)
                isChecked = vm.active
                setOnCheckedChangeListener { _, on -> vm.active = on }
            }, MATCH, WRAP)
            form.add(label(tr("Changing the date or frequency restarts the schedule from the date above. Transactions already recorded stay as they are.",
                "Mengubah tanggal atau frekuensi akan memulai ulang jadwal dari tanggal di atas. Transaksi yang sudah tercatat tidak berubah."), 12.5f, C.text3), t = 8)
        }
    }

    private fun freqChips(): View {
        val f = flow()
        for (q in Freq.all) f.addView(chip(Freq.label(q), vm.freq == q) { vm.freq = q; renderForm() })
        return f
    }

    // ------------------------------------------------------------------ actions

    private fun save() {
        hideKeyboard()
        vm.amount = Money.parse(amountField.text)
        if (vm.amount <= 0) { amountField.error = tr("Enter an amount", "Masukkan jumlah"); amountField.requestFocus(); return }
        if (vm.type != TxType.TRANSFER && vm.categoryId == null) { toast(tr("Choose a category", "Pilih kategori")); return }
        val wallet = vm.walletId ?: run { toast(tr("Choose a wallet", "Pilih dompet")); return }
        if (vm.type == TxType.TRANSFER && (vm.toWalletId == null || vm.toWalletId == wallet)) {
            toast(tr("Choose a different destination wallet", "Pilih dompet tujuan yang berbeda")); return
        }
        if (vm.ruleMode && vm.endDate != null && vm.endDate!! < vm.date) { toast(tr("End date is before the first date", "Tanggal akhir sebelum tanggal pertama")); return }
        val ctx = applicationContext
        val catId = if (vm.type == TxType.TRANSFER) null else vm.categoryId
        val toWallet = if (vm.type == TxType.TRANSFER) vm.toWalletId else null
        val title = vm.title.trim()
        val note = vm.note.trim()
        Repo.async({
            var msg: String? = null
            if (!vm.ruleMode) {
                val now = System.currentTimeMillis()
                val tx = Tx(vm.id, vm.type, vm.amount, catId, wallet, toWallet, title, note, vm.date,
                    if (vm.time > 0) vm.time else now, vm.recurringId, vm.receipt)
                Repo.inTransaction {
                    if (vm.id == 0L) {
                        var rid: Long? = null
                        if (vm.repeat) rid = Repo.saveRecurring(Recurring(0, vm.type, vm.amount, catId, wallet, toWallet, title, note,
                            vm.freq, 1, vm.date, null, 1, true, 1, -1))
                        Repo.insertTx(tx.copy(recurringId = rid))
                    } else Repo.updateTx(tx)
                }
                if (vm.originalReceipt != null && vm.originalReceipt != vm.receipt) Receipts.delete(ctx, vm.originalReceipt)
                if (vm.repeat) RecurringEngine.run(D.today())
                if (vm.type == TxType.EXPENSE && catId != null) msg = BudgetAlert.check(ctx, catId, vm.date, notify = false)
                Prefs.lastWallet = wallet
            } else {
                val scheduleChanged = vm.id == 0L || vm.freq != vm.origFreq || vm.every != vm.origEvery || vm.date != vm.origNext
                Repo.saveRecurring(Recurring(vm.id, vm.type, vm.amount, catId, wallet, toWallet, title, note, vm.freq, vm.every,
                    if (scheduleChanged) vm.date else vm.ruleStart, vm.endDate, if (scheduleChanged) 0 else vm.ruleCount,
                    vm.active, vm.remindDays, if (scheduleChanged) -1 else vm.lastReminded))
                val created = Repo.txCount()
                RecurringEngine.run(D.today())
                if (Repo.txCount() > created) msg = tr("Recorded the occurrences that were already due.", "Transaksi yang sudah jatuh tempo telah dicatat.")
            }
            msg
        }) { msg ->
            vm.saved = true
            vm.tempReceipts.filter { it != vm.receipt }.forEach { Receipts.delete(ctx, it) }
            vm.tempReceipts.clear()
            toast(msg ?: tr("Saved", "Tersimpan"))
            finish()
        }
    }

    private fun delete() {
        if (vm.ruleMode) {
            confirm(tr("Delete this recurring rule?", "Hapus aturan berulang ini?"),
                tr("Future transactions won't be created. Transactions already recorded stay.", "Transaksi berikutnya tidak akan dibuat. Transaksi yang sudah tercatat tetap ada."),
                tr("Delete", "Hapus"), danger = true) {
                Repo.async({ Repo.deleteRecurring(vm.id) }) { vm.saved = true; finish() }
            }
        } else {
            confirm(tr("Delete this transaction?", "Hapus transaksi ini?"), tr("This cannot be undone.", "Tindakan ini tidak bisa dibatalkan."),
                tr("Delete", "Hapus"), danger = true) {
                val ctx = applicationContext
                Repo.async({ Repo.deleteTx(vm.id); Receipts.delete(ctx, vm.originalReceipt) }) {
                    vm.saved = true
                    toast(tr("Deleted", "Dihapus"))
                    finish()
                }
            }
        }
    }

    private fun takePhoto() {
        val f = FilesProvider.cameraFile(this)
        f.delete()
        val uri = FilesProvider.cameraUri()
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("", uri)
        Lock.allowExternal()
        try { startActivityForResult(i, REQ_CAMERA) } catch (_: Throwable) { Lock.externalUntil = 0; toast(tr("No camera app found", "Aplikasi kamera tidak ditemukan")) }
    }

    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        Lock.allowExternal()
        try { startActivityForResult(i, REQ_GALLERY) } catch (_: Throwable) { Lock.externalUntil = 0; toast(tr("No gallery app found", "Aplikasi galeri tidak ditemukan")) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val ctx = applicationContext
        val open: (() -> java.io.InputStream?)? = when (requestCode) {
            REQ_CAMERA -> { { FilesProvider.cameraFile(ctx).takeIf { it.length() > 0 }?.inputStream() } }
            REQ_GALLERY -> data?.data?.let { uri -> { ctx.contentResolver.openInputStream(uri) } }
            else -> null
        }
        if (open == null) return
        Repo.async({ Receipts.save(ctx, open) }) { name ->
            vm.receipt = name
            vm.tempReceipts += name
            if (::form.isInitialized) renderReceipt()
        }
    }

    override fun onDestroy() {
        if (isFinishing && !vm.saved) {
            val ctx = applicationContext
            vm.tempReceipts.forEach { Receipts.delete(ctx, it) }
            vm.tempReceipts.clear()
        }
        super.onDestroy()
    }
}
