package com.fintrack.idr.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Line icons drawn on a 24x24 grid with a 2-unit stroke (no icon font or image assets needed). */
class Icon(private val kind: Int, color: Int) : Drawable() {
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color
    }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private val path = Path()
    private val r = RectF()

    companion object {
        const val HOME = 1; const val LIST = 2; const val PLUS = 3; const val CHART = 4; const val MORE = 5
        const val WALLET = 6; const val TAG = 7; const val TARGET = 8; const val REPEAT = 9; const val BUDGET = 10
        const val SETTINGS = 11; const val EXPORT = 12; const val BACKUP = 13; const val RESTORE = 14; const val LOCK = 15
        const val BELL = 16; const val MOON = 17; const val SEARCH = 18; const val FILTER = 19; const val CALENDAR = 20
        const val BACK = 21; const val CHEV_R = 22; const val CHEV_L = 23; const val CLOSE = 24; const val CAMERA = 25
        const val IMAGE = 26; const val TRASH = 27; const val EDIT = 28; const val TRANSFER = 29; const val CHECK = 30
        const val ARROW_UP = 31; const val ARROW_DOWN = 32; const val INFO = 33; const val CHEV_D = 34
        const val FINGERPRINT = 35; const val BACKSPACE = 36; const val PAUSE = 37; const val PLAY = 38
        const val EYE = 39; const val EYE_OFF = 40; const val GLOBE = 41
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val sc = minOf(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left + (b.width() - 24 * sc) / 2f, b.top + (b.height() - 24 * sc) / 2f)
        c.scale(sc, sc)
        path.reset()
        drawKind(c)
        c.restore()
    }

    private fun line(c: Canvas, vararg pts: Float) {
        path.reset()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        c.drawPath(path, s)
    }

    private fun rrect(c: Canvas, l: Float, t: Float, rr: Float, b: Float, rad: Float) {
        r.set(l, t, rr, b); c.drawRoundRect(r, rad, rad, s)
    }

    private fun drawKind(c: Canvas) {
        when (kind) {
            HOME -> {
                line(c, 3f, 11f, 12f, 3.5f, 21f, 11f)
                line(c, 5.5f, 9.5f, 5.5f, 20f, 18.5f, 20f, 18.5f, 9.5f)
                line(c, 10f, 20f, 10f, 14.5f, 14f, 14.5f, 14f, 20f)
            }
            LIST -> {
                line(c, 9f, 6f, 20f, 6f); line(c, 9f, 12f, 20f, 12f); line(c, 9f, 18f, 20f, 18f)
                c.drawCircle(4.5f, 6f, 1.3f, f); c.drawCircle(4.5f, 12f, 1.3f, f); c.drawCircle(4.5f, 18f, 1.3f, f)
            }
            PLUS -> { line(c, 12f, 5f, 12f, 19f); line(c, 5f, 12f, 19f, 12f) }
            CHART -> {
                line(c, 5f, 20f, 5f, 13f); line(c, 12f, 20f, 12f, 5f); line(c, 19f, 20f, 19f, 10f)
            }
            MORE -> {
                rrect(c, 4f, 4f, 10.5f, 10.5f, 2f); rrect(c, 13.5f, 4f, 20f, 10.5f, 2f)
                rrect(c, 4f, 13.5f, 10.5f, 20f, 2f); rrect(c, 13.5f, 13.5f, 20f, 20f, 2f)
            }
            WALLET -> {
                rrect(c, 3f, 6f, 21f, 20f, 2.5f)
                line(c, 6f, 6f, 16f, 3.5f, 17f, 6f)
                rrect(c, 15f, 11f, 21f, 15f, 1.5f)
            }
            TAG -> {
                line(c, 3.5f, 11.5f, 3.5f, 3.5f, 11.5f, 3.5f, 20.5f, 12.5f, 12.5f, 20.5f, 3.5f, 11.5f)
                c.drawCircle(8f, 8f, 1.4f, f)
            }
            TARGET -> {
                c.drawCircle(12f, 12f, 9f, s); c.drawCircle(12f, 12f, 5f, s); c.drawCircle(12f, 12f, 1.6f, f)
            }
            REPEAT -> {
                line(c, 17f, 3f, 20f, 6f, 17f, 9f)
                path.reset(); path.moveTo(4f, 11f); path.lineTo(4f, 9f); path.quadTo(4f, 6f, 7f, 6f); path.lineTo(20f, 6f); c.drawPath(path, s)
                line(c, 7f, 21f, 4f, 18f, 7f, 15f)
                path.reset(); path.moveTo(20f, 13f); path.lineTo(20f, 15f); path.quadTo(20f, 18f, 17f, 18f); path.lineTo(4f, 18f); c.drawPath(path, s)
            }
            BUDGET -> {
                c.drawCircle(12f, 12f, 9f, s)
                line(c, 12f, 3f, 12f, 12f, 21f, 12f)
            }
            SETTINGS -> {
                line(c, 4f, 7f, 6.5f, 7f); line(c, 11.5f, 7f, 20f, 7f); c.drawCircle(9f, 7f, 2.5f, s)
                line(c, 4f, 17f, 12.5f, 17f); line(c, 17.5f, 17f, 20f, 17f); c.drawCircle(15f, 17f, 2.5f, s)
            }
            EXPORT -> {
                line(c, 12f, 15f, 12f, 3.5f); line(c, 7.5f, 8f, 12f, 3.5f, 16.5f, 8f)
                path.reset(); path.moveTo(5f, 13f); path.lineTo(5f, 18.5f); path.quadTo(5f, 20.5f, 7f, 20.5f)
                path.lineTo(17f, 20.5f); path.quadTo(19f, 20.5f, 19f, 18.5f); path.lineTo(19f, 13f); c.drawPath(path, s)
            }
            BACKUP -> {
                r.set(5f, 3f, 19f, 8f); c.drawOval(r, s)
                line(c, 5f, 5.5f, 5f, 18.5f); line(c, 19f, 5.5f, 19f, 18.5f)
                path.reset(); r.set(5f, 16f, 19f, 21f); path.arcTo(r, 0f, 180f, true); c.drawPath(path, s)
                path.reset(); r.set(5f, 9.5f, 19f, 14.5f); path.arcTo(r, 0f, 180f, true); c.drawPath(path, s)
            }
            RESTORE -> {
                path.reset(); r.set(4f, 4f, 20f, 20f); path.arcTo(r, 180f, 300f, true); c.drawPath(path, s)
                line(c, 1.8f, 9.5f, 4f, 12f, 6.8f, 10f)
                line(c, 12f, 8f, 12f, 12f, 15f, 14f)
            }
            LOCK -> {
                rrect(c, 5f, 11f, 19f, 21f, 2.5f)
                path.reset(); path.moveTo(8f, 11f); path.lineTo(8f, 8f); path.quadTo(8f, 3.5f, 12f, 3.5f)
                path.quadTo(16f, 3.5f, 16f, 8f); path.lineTo(16f, 11f); c.drawPath(path, s)
            }
            BELL -> {
                path.reset(); path.moveTo(6f, 17f); path.lineTo(6f, 11f); path.quadTo(6f, 5f, 12f, 5f)
                path.quadTo(18f, 5f, 18f, 11f); path.lineTo(18f, 17f); c.drawPath(path, s)
                line(c, 4f, 17f, 20f, 17f)
                path.reset(); path.moveTo(10f, 20f); path.quadTo(12f, 22f, 14f, 20f); c.drawPath(path, s)
            }
            MOON -> {
                val a = Path().apply { addCircle(12f, 12f, 8.5f, Path.Direction.CW) }
                val b = Path().apply { addCircle(17f, 7.5f, 7f, Path.Direction.CW) }
                a.op(b, Path.Op.DIFFERENCE)
                c.drawPath(a, s)
            }
            SEARCH -> { c.drawCircle(10.5f, 10.5f, 6.5f, s); line(c, 15.5f, 15.5f, 20f, 20f) }
            FILTER -> { line(c, 4f, 6f, 20f, 6f); line(c, 7f, 12f, 17f, 12f); line(c, 10f, 18f, 14f, 18f) }
            CALENDAR -> {
                rrect(c, 4f, 5f, 20f, 20f, 2.5f)
                line(c, 4f, 10f, 20f, 10f); line(c, 8.5f, 3f, 8.5f, 7f); line(c, 15.5f, 3f, 15.5f, 7f)
            }
            BACK -> { line(c, 19f, 12f, 5f, 12f); line(c, 11f, 6f, 5f, 12f, 11f, 18f) }
            CHEV_R -> line(c, 9f, 6f, 15f, 12f, 9f, 18f)
            CHEV_L -> line(c, 15f, 6f, 9f, 12f, 15f, 18f)
            CHEV_D -> line(c, 6f, 9f, 12f, 15f, 18f, 9f)
            CLOSE -> { line(c, 6f, 6f, 18f, 18f); line(c, 18f, 6f, 6f, 18f) }
            CAMERA -> {
                rrect(c, 3f, 7f, 21f, 19.5f, 2.5f)
                line(c, 8f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 16f, 7f)
                c.drawCircle(12f, 13f, 3.5f, s)
            }
            IMAGE -> {
                rrect(c, 3f, 4f, 21f, 20f, 2.5f)
                c.drawCircle(8.5f, 9f, 1.6f, f)
                line(c, 21f, 15f, 16f, 10f, 5f, 20f)
            }
            TRASH -> {
                line(c, 4f, 7f, 20f, 7f); line(c, 9f, 7f, 9f, 4.5f, 15f, 4.5f, 15f, 7f)
                line(c, 6.5f, 7f, 7.5f, 20f, 16.5f, 20f, 17.5f, 7f)
                line(c, 10f, 11f, 10f, 16f); line(c, 14f, 11f, 14f, 16f)
            }
            EDIT -> {
                line(c, 4f, 20f, 4f, 16f, 15.5f, 4.5f, 19.5f, 8.5f, 8f, 20f, 4f, 20f)
                line(c, 13f, 7f, 17f, 11f)
            }
            TRANSFER -> {
                line(c, 4f, 8f, 18f, 8f); line(c, 14f, 4f, 18f, 8f, 14f, 12f)
                line(c, 20f, 16f, 6f, 16f); line(c, 10f, 12f, 6f, 16f, 10f, 20f)
            }
            CHECK -> line(c, 5f, 12.5f, 10f, 17.5f, 19f, 7f)
            ARROW_UP -> { line(c, 12f, 20f, 12f, 4f); line(c, 6f, 10f, 12f, 4f, 18f, 10f) }
            ARROW_DOWN -> { line(c, 12f, 4f, 12f, 20f); line(c, 6f, 14f, 12f, 20f, 18f, 14f) }
            INFO -> { c.drawCircle(12f, 12f, 9f, s); line(c, 12f, 11f, 12f, 16.5f); c.drawCircle(12f, 7.8f, 1.2f, f) }
            FINGERPRINT -> {
                path.reset(); r.set(4f, 3f, 20f, 19f); path.arcTo(r, 200f, 140f, true); c.drawPath(path, s)
                path.reset(); r.set(7f, 6.5f, 17f, 16.5f); path.arcTo(r, 180f, 180f, true); path.lineTo(17f, 15f); c.drawPath(path, s)
                line(c, 7f, 11.5f, 7f, 15.5f)
                path.reset(); r.set(10f, 9.5f, 14f, 13.5f); path.arcTo(r, 180f, 180f, true); path.lineTo(14f, 20f); c.drawPath(path, s)
                line(c, 10f, 11.5f, 10f, 21f)
            }
            BACKSPACE -> {
                path.reset(); path.moveTo(8f, 5f); path.lineTo(19.5f, 5f); path.quadTo(21f, 5f, 21f, 6.5f); path.lineTo(21f, 17.5f)
                path.quadTo(21f, 19f, 19.5f, 19f); path.lineTo(8f, 19f); path.lineTo(2.5f, 12f); path.close(); c.drawPath(path, s)
                line(c, 11f, 9f, 17f, 15f); line(c, 17f, 9f, 11f, 15f)
            }
            PAUSE -> { line(c, 9f, 6f, 9f, 18f); line(c, 15f, 6f, 15f, 18f) }
            PLAY -> line(c, 8f, 5f, 19f, 12f, 8f, 19f, 8f, 5f)
            EYE, EYE_OFF -> {
                path.reset(); path.moveTo(2.5f, 12f); path.quadTo(12f, 2.5f, 21.5f, 12f); path.quadTo(12f, 21.5f, 2.5f, 12f); path.close()
                c.drawPath(path, s)
                c.drawCircle(12f, 12f, 3f, s)
                if (kind == EYE_OFF) line(c, 4f, 4f, 20f, 20f)
            }
            GLOBE -> {
                c.drawCircle(12f, 12f, 9f, s)
                line(c, 3f, 12f, 21f, 12f)
                r.set(8f, 3f, 16f, 21f); c.drawOval(r, s)
            }
        }
    }

    override fun setAlpha(alpha: Int) { s.alpha = alpha; f.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { s.colorFilter = cf; f.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = -1
    override fun getIntrinsicHeight(): Int = -1
}
