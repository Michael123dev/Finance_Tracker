package com.fintrack.idr.screens

import android.content.Intent
import android.widget.LinearLayout
import com.fintrack.idr.ui.C
import com.fintrack.idr.ui.Icon
import com.fintrack.idr.ui.add
import com.fintrack.idr.ui.card
import com.fintrack.idr.ui.label
import com.fintrack.idr.ui.menuRow
import com.fintrack.idr.ui.pad
import com.fintrack.idr.ui.scroll
import com.fintrack.idr.ui.vbox
import com.fintrack.idr.util.TxType
import com.fintrack.idr.util.tr

class MoreTab(a: MainActivity) : Tab(a) {
    private val body: LinearLayout = a.vbox { pad(16, 8, 16, 28) }
    override val view = a.scroll(body)

    init { build() }

    private fun go(cls: Class<*>) = a.startActivity(Intent(a, cls))

    private fun build() = with(a) {
        body.add(label(tr("More", "Lainnya"), 24f, C.text, true, 1), b = 12)

        body.add(label(tr("Money", "Keuangan"), 13f, C.text2, true).pad(4, 0, 0, 6))
        body.add(card(4) {
            add(menuRow(Icon.WALLET, tr("Wallets & accounts", "Dompet & rekening"), tr("Bank, cash, e-wallet balances", "Saldo bank, tunai, dompet digital")) { go(WalletsActivity::class.java) })
            add(menuRow(Icon.TRANSFER, tr("Transfer between wallets", "Transfer antar dompet"), tr("Not counted as income or expense", "Tidak dihitung sebagai pemasukan/pengeluaran")) {
                startActivity(Intent(a, TxEditActivity::class.java).putExtra(TxEditActivity.EXTRA_TYPE, TxType.TRANSFER))
            })
            add(menuRow(Icon.BUDGET, tr("Monthly budgets", "Anggaran bulanan"), tr("Spending limit per category", "Batas pengeluaran per kategori")) { go(BudgetsActivity::class.java) })
            add(menuRow(Icon.TARGET, tr("Savings goals", "Target tabungan"), tr("Wedding fund, emergency fund…", "Dana nikah, dana darurat…")) { go(GoalsActivity::class.java) })
            add(menuRow(Icon.REPEAT, tr("Recurring transactions", "Transaksi berulang"), tr("Salary, rent, installments, subscriptions", "Gaji, sewa, cicilan, langganan")) { go(RecurringActivity::class.java) })
            add(menuRow(Icon.TAG, tr("Categories", "Kategori"), tr("Add, rename or delete categories", "Tambah, ubah, atau hapus kategori")) { go(CategoriesActivity::class.java) })
            add(menuRow(Icon.CALENDAR, tr("Calendar", "Kalender"), tr("Transactions by date", "Transaksi per tanggal")) { go(CalendarActivity::class.java) })
        }, b = 16)

        body.add(label(tr("Data", "Data"), 13f, C.text2, true).pad(4, 0, 0, 6))
        body.add(card(4) {
            add(menuRow(Icon.EXPORT, tr("Export to Excel / CSV", "Ekspor ke Excel / CSV")) { ExportFlow.start(a) })
            add(menuRow(Icon.BACKUP, tr("Back up data", "Cadangkan data"), tr("Save everything to one .zip file", "Simpan semua ke satu file .zip")) { BackupFlow.backup(a) })
            add(menuRow(Icon.RESTORE, tr("Restore from backup", "Pulihkan dari cadangan")) { BackupFlow.restore(a) })
        }, b = 16)

        body.add(label(tr("App", "Aplikasi"), 13f, C.text2, true).pad(4, 0, 0, 6))
        body.add(card(4) {
            add(menuRow(Icon.SETTINGS, tr("Settings", "Pengaturan"), tr("Theme, app lock, reminders", "Tema, kunci aplikasi, pengingat")) { go(SettingsActivity::class.java) })
        }, b = 16)
        body.add(label(tr("All data is stored only on this phone. Make regular backups.",
            "Semua data hanya tersimpan di ponsel ini. Rutinlah membuat cadangan."), 12.5f, C.text3).pad(4, 0, 4, 0))
    }

    override fun refresh(force: Boolean) {}
}
