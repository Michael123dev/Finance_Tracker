package com.fintrack.idr.data

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Receipt rules with no Android types, so they run in the JVM tests (test/LogicTests.kt).

/** A receipt image ready to store: the encoded binary and its type. */
class ReceiptImage(val bytes: ByteArray, val mime: String, val width: Int, val height: Int) {
    /** Standard Base64, no line breaks. */
    fun base64(): String = java.util.Base64.getEncoder().encodeToString(bytes)

    companion object {
        /** Null when the stored text is empty or not valid Base64 (strict decoder: damaged text is rejected, not guessed). */
        fun fromBase64(data: String?, mime: String, width: Int, height: Int): ReceiptImage? {
            if (data.isNullOrEmpty()) return null
            val bytes = try { java.util.Base64.getDecoder().decode(data) } catch (_: IllegalArgumentException) { return null }
            return if (bytes.isEmpty()) null else ReceiptImage(bytes, mime, width, height)
        }
    }
}

object ReceiptMath {
    /** Most bytes a stored receipt image may have. Applies to the image binary, not its Base64 text (about 4/3 larger). */
    const val LIMIT = 200_000
    /** Larger picked files are refused before decoding. */
    const val MAX_SOURCE = 40L * 1024 * 1024
    /** Size caps before compression: long side and total pixels (2 MP keeps receipt text readable). */
    const val MAX_LONG_SIDE = 2400
    const val MAX_PIXELS = 2_000_000L
    /** Below this long side the compressor stops shrinking and lowers quality instead. */
    const val MIN_LONG_SIDE = 480
    val QUALITIES = intArrayOf(85, 75, 65, 55)
    val LAST_RESORT_QUALITIES = intArrayOf(45, 35, 25)
    const val SHRINK = 0.8
    /** Formats stored as they are when already small, upright and within the size caps. */
    val KEEP_MIMES = setOf("image/jpeg", "image/png", "image/webp")

    /** Scale (at most 1) that fits w x h inside the long-side and pixel caps, keeping the aspect ratio. */
    fun fitScale(w: Int, h: Int): Double {
        if (w <= 0 || h <= 0) return 1.0
        return minOf(1.0, MAX_LONG_SIDE.toDouble() / max(w, h), sqrt(MAX_PIXELS.toDouble() / (w.toLong() * h)))
    }

    /** Target size; rounded down so it never exceeds the caps. */
    fun fitted(w: Int, h: Int): Pair<Int, Int> {
        val s = fitScale(w, h)
        if (s >= 1.0) return w to h
        return max(1, floor(w * s).toInt()) to max(1, floor(h * s).toInt())
    }

    /** Largest power-of-two subsample that still decodes at least targetW x targetH (saves memory on big photos). */
    fun sampleSize(w: Int, h: Int, targetW: Int, targetH: Int): Int {
        var s = 1
        while (w / (s * 2) >= targetW && h / (s * 2) >= targetH) s *= 2
        return s
    }

    /**
     * True when the picked file can be stored unchanged: already within [LIMIT], upright, and a format every supported
     * Android version decodes. Pixel size doesn't matter here: it is shown downsampled, and storage is what the limit is for.
     */
    @Suppress("UNUSED_PARAMETER")
    fun keepOriginal(bytes: Int, mime: String?, w: Int, h: Int, rotation: Int): Boolean =
        bytes in 1..LIMIT && rotation == 0 && mime in KEEP_MIMES && w > 0 && h > 0

    /** Degrees to turn the picture upright for an EXIF orientation value (mirrored variants are left as they are). */
    fun rotationFor(exifOrientation: Int): Int = when (exifOrientation) {
        6 -> 90
        3 -> 180
        8 -> 270
        else -> 0
    }

    class Fit<T>(val result: T, val width: Int, val height: Int, val quality: Int, val attempts: Int)

    /**
     * Encodes until the result is at most [LIMIT] bytes: each quality in [QUALITIES] at the current size, then the size
     * shrinks by [SHRINK] and the qualities repeat. At [MIN_LONG_SIDE] it tries [LAST_RESORT_QUALITIES], then keeps
     * shrinking. [encode] gets (width, height, quality); [size] measures its result.
     */
    fun <T> compress(w0: Int, h0: Int, encode: (Int, Int, Int) -> T, size: (T) -> Int): Fit<T> {
        var w = w0
        var h = h0
        var attempts = 0
        while (true) {
            val small = max(w, h) <= MIN_LONG_SIDE
            for (q in if (small) QUALITIES + LAST_RESORT_QUALITIES else QUALITIES) {
                attempts++
                val r = encode(w, h, q)
                if (size(r) <= LIMIT) return Fit(r, w, h, q, attempts)
            }
            if (max(w, h) <= 16) throw IllegalStateException("Image cannot be compressed")
            w = max(1, (w * SHRINK).roundToInt())
            h = max(1, (h * SHRINK).roundToInt())
        }
    }
}

/**
 * What the edit screen does with the receipt when the user taps Save.
 * [stored] = the transaction already has a receipt in the database.
 */
class ReceiptDraft(val stored: Boolean) {
    sealed class Action {
        object Keep : Action()
        object Delete : Action()
        class Put(val image: ReceiptImage) : Action()
    }

    /** A newly picked image, not saved yet. */
    var pending: ReceiptImage? = null
        private set
    var removed = false
        private set

    /** Something to show: a new image, or the stored one that wasn't removed. */
    val visible: Boolean get() = pending != null || (stored && !removed)

    fun replace(img: ReceiptImage) {
        pending = img
        removed = false
    }

    fun remove() {
        pending = null
        removed = true
    }

    fun action(): Action {
        val p = pending
        return when {
            p != null -> Action.Put(p)
            stored && removed -> Action.Delete
            else -> Action.Keep
        }
    }

    /** Value for tx.receipt after saving. Unchanged attachments keep the original value. */
    fun marker(original: String?, mark: String): String? = when (action()) {
        is Action.Put -> mark
        Action.Delete -> null
        Action.Keep -> if (stored) original else null
    }
}
