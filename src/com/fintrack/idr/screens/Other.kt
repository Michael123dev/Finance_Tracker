package com.fintrack.idr.screens

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.fintrack.idr.R
import com.fintrack.idr.data.ReceiptImage
import com.fintrack.idr.data.Receipts
import com.fintrack.idr.data.Repo
import com.fintrack.idr.data.TxFilter
import com.fintrack.idr.data.TxRow
import com.fintrack.idr.ui.BaseActivity
import com.fintrack.idr.ui.C
import com.fintrack.idr.ui.Icon
import com.fintrack.idr.ui.MATCH
import com.fintrack.idr.ui.WRAP
import com.fintrack.idr.ui.add
import com.fintrack.idr.ui.addF
import com.fintrack.idr.ui.amount
import com.fintrack.idr.ui.card
import com.fintrack.idr.ui.choose
import com.fintrack.idr.ui.clickable
import com.fintrack.idr.ui.confirm
import com.fintrack.idr.ui.dialog
import com.fintrack.idr.ui.dialogBody
import com.fintrack.idr.ui.dp
import com.fintrack.idr.ui.dpf
import com.fintrack.idr.ui.hbox
import com.fintrack.idr.ui.iconButton
import com.fintrack.idr.ui.input
import com.fintrack.idr.ui.label
import com.fintrack.idr.ui.menuRow
import com.fintrack.idr.ui.oval
import com.fintrack.idr.ui.pad
import com.fintrack.idr.ui.rounded
import com.fintrack.idr.ui.scroll
import com.fintrack.idr.ui.secondaryButton
import com.fintrack.idr.ui.showKeyboard
import com.fintrack.idr.ui.toast
import com.fintrack.idr.ui.topBar
import com.fintrack.idr.ui.txRow
import com.fintrack.idr.ui.vbox
import com.fintrack.idr.util.Auth
import com.fintrack.idr.util.D
import com.fintrack.idr.util.L
import com.fintrack.idr.util.Lang
import com.fintrack.idr.util.Lock
import com.fintrack.idr.util.Money
import com.fintrack.idr.util.Notif
import com.fintrack.idr.util.Prefs
import com.fintrack.idr.util.Scheduler
import com.fintrack.idr.util.Security
import com.fintrack.idr.util.tr
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.util.Locale

// =================================================================== Calendar

class CalendarActivity : BaseActivity() {
    private var ym = YearMonth.now()
    private var sel = D.today()
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = vbox { pad(12, 4, 12, 28) }
        setContentView(vbox {
            add(topBar(tr("Calendar", "Kalender")))
            add(scroll(body), MATCH, 0, 1f)
        })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val m = ym
        val day = sel
        Repo.async({ Repo.daily(D.first(m), D.last(m)) to Repo.txRows(TxFilter(from = day, to = day)) }) { (daily, rows) -> render(daily, rows) }
    }

    private fun render(daily: Map<Long, LongArray>, rows: List<TxRow>) {
        body.removeAllViews()
        body.add(hbox {
            background = rounded(C.card, dpf(16f))
            add(iconButton(Icon.CHEV_L, C.text, desc = tr("Previous month", "Bulan sebelumnya")) { ym = ym.minusMonths(1); load() }, dp(48), dp(48))
            add(label(D.month(ym), 16f, C.text, true, 1, Gravity.CENTER), 0, WRAP, 1f)
            add(iconButton(Icon.CHEV_R, C.text, desc = tr("Next month", "Bulan berikutnya")) { ym = ym.plusMonths(1); load() }, dp(48), dp(48))
        }, b = 10)

        val fdw = WeekFields.of(L.locale()).firstDayOfWeek
        val grid = vbox {
            background = rounded(C.card, dpf(18f))
            pad(6, 8)
        }
        grid.add(hbox {
            for (k in 0 until 7) add(label(D.weekday(fdw.plus(k.toLong())), 12f, C.text3, true, 1, Gravity.CENTER), 0, WRAP, 1f)
        }, b = 4)
        val first = ym.atDay(1)
        val offset = (first.dayOfWeek.value - fdw.value + 7) % 7
        val len = ym.lengthOfMonth()
        val rowsCount = (offset + len + 6) / 7
        val today = D.today()
        for (r in 0 until rowsCount) {
            val row = hbox()
            for (c in 0 until 7) {
                val dayNum = r * 7 + c - offset + 1
                if (dayNum < 1 || dayNum > len) { row.add(android.view.View(this), 0, dp(62), 1f); continue }
                val epoch = ym.atDay(dayNum).toEpochDay()
                val v = daily[epoch]
                val cell = vbox {
                    gravity = Gravity.CENTER_HORIZONTAL
                    pad(0, 6, 0, 4)
                    val isSel = epoch == sel
                    background = if (isSel) rounded(C.brandSoft, dpf(12f), C.accent, dp(2)) else null
                    add(label(dayNum.toString(), 14f, if (epoch == today) C.accent else C.text, epoch == today || isSel, 1, Gravity.CENTER), MATCH, WRAP)
                    if (v != null && v[0] > 0) add(label("+" + Money.compact(v[0]), 10f, C.income, false, 1, Gravity.CENTER), MATCH, WRAP)
                    if (v != null && v[1] > 0) add(label("-" + Money.compact(v[1]), 10f, C.expense, false, 1, Gravity.CENTER), MATCH, WRAP)
                    setOnClickListener { sel = epoch; load() }
                    contentDescription = D.day(epoch)
                }
                row.add(cell, 0, dp(62), 1f, l = 1, r = 1)
            }
            grid.add(row, MATCH, WRAP, t = 2)
        }
        body.add(grid, b = 14)

        val inc = rows.filter { it.tx.type == com.fintrack.idr.util.TxType.INCOME }.sumOf { it.tx.amount }
        val exp = rows.filter { it.tx.type == com.fintrack.idr.util.TxType.EXPENSE }.sumOf { it.tx.amount }
        body.add(hbox {
            val col = vbox()
            col.add(label(D.relative(sel), 17f, C.text, true, 1))
            col.add(label(tr("In ", "Masuk ") + Money.fmt(inc) + " · " + tr("Out ", "Keluar ") + Money.fmt(exp), 13f, C.text2, false, 1))
            add(col, 0, WRAP, 1f)
            add(secondaryButton("+ " + tr("Add", "Tambah")) {
                startActivity(Intent(this@CalendarActivity, TxEditActivity::class.java).putExtra(TxEditActivity.EXTRA_DATE, sel))
            }, WRAP, WRAP)
        }, b = 8)
        if (rows.isEmpty()) body.add(label(tr("No transactions on this day.", "Tidak ada transaksi di hari ini."), 14f, C.text2).pad(4, 8))
        else body.add(card(4) { for (r in rows) add(txRow(r) { TxEditActivity.edit(this@CalendarActivity, r.tx.id) }) })
    }
}

// =================================================================== Settings

class SettingsActivity : BaseActivity() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = vbox { pad(16, 4, 16, 28) }
        setContentView(vbox {
            add(topBar(tr("Settings", "Pengaturan")))
            add(scroll(body), MATCH, 0, 1f)
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun section(t: String) = body.add(label(t, 13f, C.text2, true).pad(4, 14, 0, 6))

    private fun switchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout = hbox {
        pad(14, 10)
        minimumHeight = dp(56)
        val col = vbox()
        col.add(label(title, 15f, C.text, true, 2))
        if (sub != null) col.add(label(sub, 13f, C.text2, false, 3), t = 2)
        add(col, 0, WRAP, 1f, r = 8)
        val sw = Switch(context).apply { isChecked = checked; setOnCheckedChangeListener { _, b -> onChange(b) } }
        add(sw, WRAP, WRAP)
        clickable(14)
        setOnClickListener { sw.toggle() }
    }

    private fun render() {
        body.removeAllViews()
        val themes = listOf(tr("Follow phone", "Ikuti ponsel"), tr("Light", "Terang"), tr("Dark", "Gelap"))

        section(tr("Appearance", "Tampilan"))
        body.add(card(4) {
            add(menuRow(Icon.MOON, tr("Theme", "Tema"), null, themes[Prefs.theme]) {
                choose(tr("Theme", "Tema"), themes, Prefs.theme) { i -> Prefs.theme = i; recreate() }
            })
            add(menuRow(Icon.GLOBE, tr("Language", "Bahasa"), null, Lang.name(Prefs.language)) {
                val modes = listOf(L.SYSTEM, L.EN, L.ID)
                choose(tr("Language", "Bahasa"), modes.map { Lang.name(it) }, modes.indexOf(Prefs.language)) { i ->
                    Lang.set(applicationContext, modes[i])
                    recreate() // other open screens rebuild when they come back into view
                }
            })
        })

        section(tr("Security", "Keamanan"))
        body.add(card(4) {
            val ready = Auth.fingerprintReady(this@SettingsActivity)
            val sensor = ready || Auth.hasSensor(this@SettingsActivity)
            val backup = if (Security.enabled) tr("app PIN", "PIN aplikasi") else tr("phone screen lock", "kunci layar ponsel")
            val sub = when {
                Prefs.biometric -> tr("On · fingerprint needed to open the app. Backup: $backup", "Aktif · sidik jari diperlukan untuk membuka aplikasi. Cadangan: $backup")
                !sensor -> tr("This phone has no fingerprint sensor", "Ponsel ini tidak punya sensor sidik jari")
                !ready -> tr("No fingerprint registered on this phone yet", "Belum ada sidik jari terdaftar di ponsel ini")
                else -> tr("Off · unlock the app with your fingerprint", "Mati · buka aplikasi dengan sidik jari")
            }
            add(switchRow(tr("Fingerprint lock", "Kunci sidik jari"), sub, Prefs.biometric) { on ->
                render() // keep showing the real state until the check below succeeds
                if (on) enableFingerprint() else disableFingerprint()
            })
            if (!Security.enabled) {
                add(menuRow(Icon.LOCK, tr("App PIN", "PIN aplikasi"),
                    if (Prefs.biometric) tr("Off · optional backup when fingerprint fails", "Mati · cadangan opsional jika sidik jari gagal")
                    else tr("Off · protect the app with a 4–6 digit PIN", "Mati · lindungi aplikasi dengan PIN 4–6 angka")) { newPin() })
            } else {
                add(menuRow(Icon.LOCK, tr("App PIN", "PIN aplikasi"), tr("On", "Aktif"), tr("Change", "Ubah")) {
                    choose(tr("App PIN", "PIN aplikasi"), listOf(tr("Change PIN", "Ganti PIN"), tr("Turn off PIN", "Matikan PIN")), -1) { i ->
                        verifyPin {
                            if (i == 0) newPin() else {
                                Prefs.clearPin()
                                toast(if (Prefs.biometric) tr("PIN removed. Your phone's screen lock is now the backup for fingerprint.",
                                    "PIN dihapus. Kunci layar ponsel kini menjadi cadangan sidik jari.")
                                else tr("App lock turned off", "Kunci aplikasi dimatikan"))
                                recreate()
                            }
                        }
                    }
                })
            }
            if (Lock.enabled) {
                val opts = listOf(0, 30, 60, 300)
                val names = listOf(tr("Immediately", "Langsung"), tr("After 30 seconds", "Setelah 30 detik"), tr("After 1 minute", "Setelah 1 menit"), tr("After 5 minutes", "Setelah 5 menit"))
                add(menuRow(Icon.INFO, tr("Lock when away", "Kunci saat ditinggal"), null, names[maxOf(0, opts.indexOf(Prefs.lockAfterSec))]) {
                    choose(tr("Lock when away", "Kunci saat ditinggal"), names, opts.indexOf(Prefs.lockAfterSec)) { i -> Prefs.lockAfterSec = opts[i]; render() }
                })
            }
        })

        section(tr("Notifications", "Notifikasi"))
        body.add(card(4) {
            if (!Notif.allowed(this@SettingsActivity)) {
                add(menuRow(Icon.BELL, tr("Notifications are blocked", "Notifikasi diblokir"), tr("Tap to allow reminders and budget alerts", "Ketuk untuk mengizinkan pengingat dan peringatan anggaran")) { askNotifications() })
            }
            val t = Prefs.reminderTime
            add(switchRow(tr("Daily reminder", "Pengingat harian"),
                tr("At %02d:%02d if nothing was recorded that day", "Pukul %02d:%02d jika belum ada catatan hari itu").format(t / 60, t % 60), Prefs.dailyReminder) {
                Prefs.dailyReminder = it; Scheduler.scheduleReminder(applicationContext)
                if (it) askNotifications()
                render()
            })
            if (Prefs.dailyReminder) add(menuRow(Icon.BELL, tr("Reminder time", "Waktu pengingat"), null, "%02d:%02d".format(t / 60, t % 60)) {
                TimePickerDialog(this@SettingsActivity, R.style.PickerTheme, { _, h, m ->
                    Prefs.reminderTime = h * 60 + m; Scheduler.scheduleReminder(applicationContext); render()
                }, t / 60, t % 60, android.text.format.DateFormat.is24HourFormat(this@SettingsActivity)).show()
            })
            add(switchRow(tr("Budget alerts", "Peringatan anggaran"),
                tr("When a category reaches ${Prefs.budgetThreshold}% and when it's exceeded", "Saat kategori mencapai ${Prefs.budgetThreshold}% dan saat terlampaui"), Prefs.budgetAlerts) {
                Prefs.budgetAlerts = it; if (it) askNotifications()
            })
            val th = listOf(50, 70, 80, 90)
            add(menuRow(Icon.BUDGET, tr("Alert threshold", "Batas peringatan"), null, "${Prefs.budgetThreshold}%") {
                choose(tr("Alert threshold", "Batas peringatan"), th.map { "$it%" }, th.indexOf(Prefs.budgetThreshold)) { i -> Prefs.budgetThreshold = th[i]; render() }
            })
            add(switchRow(tr("Upcoming payment reminders", "Pengingat pembayaran mendatang"),
                tr("Before recurring bills are due (set per item)", "Sebelum tagihan berulang jatuh tempo (diatur per item)"), Prefs.recurringReminders) {
                Prefs.recurringReminders = it; if (it) askNotifications()
            })
        })

        section(tr("Data", "Data"))
        body.add(card(4) {
            add(menuRow(Icon.EXPORT, tr("Export to Excel / CSV", "Ekspor ke Excel / CSV")) { ExportFlow.start(this@SettingsActivity) })
            add(menuRow(Icon.BACKUP, tr("Back up data", "Cadangkan data"), tr("Includes receipt photos", "Termasuk foto struk")) { BackupFlow.backup(this@SettingsActivity) })
            add(menuRow(Icon.RESTORE, tr("Restore from backup", "Pulihkan dari cadangan")) { BackupFlow.restore(this@SettingsActivity) })
            add(menuRow(Icon.TRASH, tr("Delete all data", "Hapus semua data"), tr("Start over with an empty app", "Mulai ulang dari awal")) { wipe() })
        })

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
        body.add(label(tr("Finance Tracker $version · Currency: Indonesian Rupiah (IDR)\nYour data is stored only on this phone. No account, no internet needed.",
            "Catatan Keuangan $version · Mata uang: Rupiah (IDR)\nData hanya tersimpan di ponsel ini. Tanpa akun, tanpa internet."), 12.5f, C.text3).pad(4, 18, 4, 0))
    }

    private fun askNotifications() {
        if (Notif.allowed(this)) return
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotifPermission) {
            Prefs.askedNotifPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
        } else if (Build.VERSION.SDK_INT >= 33 && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
        } else {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (_: Throwable) { }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun pinDialog(title: String, onOk: (String) -> Unit) {
        val et = input("••••").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(6))
            textSize = 22f
            gravity = Gravity.CENTER
        }
        val d = dialog().setTitle(title).setView(dialogBody { add(et) })
            .setPositiveButton(tr("OK", "OK"), null).setNegativeButton(tr("Cancel", "Batal"), null).create()
        d.setOnShowListener {
            showKeyboard(et)
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = et.text.toString()
                if (p.length !in 4..6) { et.error = tr("Use 4 to 6 digits", "Gunakan 4 sampai 6 angka"); return@setOnClickListener }
                d.dismiss(); onOk(p)
            }
        }
        d.show()
    }

    private fun verifyPin(onOk: () -> Unit) = pinDialog(tr("Enter your current PIN", "Masukkan PIN saat ini")) { p ->
        if (Security.check(p)) onOk() else toast(tr("Wrong PIN", "PIN salah"))
    }

    private fun newPin() {
        pinDialog(tr("Create a 4–6 digit PIN", "Buat PIN 4–6 angka")) { p1 ->
            pinDialog(tr("Enter the same PIN again", "Masukkan PIN yang sama lagi")) { p2 ->
                if (p1 != p2) { toast(tr("PINs don't match. Try again.", "PIN tidak sama. Coba lagi.")); return@pinDialog }
                Security.setPin(p1)
                Prefs.pinLength = p1.length
                Lock.unlocked = true
                toast(tr("App lock is on. There's no way to recover a forgotten PIN — keep a backup.",
                    "Kunci aplikasi aktif. PIN yang terlupa tidak bisa dipulihkan — simpan cadangan data."))
                recreate()
            }
        }
    }

    private fun enableFingerprint() {
        if (!Auth.fingerprintReady(this)) {
            if (!Auth.hasSensor(this)) {
                toast(tr("This phone has no fingerprint sensor. Use an app PIN instead.", "Ponsel ini tidak punya sensor sidik jari. Gunakan PIN aplikasi."))
                return
            }
            confirm(tr("Register a fingerprint first", "Daftarkan sidik jari dulu"),
                tr("Add a fingerprint in your phone's Settings, then come back and turn this on.",
                    "Tambahkan sidik jari di Pengaturan ponsel, lalu kembali ke sini dan aktifkan."),
                tr("Open Settings", "Buka Pengaturan")) { Auth.openEnroll(this) }
            return
        }
        if (!Security.enabled && !Auth.deviceSecure(this)) {
            toast(tr("Set an app PIN first so you have a backup.", "Atur PIN aplikasi dulu sebagai cadangan."))
            return
        }
        val done = {
            Prefs.biometric = true
            Lock.unlocked = true
            toast(tr("Fingerprint lock is on", "Kunci sidik jari aktif"))
            recreate()
        }
        if (Build.VERSION.SDK_INT >= 28) {
            Auth.prompt(this, tr("Confirm your fingerprint", "Konfirmasi sidik jari"), tr("Cancel", "Batal"), done, {}) { toast(it) }
        } else done()
    }

    private fun disableFingerprint() = confirmIdentity {
        Prefs.biometric = false
        toast(if (Security.enabled) tr("Fingerprint lock is off. The app PIN still protects the app.", "Kunci sidik jari mati. PIN aplikasi tetap melindungi aplikasi.")
        else tr("Fingerprint lock is off", "Kunci sidik jari dimatikan"))
        recreate()
    }

    /** Fingerprint first, then the app PIN or the phone's screen lock. */
    private fun confirmIdentity(onOk: () -> Unit) {
        val title = tr("Confirm it's you", "Konfirmasi bahwa ini kamu")
        val fallback = {
            when {
                Security.enabled -> verifyPin(onOk)
                Auth.deviceSecure(this) -> Auth.deviceCredential(this, title, onOk)
                else -> onOk()
            }
        }
        if (Prefs.biometric && Auth.fingerprintReady(this) && Build.VERSION.SDK_INT >= 28) {
            Auth.prompt(this, title, Auth.fallbackLabel(), onOk, fallback) { toast(it) }
        } else fallback()
    }

    private fun wipe() {
        confirm(tr("Delete all data?", "Hapus semua data?"),
            tr("Every transaction, wallet, budget, goal and receipt on this phone will be erased. Make a backup first if you might need it.",
                "Semua transaksi, dompet, anggaran, target, dan struk di ponsel ini akan dihapus. Buat cadangan dulu jika mungkin perlu."),
            tr("Continue", "Lanjut"), danger = true) {
            confirm(tr("Are you absolutely sure?", "Benar-benar yakin?"), tr("This cannot be undone.", "Tindakan ini tidak bisa dibatalkan."),
                tr("Delete everything", "Hapus semuanya"), danger = true) {
                Repo.async({ Repo.wipeAll() }) {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (Auth.onResult(requestCode, resultCode)) return
        if (ExportFlow.onResult(this, requestCode, resultCode, data)) return
        BackupFlow.onResult(this, requestCode, resultCode, data)
    }
}

// =================================================================== Lock screen

/**
 * Shown when the app is locked. With an app PIN: keypad (+ fingerprint button if fingerprint lock is on).
 * Fingerprint only: fingerprint prompt with the phone's screen lock as the fallback.
 */
class LockActivity : BaseActivity() {
    private val entered = StringBuilder()
    private lateinit var dots: LinearLayout
    private lateinit var msg: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var promptedBiometric = false
    private var legacyCancel: CancellationSignal? = null
    private val pinMode get() = Security.enabled

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dots = hbox { gravity = Gravity.CENTER }
        msg = label("", 14f, C.text2, false, 3, Gravity.CENTER)
        val content = vbox {
            gravity = Gravity.CENTER_HORIZONTAL
            pad(24, 48, 24, 24)
            add(ImageView(context).apply { setImageDrawable(Icon(Icon.LOCK, C.onBrand)); background = oval(C.brand); setPadding(dp(16), dp(16), dp(16), dp(16)) }, dp(64), dp(64))
        }
        if (pinMode) {
            content.add(label(tr("Enter PIN", "Masukkan PIN"), 22f, C.text, true, 1, Gravity.CENTER), WRAP, WRAP, t = 16)
            content.add(dots, MATCH, dp(24), t = 18)
            content.add(msg, MATCH, WRAP, t = 10)
            content.add(keypad(), WRAP, WRAP, t = 16)
            content.add(label(tr("Forgot PIN?", "Lupa PIN?"), 14f, C.accent, true).pad(12, 10).apply { setOnClickListener { forgot() } }, WRAP, WRAP, t = 12)
        } else {
            content.add(label(tr("Finance Tracker is locked", "Catatan Keuangan terkunci"), 22f, C.text, true, 2, Gravity.CENTER), MATCH, WRAP, t = 16)
            content.add(label(tr("Unlock with your fingerprint to continue.", "Buka dengan sidik jari untuk melanjutkan."), 14f, C.text2, false, 2, Gravity.CENTER), MATCH, WRAP, t = 6)
            content.add(iconButton(Icon.FINGERPRINT, C.onBrand, 96, tr("Unlock with fingerprint", "Buka dengan sidik jari")) { biometric() }.apply {
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33000000), oval(C.brand), null)
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }, dp(96), dp(96), t = 36)
            content.add(msg, MATCH, WRAP, t = 16)
            content.add(secondaryButton(tr("Use phone screen lock", "Pakai kunci layar ponsel")) { useScreenLock() }, WRAP, WRAP, t = 24)
        }
        val root = FrameLayout(this)
        root.addF(content, MATCH, MATCH)
        setContentView(scroll(root))
        if (pinMode) renderDots()
    }

    private fun keypad(): LinearLayout {
        val pad = vbox { gravity = Gravity.CENTER_HORIZONTAL }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del")
        for (r in 0 until 4) {
            val row = hbox { gravity = Gravity.CENTER }
            for (c in 0 until 3) {
                val k = keys[r * 3 + c]
                val v = when (k) {
                    "del" -> iconButton(Icon.BACKSPACE, C.text, 72, tr("Delete", "Hapus")) { if (entered.isNotEmpty()) { entered.setLength(entered.length - 1); renderDots() } }
                    "bio" -> if (Prefs.biometric && Auth.fingerprintReady(this)) iconButton(Icon.FINGERPRINT, C.text, 72, tr("Unlock with fingerprint", "Buka dengan sidik jari")) { biometric() }
                    else android.view.View(this)
                    else -> label(k, 28f, C.text, false, 1, Gravity.CENTER).apply {
                        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(C.ripple), oval(C.card2), null)
                        setOnClickListener { press(k) }
                    }
                }
                row.add(v, dp(72), dp(72), l = 12, r = 12)
            }
            pad.add(row, WRAP, WRAP, t = 10)
        }
        return pad
    }

    override fun onResume() {
        super.onResume()
        if (!Lock.required()) { finish(); return }
        // Never lock someone out of their own data when no way to unlock is left on the phone.
        if (!pinMode && !Auth.fingerprintReady(this) && !Auth.deviceSecure(this)) {
            Prefs.biometric = false
            Lock.unlocked = true
            android.widget.Toast.makeText(this, tr("App lock was turned off: this phone no longer has a fingerprint or screen lock.",
                "Kunci aplikasi dimatikan: ponsel ini tidak lagi punya sidik jari atau kunci layar."), android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (pinMode) updateLockout()
        if (Prefs.biometric && Auth.fingerprintReady(this)) {
            if (Build.VERSION.SDK_INT >= 28) {
                if (!promptedBiometric) {
                    promptedBiometric = true
                    handler.postDelayed({ if (!isFinishing && Lock.required()) biometric() }, 250)
                }
            } else startLegacy()
        } else if (!pinMode) {
            showMsg(tr("Fingerprint isn't available right now. Use your phone's screen lock.", "Sidik jari tidak tersedia saat ini. Pakai kunci layar ponsel."), false)
        }
    }

    override fun onPause() {
        legacyCancel?.cancel()
        legacyCancel = null
        super.onPause()
    }

    private fun showMsg(text: CharSequence, error: Boolean) {
        msg.text = text
        msg.setTextColor(if (error) C.expense else C.text2)
    }

    private fun renderDots() {
        dots.removeAllViews()
        val n = maxOf(Prefs.pinLength, entered.length)
        for (i in 0 until n) {
            dots.add(android.view.View(this).apply { background = if (i < entered.length) oval(C.text) else oval(C.card2) }, dp(14), dp(14), l = 8, r = 8)
        }
    }

    private fun lockedOut(): Boolean = System.currentTimeMillis() < Prefs.lockoutUntil

    private fun updateLockout() {
        if (lockedOut()) {
            val s = (Prefs.lockoutUntil - System.currentTimeMillis()) / 1000 + 1
            showMsg(tr("Too many attempts. Try again in ${s}s.", "Terlalu banyak percobaan. Coba lagi dalam ${s} detik."), true)
            handler.postDelayed({ updateLockout() }, 1000)
        } else if (msg.text.startsWith(tr("Too many", "Terlalu banyak"))) {
            msg.text = ""
        }
    }

    private fun press(k: String) {
        if (lockedOut()) return
        if (entered.length >= 6) return
        entered.append(k)
        renderDots()
        if (entered.length >= Prefs.pinLength) handler.postDelayed({ check() }, 80)
    }

    private fun check() {
        val p = entered.toString()
        if (Security.check(p)) { unlock(); return }
        entered.setLength(0)
        renderDots()
        val n = Prefs.failedAttempts + 1
        Prefs.failedAttempts = n
        if (n >= 5) {
            val secs = minOf(900L, 30L shl minOf(5, n - 5))
            Prefs.lockoutUntil = System.currentTimeMillis() + secs * 1000
            updateLockout()
        } else {
            showMsg(tr("Wrong PIN. ${5 - n} attempts left before a pause.", "PIN salah. ${5 - n} percobaan lagi sebelum jeda."), true)
        }
    }

    private fun unlock() {
        Prefs.failedAttempts = 0
        Lock.unlocked = true
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun biometric() {
        if (Build.VERSION.SDK_INT < 28) { startLegacy(); return }
        Auth.prompt(this, tr("Unlock Finance Tracker", "Buka Catatan Keuangan"), Auth.fallbackLabel(),
            onSuccess = { unlock() },
            onFallback = { if (!pinMode) useScreenLock() },
            onError = { showMsg(it, true) })
    }

    private fun useScreenLock() = Auth.deviceCredential(this, tr("Unlock Finance Tracker", "Buka Catatan Keuangan")) { unlock() }

    /** Android 8.x: listen on the fingerprint sensor while this screen is visible. */
    @Suppress("DEPRECATION")
    private fun startLegacy() {
        if (legacyCancel != null) return
        val fm = Auth.legacyManager(this) ?: return
        val cs = CancellationSignal()
        legacyCancel = cs
        showMsg(tr("Touch the fingerprint sensor", "Sentuh sensor sidik jari"), false)
        try {
            fm.authenticate(null, cs, 0, object : FingerprintManager.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult?) {
                    legacyCancel = null
                    unlock()
                }
                override fun onAuthenticationFailed() {
                    showMsg(tr("Fingerprint not recognized. Try again.", "Sidik jari tidak dikenali. Coba lagi."), true)
                }
                override fun onAuthenticationHelp(helpCode: Int, helpString: CharSequence?) {
                    if (helpString != null) showMsg(helpString, false)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    legacyCancel = null
                    if (errorCode != FingerprintManager.FINGERPRINT_ERROR_CANCELED) showMsg(errString ?: "", true)
                }
            }, null)
        } catch (_: Throwable) {
            legacyCancel = null
        }
    }

    private fun forgot() {
        confirm(tr("Forgot your PIN?", "Lupa PIN?"),
            tr("Your data is only on this phone, so a PIN can't be recovered. The only option is to erase everything and start over (then restore a backup if you have one).",
                "Data hanya ada di ponsel ini, jadi PIN tidak bisa dipulihkan. Satu-satunya pilihan adalah menghapus semua data dan mulai ulang (lalu pulihkan cadangan jika ada)."),
            tr("Erase everything", "Hapus semuanya"), danger = true) {
            confirm(tr("Erase all data?", "Hapus semua data?"), tr("This cannot be undone.", "Tindakan ini tidak bisa dibatalkan."), tr("Erase", "Hapus"), danger = true) {
                Repo.async({ Repo.wipeAll(); Prefs.clearSecurity() }) {
                    Lock.unlocked = true
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Auth.onResult(requestCode, resultCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}

// =================================================================== Receipt viewer

class ReceiptActivity : BaseActivity() {
    companion object {
        /** Show the stored photo of this transaction. */
        const val EXTRA_TX = "tx_id"
        /** Show [pending]: a photo picked in the edit screen and not saved yet. */
        const val EXTRA_PENDING = "pending"
        @Volatile var pending: ReceiptImage? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF000000.toInt()
        val txId = intent.getLongExtra(EXTRA_TX, 0L)
        val held = if (intent.getBooleanExtra(EXTRA_PENDING, false)) pending else null
        if (txId <= 0L && held == null) { finish(); return }
        val img = ZoomImageView(this)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addF(img)
        root.addF(iconButton(Icon.CLOSE, 0xFFFFFFFF.toInt(), desc = tr("Close", "Tutup")) { finish() }, dp(48), dp(48), Gravity.TOP or Gravity.END, t = 8, r = 8)
        setContentView(root)
        val max = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
        Repo.async({
            val src = held ?: Repo.receipt(txId)
            src?.let { Receipts.decode(it.bytes, max) }
        }) { bmp ->
            if (bmp == null) { toast(tr("This receipt photo can't be shown.", "Foto struk ini tidak bisa ditampilkan.")); finish() } else img.setBitmap(bmp)
        }
    }
}

/** ImageView with pinch-to-zoom, drag and double-tap zoom. */
class ZoomImageView(ctx: Context) : ImageView(ctx) {
    private val m = Matrix()
    private var base = 1f
    private var scale = 1f
    private var bmpW = 0
    private var bmpH = 0

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor
            val ns = (scale * f).coerceIn(1f, 6f)
            val real = ns / scale
            scale = ns
            m.postScale(real, real, d.focusX, d.focusY)
            clamp(); imageMatrix = m
            return true
        }
    })
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            m.postTranslate(-dx, -dy); clamp(); imageMatrix = m; return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1.01f) fit() else { scale = 2.5f; m.postScale(2.5f, 2.5f, e.x, e.y); clamp(); imageMatrix = m }
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    fun setBitmap(b: Bitmap) {
        bmpW = b.width; bmpH = b.height
        setImageBitmap(b)
        post { fit() }
    }

    private fun fit() {
        if (bmpW == 0 || width == 0) return
        base = minOf(width.toFloat() / bmpW, height.toFloat() / bmpH)
        scale = 1f
        m.reset()
        m.postScale(base, base)
        m.postTranslate((width - bmpW * base) / 2f, (height - bmpH * base) / 2f)
        imageMatrix = m
    }

    private fun clamp() {
        val r = RectF(0f, 0f, bmpW.toFloat(), bmpH.toFloat())
        m.mapRect(r)
        var dx = 0f
        var dy = 0f
        if (r.width() <= width) dx = (width - r.width()) / 2f - r.left
        else if (r.left > 0) dx = -r.left else if (r.right < width) dx = width - r.right
        if (r.height() <= height) dy = (height - r.height()) / 2f - r.top
        else if (r.top > 0) dy = -r.top else if (r.bottom < height) dy = height - r.bottom
        m.postTranslate(dx, dy)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit()
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }
}

