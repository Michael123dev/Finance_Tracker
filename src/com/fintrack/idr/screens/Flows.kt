package com.fintrack.idr.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.fintrack.idr.data.Backup
import com.fintrack.idr.data.Export
import com.fintrack.idr.data.Repo
import com.fintrack.idr.data.TxFilter
import com.fintrack.idr.ui.choose
import com.fintrack.idr.ui.confirm
import com.fintrack.idr.ui.toast
import com.fintrack.idr.util.D
import com.fintrack.idr.util.Lock
import com.fintrack.idr.util.TxType
import com.fintrack.idr.util.tr
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Export to Excel/CSV through the system "save as" screen (no storage permission needed). */
object ExportFlow {
    const val REQ = 501
    private var from: Long? = null
    private var to: Long? = null
    private var label = ""
    private var xlsx = true

    /** Lets the user pick a period (defaulting around [ym]) and a format, then asks where to save. */
    fun start(a: Activity, ym: YearMonth = YearMonth.now()) {
        val options = listOf(
            D.month(ym),
            tr("Last 3 months", "3 bulan terakhir"),
            tr("This year", "Tahun ini") + " (${LocalDate.now().year})",
            tr("All time", "Semua waktu"),
        )
        a.choose(tr("Export which period?", "Ekspor periode apa?"), options, 0) { i ->
            val now = YearMonth.now()
            when (i) {
                0 -> { from = D.first(ym); to = D.last(ym); label = D.month(ym) }
                1 -> { from = D.first(now.minusMonths(2)); to = D.last(now); label = D.month(now.minusMonths(2)) + " – " + D.month(now) }
                2 -> { from = D.first(YearMonth.of(now.year, 1)); to = D.last(YearMonth.of(now.year, 12)); label = now.year.toString() }
                else -> { from = null; to = null; label = tr("All time", "Semua waktu") }
            }
            a.choose(tr("File format", "Format file"), listOf("Excel (.xlsx)", "CSV (.csv)"), 0) { f ->
                xlsx = f == 0
                val stamp = DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDate.now())
                val name = "finance-" + (if (i == 0) "%04d-%02d".format(ym.year, ym.monthValue) else stamp) + if (xlsx) ".xlsx" else ".csv"
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(if (xlsx) "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" else "text/csv")
                    .putExtra(Intent.EXTRA_TITLE, name)
                Lock.allowExternal()
                try { a.startActivityForResult(intent, REQ) } catch (_: Throwable) { Lock.externalUntil = 0;
                    a.toast(tr("No file manager available", "Tidak ada pengelola file"))
                }
            }
        }
    }

    fun onResult(a: Activity, req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ) return false
        val uri: Uri = data?.data ?: return true
        if (res != Activity.RESULT_OK) return true
        val f = from; val t = to; val asX = xlsx; val lbl = label
        Repo.async({
            val rows = Repo.txRows(TxFilter(from = f, to = t)).reversed() // oldest first reads better in a sheet
            val bytes = if (asX) {
                val start = f ?: Repo.firstTxDate() ?: D.today()
                val end = t ?: D.today()
                val months = ArrayList<Export.MonthSum>()
                var ym = D.ym(start)
                val last = D.ym(end)
                while (!ym.isAfter(last) && months.size < 240) {
                    val tt = Repo.totals(ym)
                    months += Export.MonthSum(ym, tt[0], tt[1])
                    ym = ym.plusMonths(1)
                }
                Export.xlsx(rows, months, Repo.byCategory(TxType.EXPENSE, start, end), lbl)
            } else Export.csv(rows)
            a.contentResolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            rows.size
        }) { n -> a.toast(tr("Exported $n transactions", "$n transaksi diekspor")) }
        return true
    }
}

/** Backup to a .zip and restore from it, via the system file screens. */
object BackupFlow {
    const val REQ_BACKUP = 502
    const val REQ_RESTORE = 503

    fun backup(a: Activity) {
        val name = "finance-backup-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(java.time.LocalDateTime.now()) + ".zip"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip").putExtra(Intent.EXTRA_TITLE, name)
        Lock.allowExternal()
        try { a.startActivityForResult(intent, REQ_BACKUP) } catch (_: Throwable) { Lock.externalUntil = 0; a.toast(tr("No file manager available", "Tidak ada pengelola file")) }
    }

    fun restore(a: Activity) {
        a.confirm(
            tr("Restore from backup?", "Pulihkan dari cadangan?"),
            tr("All current data on this phone will be replaced by the backup. This cannot be undone.",
                "Semua data saat ini di ponsel akan diganti dengan isi cadangan. Tindakan ini tidak bisa dibatalkan."),
            tr("Choose backup file", "Pilih file cadangan"), danger = true
        ) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            Lock.allowExternal()
            try { a.startActivityForResult(intent, REQ_RESTORE) } catch (_: Throwable) { Lock.externalUntil = 0; a.toast(tr("No file manager available", "Tidak ada pengelola file")) }
        }
    }

    fun onResult(a: Activity, req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ_BACKUP && req != REQ_RESTORE) return false
        val uri = data?.data
        if (res != Activity.RESULT_OK || uri == null) return true
        if (req == REQ_BACKUP) {
            Repo.async({
                a.contentResolver.openOutputStream(uri, "w")!!.use { Backup.write(a.applicationContext, it) }
            }) { a.toast(tr("Backup saved. Keep it somewhere safe (e.g. Google Drive).", "Cadangan tersimpan. Simpan di tempat aman (mis. Google Drive).")) }
        } else {
            Repo.async({
                a.contentResolver.openInputStream(uri)!!.use { Backup.restore(a.applicationContext, it) }
            }) {
                a.toast(tr("Data restored", "Data berhasil dipulihkan"))
                a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                a.finish()
            }
        }
        return true
    }
}
