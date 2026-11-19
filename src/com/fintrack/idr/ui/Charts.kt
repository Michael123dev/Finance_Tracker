package com.fintrack.idr.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.fintrack.idr.util.Money

/**
 * Vertical bar chart (1 or 2 series, grouped). Thin bars with 4dp rounded tops anchored to the baseline,
 * 2dp gap between grouped bars, recessive gridlines, compact y labels. Tap or drag to inspect a bar.
 */
class BarChart(ctx: Context) : View(ctx) {
    var labels: List<String> = emptyList()
    var series: List<LongArray> = emptyList()
    var colors: IntArray = intArrayOf()
    var labelEvery = 1
    var highlight = -1
    var selected = -1
        private set
    /** Lines shown in the tooltip for a selected index. */
    var tooltip: ((Int) -> List<String>)? = null

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { strokeWidth = 1f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = REGULAR }
    private val tipBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tipTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = MEDIUM }
    private val path = Path()
    private val rect = RectF()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    fun set(labels: List<String>, series: List<LongArray>, colors: IntArray) {
        this.labels = labels; this.series = series; this.colors = colors
        selected = -1
        contentDescription = labels.indices.joinToString("; ") { i ->
            labels[i] + ": " + series.joinToString(", ") { Money.fmt(it.getOrElse(i) { 0 }) }
        }
        invalidate()
    }

    private val leftPad get() = dp(42).toFloat()
    private val bottomPad get() = dp(22).toFloat()
    private val topPad get() = dp(10).toFloat()

    private fun niceCeil(v: Long): Long {
        if (v <= 0) return 1000
        var m = 1L
        while (m * 10 <= v) m *= 10
        for (k in longArrayOf(1, 2, 3, 4, 5, 6, 8, 10)) if (k * m >= v) return k * m
        return 10 * m
    }

    override fun onDraw(c: Canvas) {
        val n = labels.size
        if (n == 0 || series.isEmpty()) return
        val w = width.toFloat()
        val base = height - bottomPad
        val plotH = base - topPad
        val plotW = w - leftPad - dp(4)
        val max = niceCeil(series.maxOf { s -> s.maxOrNull() ?: 0L })

        // gridlines + y labels
        grid.color = C.grid
        txt.color = C.text3
        txt.textSize = dpf(10.5f)
        txt.textAlign = Paint.Align.RIGHT
        for (k in 0..2) {
            val y = base - plotH * k / 2f
            c.drawLine(leftPad, y, w, y, grid)
            c.drawText(Money.compact(max * k / 2), leftPad - dp(6), y + dpf(3.5f), txt)
        }

        val slot = plotW / n
        val groups = series.size
        val gap = dpf(2f)
        val groupW = minOf(slot * 0.72f, dpf(14f) * groups + gap * (groups - 1))
        val barW = (groupW - gap * (groups - 1)) / groups
        for (i in 0 until n) {
            val x0 = leftPad + slot * i + (slot - groupW) / 2f
            for (s in 0 until groups) {
                val v = series[s].getOrElse(i) { 0L }
                if (v <= 0) continue
                var h = (v.toFloat() / max) * plotH
                if (h < dpf(1.5f)) h = dpf(1.5f)
                val l = x0 + s * (barW + gap)
                val r = l + barW
                val t = base - h
                val rad = minOf(dpf(4f), barW / 2f, h)
                path.reset()
                path.moveTo(l, base); path.lineTo(l, t + rad); path.quadTo(l, t, l + rad, t)
                path.lineTo(r - rad, t); path.quadTo(r, t, r, t + rad); path.lineTo(r, base); path.close()
                bar.color = colors.getOrElse(s) { C.meter }
                bar.alpha = if (selected >= 0 && selected != i) 90 else 255
                c.drawPath(path, bar)
            }
        }

        // x labels
        txt.textAlign = Paint.Align.CENTER
        txt.textSize = dpf(10.5f)
        for (i in 0 until n) {
            if (i % labelEvery != 0 && i != highlight && i != selected) continue
            txt.color = if (i == highlight || i == selected) C.text else C.text3
            txt.typeface = if (i == highlight || i == selected) MEDIUM else REGULAR
            c.drawText(labels[i], leftPad + slot * i + slot / 2f, height - dpf(6f), txt)
        }
        txt.typeface = REGULAR

        // tooltip
        val tipLines = if (selected >= 0) tooltip?.invoke(selected) else null
        if (tipLines != null && tipLines.isNotEmpty()) {
            tipTxt.textSize = dpf(12f)
            tipTxt.color = C.bg
            tipBg.color = C.text
            val padH = dpf(10f)
            val lineH = dpf(17f)
            val tw = tipLines.maxOf { tipTxt.measureText(it) } + padH * 2
            val th = lineH * tipLines.size + dpf(10f)
            val cx = leftPad + slot * selected + slot / 2f
            val left = (cx - tw / 2f).coerceIn(0f, w - tw)
            rect.set(left, 0f, left + tw, th)
            c.drawRoundRect(rect, dpf(8f), dpf(8f), tipBg)
            tipLines.forEachIndexed { k, s ->
                c.drawText(s, left + padH, dpf(5f) + lineH * (k + 1) - dpf(4.5f), tipTxt)
            }
        }
    }

    private fun indexAt(x: Float): Int {
        val n = labels.size
        if (n == 0) return -1
        val slot = (width - leftPad - dp(4)) / n
        return ((x - leftPad) / slot).toInt().coerceIn(0, n - 1)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.abs(e.x - downX)
                val dy = Math.abs(e.y - downY)
                if (!dragging && dx > slop && dx > dy) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) { selected = indexAt(e.x); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    val i = indexAt(e.x)
                    selected = if (selected == i) -1 else i
                    invalidate()
                    performClick()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Single-value progress ring with the percentage in the middle. */
class Ring(ctx: Context) : View(ctx) {
    var pct = 0
    var color = C.meter
    var center = ""
    var sub = ""
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    fun set(pct: Int, center: String, sub: String, color: Int) {
        this.pct = pct; this.center = center; this.sub = sub; this.color = color
        contentDescription = "$center $sub"
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val sw = dpf(9f)
        val size = minOf(width, height) - sw
        val cx = width / 2f
        val cy = height / 2f
        r.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        p.strokeWidth = sw
        p.color = C.card2
        c.drawArc(r, 0f, 360f, false, p)
        if (pct > 0) {
            p.color = color
            c.drawArc(r, -90f, 3.6f * pct.coerceAtMost(100), false, p)
        }
        t.color = C.text
        t.typeface = MEDIUM
        t.textSize = size * 0.22f
        c.drawText(center, cx, cy + t.textSize * 0.2f, t)
        if (sub.isNotEmpty()) {
            t.typeface = REGULAR
            t.color = C.text2
            t.textSize = size * 0.11f
            c.drawText(sub, cx, cy + size * 0.2f, t)
        }
    }
}
