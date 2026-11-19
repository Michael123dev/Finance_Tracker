package com.fintrack.idr.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fintrack.idr.R
import com.fintrack.idr.data.Defaults
import com.fintrack.idr.util.D
import com.fintrack.idr.util.Money
import com.fintrack.idr.util.tr
import java.time.LocalDate

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density
fun View.dp(v: Int): Int = context.dp(v)
fun View.dpf(v: Float): Float = context.dpf(v)

val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

// ------------------------------------------------------------------ layout helpers

fun Context.vbox(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; block() }

fun Context.hbox(block: LinearLayout.() -> Unit = {}): LinearLayout =
    LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; block() }

/** Adds a child with LinearLayout params; margins in dp. */
fun <T : View> LinearLayout.add(
    v: T, w: Int = MATCH, h: Int = WRAP, weight: Float = 0f,
    l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0,
): T {
    val lp = LinearLayout.LayoutParams(w, h, weight)
    lp.setMargins(dp(l), dp(t), dp(r), dp(b))
    addView(v, lp)
    return v
}

fun <T : View> FrameLayout.addF(v: T, w: Int = MATCH, h: Int = MATCH, gravity: Int = Gravity.TOP or Gravity.START, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): T {
    val lp = FrameLayout.LayoutParams(w, h, gravity)
    lp.setMargins(dp(l), dp(t), dp(r), dp(b))
    addView(v, lp)
    return v
}

fun LinearLayout.space(h: Int = 8) = add(View(context), MATCH, dp(h))
fun LinearLayout.flex() = add(View(context), 0, 1, 1f)

fun View.pad(all: Int) = apply { setPadding(dp(all), dp(all), dp(all), dp(all)) }
fun View.pad(h: Int, v: Int) = apply { setPadding(dp(h), dp(v), dp(h), dp(v)) }
fun View.pad(l: Int, t: Int, r: Int, b: Int) = apply { setPadding(dp(l), dp(t), dp(r), dp(b)) }

fun View.onClick(f: () -> Unit) = apply { setOnClickListener { f() } }

fun Context.scroll(content: View): ScrollView = ScrollView(this).apply {
    isFillViewport = true
    isVerticalScrollBarEnabled = false
    addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
}

fun Context.hscroll(content: View): HorizontalScrollView = HorizontalScrollView(this).apply {
    isHorizontalScrollBarEnabled = false
    addView(content, ViewGroup.LayoutParams(WRAP, WRAP))
}

// ------------------------------------------------------------------ drawables

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

fun oval(color: Int): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

fun ripple(content: Drawable?, radius: Float): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(C.ripple), content, rounded(Color.WHITE, radius))

fun View.clickable(radiusDp: Int = 12, bgColor: Int? = null) = apply {
    val r = context.dpf(radiusDp.toFloat())
    background = ripple(bgColor?.let { rounded(it, r) }, r)
    isClickable = true
    isFocusable = true
}

// ------------------------------------------------------------------ text

fun Context.label(
    text: CharSequence = "", size: Float = 14f, color: Int = C.text, bold: Boolean = false,
    lines: Int = 0, gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL,
): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    typeface = if (bold) MEDIUM else REGULAR
    this.gravity = gravity
    includeFontPadding = true
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
}

/** Money text uses tabular figures so columns of amounts line up. */
fun Context.amount(v: String, size: Float = 16f, color: Int = C.text, bold: Boolean = true): TextView =
    label(v, size, color, bold, 1).apply { fontFeatureSettings = "tnum" }

fun Context.heading(text: String) = label(text, 17f, C.text, true)

fun Context.sectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null): LinearLayout = hbox {
    add(label(title, 16f, C.text, true), 0, WRAP, 1f)
    if (action != null) add(label(action, 14f, C.accent, true).pad(8, 6).clickable(16).onClick { onAction?.invoke() }, WRAP, WRAP)
}

// ------------------------------------------------------------------ components

fun Context.card(padDp: Int = 16, block: LinearLayout.() -> Unit = {}): LinearLayout = vbox {
    background = rounded(C.card, dpf(18f))
    pad(padDp)
    block()
}

fun Context.primaryButton(text: String, onClick: () -> Unit): TextView =
    label(text, 16f, C.onBrand, true, 1, Gravity.CENTER).apply {
        background = RippleDrawable(ColorStateList.valueOf(0x33000000), rounded(C.brand, dpf(16f)), null)
        minHeight = dp(54)
        pad(20, 14)
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.secondaryButton(text: String, color: Int = C.text, onClick: () -> Unit): TextView =
    label(text, 15f, color, true, 1, Gravity.CENTER).apply {
        background = RippleDrawable(ColorStateList.valueOf(C.ripple), rounded(C.card2, dpf(16f)), null)
        minHeight = dp(48)
        pad(16, 12)
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.chip(text: String, selected: Boolean, onClick: () -> Unit): TextView =
    label(text, 14f, if (selected) C.onBrand else C.text, selected, 1, Gravity.CENTER).apply {
        background = RippleDrawable(
            ColorStateList.valueOf(C.ripple),
            rounded(if (selected) C.brand else C.card2, dpf(20f)), null
        )
        minHeight = dp(38)
        pad(14, 8)
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.iconButton(kind: Int, color: Int = C.text, sizeDp: Int = 44, desc: String? = null, onClick: () -> Unit): ImageView =
    ImageView(this).apply {
        setImageDrawable(Icon(kind, color))
        val p = dp(10)
        setPadding(p, p, p, p)
        background = RippleDrawable(ColorStateList.valueOf(C.ripple), null, oval(Color.WHITE))
        contentDescription = desc
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

fun Context.emojiBadge(emoji: String, sizeDp: Int = 42, bg: Int = C.card2): TextView =
    label(emoji, sizeDp * 0.42f, C.text, false, 1, Gravity.CENTER).apply {
        background = oval(bg)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        includeFontPadding = false
    }

fun Context.input(hint: String, value: String = "", multiline: Boolean = false): EditText = EditText(this).apply {
    this.hint = hint
    setText(value)
    textSize = 16f
    setTextColor(C.text)
    setHintTextColor(C.text3)
    background = rounded(C.card2, dpf(14f))
    pad(14, 12)
    inputType = if (multiline) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    if (multiline) { minLines = 2; maxLines = 5; gravity = Gravity.TOP or Gravity.START } else maxLines = 1
}

/** Number field that shows thousands separators while typing: 1.250.000 */
fun EditText.moneyInput(initial: Long = 0, onChange: (Long) -> Unit = {}): EditText {
    // Accept digits plus our own '.' separators, but show the numeric keyboard.
    keyListener = DigitsKeyListener.getInstance("0123456789.")
    setRawInputType(InputType.TYPE_CLASS_NUMBER)
    maxLines = 1
    if (initial > 0) setText(Money.group(initial))
    addTextChangedListener(object : TextWatcher {
        private var busy = false
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) {
            if (busy) return
            busy = true
            val v = Money.parse(s)
            val f = if (s.none { it in '0'..'9' }) "" else Money.group(v)
            if (f != s.toString()) {
                setText(f)
                setSelection(f.length)
            }
            busy = false
            onChange(v)
        }
    })
    return this
}

fun Context.divider(): View = View(this).apply { setBackgroundColor(C.line) }

fun Context.emptyState(emoji: String, title: String, sub: String, action: String? = null, onAction: (() -> Unit)? = null): LinearLayout = vbox {
    gravity = Gravity.CENTER_HORIZONTAL
    pad(24, 32)
    add(label(emoji, 44f, C.text, false, 1, Gravity.CENTER), WRAP, WRAP)
    add(label(title, 17f, C.text, true, 0, Gravity.CENTER), MATCH, WRAP, t = 8)
    add(label(sub, 14f, C.text2, false, 0, Gravity.CENTER), MATCH, WRAP, t = 4)
    if (action != null) add(secondaryButton(action) { onAction?.invoke() }, WRAP, WRAP, t = 16)
}

/** Simple top bar with back button. */
fun Activity.topBar(title: String, actions: LinearLayout.() -> Unit = {}): LinearLayout = hbox {
    pad(4, 4, 8, 4)
    minimumHeight = dp(56)
    add(iconButton(Icon.BACK, C.text, desc = tr("Back", "Kembali")) { onBackPressed() }, dp(44), dp(44))
    add(label(title, 20f, C.text, true, 1), 0, WRAP, 1f, l = 4)
    actions()
}

/** Pill-shaped progress meter. Color shifts at the warning threshold and when over 100%. */
class Meter(ctx: Context) : View(ctx) {
    var pct = 0
        set(v) { field = v; invalidate() }
    var warnAt = 80
    var color: Int? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val rad = h / 2
        p.color = C.card2
        r.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(r, rad, rad, p)
        val f = pct.coerceIn(0, 100) / 100f
        if (f <= 0f) return
        p.color = color ?: when {
            pct >= 100 -> C.expense
            pct >= warnAt -> C.warn
            else -> C.meter
        }
        r.set(0f, 0f, maxOf(h, width * f), h)
        c.drawRoundRect(r, rad, rad, p)
    }
}

fun Context.meter(pct: Int, warnAt: Int = 80, color: Int? = null): Meter = Meter(this).apply {
    this.warnAt = warnAt; this.color = color; this.pct = pct
}

/** Wrapping row layout for chips. */
class FlowLayout(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(wSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

fun Context.flow(hGapDp: Int = 8, vGapDp: Int = 8): FlowLayout = FlowLayout(this, dp(hGapDp), dp(vGapDp))

// ------------------------------------------------------------------ dialogs

fun Activity.dialog(): AlertDialog.Builder = AlertDialog.Builder(this, R.style.DialogTheme)

fun Activity.confirm(title: String, msg: String, ok: String, danger: Boolean = false, onOk: () -> Unit) {
    val d = dialog().setTitle(title).setMessage(msg)
        .setPositiveButton(ok) { _, _ -> onOk() }
        .setNegativeButton(tr("Cancel", "Batal"), null)
        .show()
    if (danger) d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(C.expense)
}

fun Activity.choose(title: String, items: List<String>, checked: Int, onPick: (Int) -> Unit) {
    dialog().setTitle(title)
        .setSingleChoiceItems(items.toTypedArray(), checked) { d, which -> d.dismiss(); onPick(which) }
        .setNegativeButton(tr("Cancel", "Batal"), null)
        .show()
}

fun Activity.dialogBody(block: LinearLayout.() -> Unit): LinearLayout = vbox {
    pad(22, 8, 22, 0)
    block()
}

fun Activity.amountDialog(title: String, initial: Long, hint: String = "0", allowZero: Boolean = false, note: String? = null, onOk: (Long) -> Unit) {
    val et = input(hint).apply { textSize = 22f; typeface = MEDIUM }.moneyInput(initial)
    val body = dialogBody {
        if (note != null) add(label(note, 14f, C.text2), MATCH, WRAP, b = 10)
        add(hbox {
            add(label("Rp", 22f, C.text2, true), WRAP, WRAP, r = 8)
            add(et, 0, WRAP, 1f)
        })
    }
    val d = dialog().setTitle(title).setView(body)
        .setPositiveButton(tr("Save", "Simpan"), null)
        .setNegativeButton(tr("Cancel", "Batal"), null)
        .create()
    d.setOnShowListener {
        et.requestFocus()
        et.setSelection(et.text.length)
        showKeyboard(et)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val v = Money.parse(et.text)
            if (v <= 0 && !allowZero) { et.error = tr("Enter an amount", "Masukkan jumlah"); return@setOnClickListener }
            d.dismiss(); onOk(v)
        }
    }
    d.show()
}

fun Activity.textDialog(title: String, initial: String, hint: String, onOk: (String) -> Unit) {
    val et = input(hint, initial)
    val d = dialog().setTitle(title).setView(dialogBody { add(et) })
        .setPositiveButton(tr("Save", "Simpan"), null)
        .setNegativeButton(tr("Cancel", "Batal"), null)
        .create()
    d.setOnShowListener {
        et.requestFocus(); et.setSelection(et.text.length); showKeyboard(et)
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val s = et.text.toString().trim()
            if (s.isEmpty()) { et.error = tr("Required", "Wajib diisi"); return@setOnClickListener }
            d.dismiss(); onOk(s)
        }
    }
    d.show()
}

fun Activity.pickDate(day: Long, onPick: (Long) -> Unit) {
    val d = D.ld(day)
    DatePickerDialog(this, R.style.PickerTheme, { _, y, m, dd -> onPick(LocalDate.of(y, m + 1, dd).toEpochDay()) },
        d.year, d.monthValue - 1, d.dayOfMonth).show()
}

fun Activity.emojiPicker(onPick: (String) -> Unit) {
    val grid = GridLayout(this).apply { columnCount = 6 }
    var dlg: AlertDialog? = null
    for (e in Defaults.emojis) {
        grid.addView(label(e, 24f, C.text, false, 1, Gravity.CENTER).apply {
            clickable(12)
            setOnClickListener { dlg?.dismiss(); onPick(e) }
        }, GridLayout.LayoutParams().apply { width = dp(46); height = dp(46) })
    }
    val custom = input(tr("Or type any emoji", "Atau ketik emoji lain"))
    val body = dialogBody {
        add(grid, WRAP, WRAP)
        add(custom, MATCH, WRAP, t = 8)
    }
    dlg = dialog().setTitle(tr("Choose an icon", "Pilih ikon")).setView(scroll(body))
        .setPositiveButton(tr("Use typed", "Pakai ketikan")) { _, _ ->
            val s = custom.text.toString().trim()
            if (s.isNotEmpty()) onPick(s.take(8))
        }
        .setNegativeButton(tr("Cancel", "Batal"), null)
        .show()
}

fun Activity.showKeyboard(v: View) {
    v.post {
        v.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }
}

fun Activity.hideKeyboard() {
    val v = currentFocus ?: return
    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
}
