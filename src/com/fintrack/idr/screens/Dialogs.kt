package com.fintrack.idr.screens

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView
import com.fintrack.idr.data.Category
import com.fintrack.idr.data.Repo
import com.fintrack.idr.data.Wallet
import com.fintrack.idr.data.WalletKind
import com.fintrack.idr.ui.C
import com.fintrack.idr.ui.MATCH
import com.fintrack.idr.ui.WRAP
import com.fintrack.idr.ui.add
import com.fintrack.idr.ui.chip
import com.fintrack.idr.ui.dialog
import com.fintrack.idr.ui.dialogBody
import com.fintrack.idr.ui.dp
import com.fintrack.idr.ui.emojiBadge
import com.fintrack.idr.ui.emojiPicker
import com.fintrack.idr.ui.flow
import com.fintrack.idr.ui.hbox
import com.fintrack.idr.ui.input
import com.fintrack.idr.ui.label
import com.fintrack.idr.ui.moneyInput
import com.fintrack.idr.ui.scroll
import com.fintrack.idr.ui.showKeyboard
import com.fintrack.idr.util.Money
import com.fintrack.idr.util.TxType
import com.fintrack.idr.util.tr

private fun Activity.iconPickerRow(initial: String, onChange: (String) -> Unit): LinearLayout {
    var badge: TextView? = null
    val row = hbox {
        badge = emojiBadge(initial, 52, C.brandSoft)
        add(badge!!, dp(52), dp(52))
        add(label(tr("Tap to change icon", "Ketuk untuk ganti ikon"), 14f, C.accent, true), 0, WRAP, 1f, l = 12)
    }
    row.setOnClickListener { emojiPicker { e -> badge?.text = e; onChange(e) } }
    return row
}

/** Create or edit a category. Calls [onSaved] with the category id. */
fun Activity.categoryDialog(existing: Category?, type: Int, onSaved: (Long) -> Unit) {
    var icon = existing?.icon ?: if (type == TxType.INCOME) "💰" else "📦"
    val name = input(tr("Category name", "Nama kategori"), existing?.label ?: "")
    val body = dialogBody {
        add(iconPickerRow(icon) { icon = it })
        add(name, MATCH, WRAP, t = 12)
    }
    val d = dialog().setTitle(if (existing == null) tr("New category", "Kategori baru") else tr("Edit category", "Ubah kategori"))
        .setView(body)
        .setPositiveButton(tr("Save", "Simpan"), null)
        .setNegativeButton(tr("Cancel", "Batal"), null)
        .create()
    d.setOnShowListener {
        if (existing == null) showKeyboard(name)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            if (n.isEmpty()) { name.error = tr("Required", "Wajib diisi"); return@setOnClickListener }
            d.dismiss()
            // Keep the default key only if the name is unchanged, so it still follows the phone language.
            val keepDefault = existing?.dkey != null && n == existing.label && existing.name == null
            val c = Category(existing?.id ?: 0, type, existing?.dkey, if (keepDefault) null else n, icon, existing?.budget ?: 0)
            Repo.async({ Repo.saveCategory(c) }) { onSaved(it) }
        }
    }
    d.show()
}

/** Create or edit a wallet. */
fun Activity.walletDialog(existing: Wallet?, onSaved: (Long) -> Unit) {
    var kind = existing?.kind ?: WalletKind.BANK
    var icon = existing?.icon ?: WalletKind.icon(kind)
    var iconTouched = existing != null
    val name = input(tr("Name (e.g. BCA, GoPay, Cash)", "Nama (mis. BCA, GoPay, Tunai)"), existing?.name ?: "")
    val initial = input("0").moneyInput(existing?.initial ?: 0)
    val kinds = flow()
    lateinit var iconRow: LinearLayout
    fun renderKinds() {
        kinds.removeAllViews()
        for (k in WalletKind.all) kinds.addView(chip(WalletKind.icon(k) + " " + WalletKind.label(k), k == kind) {
            kind = k
            if (!iconTouched) { icon = WalletKind.icon(k); ((iconRow.getChildAt(0)) as TextView).text = icon }
            renderKinds()
        })
    }
    renderKinds()
    iconRow = iconPickerRow(icon) { icon = it; iconTouched = true }
    val body = dialogBody {
        add(iconRow)
        add(name, MATCH, WRAP, t = 12)
        add(label(tr("Type", "Jenis"), 13f, C.text2, true), t = 12)
        add(kinds, MATCH, WRAP, t = 6)
        add(label(tr("Starting balance (today's balance before recording anything)", "Saldo awal (saldo sebelum mulai mencatat)"), 13f, C.text2, true), t = 12)
        add(hbox {
            add(label("Rp", 18f, C.text2, true), WRAP, WRAP, r = 8)
            add(initial, 0, WRAP, 1f)
        }, t = 6)
    }
    val d = dialog().setTitle(if (existing == null) tr("New wallet", "Dompet baru") else tr("Edit wallet", "Ubah dompet"))
        .setView(scroll(body))
        .setPositiveButton(tr("Save", "Simpan"), null)
        .setNegativeButton(tr("Cancel", "Batal"), null)
        .create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            if (n.isEmpty()) { name.error = tr("Required", "Wajib diisi"); return@setOnClickListener }
            d.dismiss()
            val w = Wallet(existing?.id ?: 0, n, kind, icon, Money.parse(initial.text), existing?.archived ?: false)
            Repo.async({ Repo.saveWallet(w) }) { onSaved(it) }
        }
    }
    d.show()
}
