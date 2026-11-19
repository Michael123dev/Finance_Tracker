package com.fintrack.idr.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.fintrack.idr.data.TxRow
import com.fintrack.idr.util.D
import com.fintrack.idr.util.Money
import com.fintrack.idr.util.TxType

/** Reusable transaction row (also used with view recycling in the history list). */
class TxRowView(ctx: Context) : LinearLayout(ctx) {
    val badge: TextView = ctx.emojiBadge("", 42)
    val title: TextView = ctx.label("", 15f, C.text, true, 1)
    val sub: TextView = ctx.label("", 13f, C.text2, false, 1)
    val amt: TextView = ctx.amount("", 15f)
    val extra: TextView = ctx.label("", 12f, C.text3, false, 1, Gravity.END)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        pad(12, 10)
        minimumHeight = dp(64)
        clickable(14)
        add(badge, dp(42), dp(42))
        val mid = ctx.vbox()
        mid.add(title)
        mid.add(sub, t = 1)
        add(mid, 0, WRAP, 1f, l = 12, r = 8)
        val right = ctx.vbox { gravity = Gravity.END }
        right.add(amt, WRAP, WRAP)
        right.add(extra, WRAP, WRAP, t = 1)
        add(right, WRAP, WRAP)
    }

    fun bind(r: TxRow, showDate: Boolean) {
        badge.text = r.catIcon
        title.text = r.displayTitle
        sub.text = if (r.tx.title.isNotBlank() || r.tx.type == TxType.TRANSFER) r.subtitle else r.wallet
        amt.text = Money.signed(r.tx.amount, r.tx.type)
        amt.setTextColor(C.typeColor(r.tx.type))
        val bits = ArrayList<String>()
        if (showDate) bits += D.dayShort(r.tx.date)
        if (r.tx.recurringId != null) bits += "🔁"
        if (r.tx.receipt != null) bits += "📎"
        extra.text = bits.joinToString(" ")
        extra.visibility = if (bits.isEmpty()) GONE else VISIBLE
        contentDescription = "${r.displayTitle}, ${r.catLabel}, ${amt.text}, ${D.day(r.tx.date)}"
    }
}

fun Context.txRow(r: TxRow, showDate: Boolean = false, onClick: () -> Unit): TxRowView =
    TxRowView(this).apply { bind(r, showDate); setOnClickListener { onClick() } }

/** A label/value line used in summaries. */
fun Context.kv(k: String, v: String, vColor: Int = C.text, bold: Boolean = true): LinearLayout = hbox {
    add(label(k, 14f, C.text2), 0, WRAP, 1f)
    add(amount(v, 15f, vColor, bold), WRAP, WRAP)
}

/** Row with leading icon, title/subtitle and trailing chevron, for menus and settings. */
fun Context.menuRow(icon: Int, title: String, sub: String? = null, trailing: String? = null, onClick: () -> Unit): LinearLayout = hbox {
    pad(14, 12)
    minimumHeight = dp(56)
    clickable(14)
    setOnClickListener { onClick() }
    val ic = android.widget.ImageView(context).apply { setImageDrawable(Icon(icon, C.text)) }
    add(ic, dp(24), dp(24))
    val mid = vbox()
    mid.add(label(title, 15f, C.text, true, 1))
    if (sub != null) mid.add(label(sub, 13f, C.text2, false, 2), t = 2)
    add(mid, 0, WRAP, 1f, l = 16, r = 8)
    if (trailing != null) add(label(trailing, 14f, C.text2, false, 1), WRAP, WRAP, r = 4)
    val chev = android.widget.ImageView(context).apply { setImageDrawable(Icon(Icon.CHEV_R, C.text3)) }
    add(chev, dp(18), dp(18))
}
