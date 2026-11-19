package com.fintrack.idr.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.fintrack.idr.util.tr
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Receipt photos. Each is compressed to at most [ReceiptMath.LIMIT] bytes and stored as Base64 in the
 * receipt_image table (one row per transaction), so it lives in the database, survives restarts and is in every backup.
 * Before 1.1.1 photos were files in files/receipts; [Db] moves any it finds into the table.
 */
object Receipts {
    /** tx.receipt value for a transaction whose photo is in receipt_image (the column stays small; the image is not in list queries). */
    const val MARK = "db"

    /** A problem the user can act on; the message is shown as is. */
    class Unreadable(message: String) : Exception(message)

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "receipt-image") }
    private val main = Handler(Looper.getMainLooper())

    /** Folder of pre-1.1.1 receipt files (empty once migrated). */
    fun dir(ctx: Context): File = File(ctx.filesDir, "receipts").apply { mkdirs() }

    /** Reads and compresses off the main thread; [done] gets the image or a message to show. */
    fun prepareAsync(read: () -> ByteArray, done: (ReceiptImage?, String?) -> Unit) {
        worker.execute {
            var img: ReceiptImage? = null
            var err: String? = null
            try {
                img = prepare(read())
            } catch (e: Unreadable) {
                err = e.message
            } catch (e: OutOfMemoryError) {
                err = tr("This image is too large to process.", "Gambar ini terlalu besar untuk diproses.")
            } catch (t: Throwable) {
                Log.e("Receipts", "prepare failed", t)
                err = tr("This image couldn't be processed. Try another one.", "Gambar ini tidak bisa diproses. Coba gambar lain.")
            }
            main.post { done(img, err) }
        }
    }

    /** Reads a picked image once, fully, so the picker's temporary permission is only needed for this one read. */
    fun readUri(ctx: Context, uri: Uri): ByteArray {
        val input = try {
            ctx.contentResolver.openInputStream(uri)
        } catch (_: SecurityException) {
            throw Unreadable(tr("The app wasn't allowed to read this file. Pick it again from the gallery.",
                "Aplikasi tidak diizinkan membaca file ini. Pilih lagi dari galeri."))
        } catch (_: FileNotFoundException) {
            null
        } ?: throw Unreadable(tr("This file can't be opened. Pick it again.", "File ini tidak bisa dibuka. Coba pilih lagi."))
        return input.use { readCapped(it) }
    }

    fun readFile(f: File): ByteArray {
        if (!f.isFile || f.length() == 0L) throw Unreadable(tr("No photo was taken.", "Tidak ada foto yang diambil."))
        return f.inputStream().use { readCapped(it) }
    }

    private fun readCapped(s: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            total += n
            if (total > ReceiptMath.MAX_SOURCE) throw Unreadable(tr("This file is larger than 40 MB.", "File ini lebih dari 40 MB."))
            out.write(buf, 0, n)
        }
        if (total == 0L) throw Unreadable(tr("This file is empty.", "File ini kosong."))
        return out.toByteArray()
    }

    /**
     * Checks the bytes are a readable image, turns it upright, fits it in 2 MP / 2400 px, puts transparent areas on
     * white and encodes JPEG, lowering quality and then size until it is at most [ReceiptMath.LIMIT] bytes.
     * A small, upright JPEG/PNG/WebP is kept unchanged.
     */
    fun prepare(src: ByteArray): ReceiptImage {
        // Bounds-only decoding always returns null; success is a positive width and height.
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) {
            throw Unreadable(tr("This file isn't a supported image. Use a JPG, PNG or WebP photo.",
                "File ini bukan gambar yang didukung. Gunakan foto JPG, PNG, atau WebP."))
        }
        val damaged = tr("This image is damaged and can't be read.", "Gambar ini rusak dan tidak bisa dibaca.")
        val rotation = exifRotation(src)
        val mime = b.outMimeType
        if (mime != null && ReceiptMath.keepOriginal(src.size, mime, b.outWidth, b.outHeight, rotation)) {
            // A damaged file can still have a valid header: make sure it decodes.
            val probe = BitmapFactory.decodeByteArray(src, 0, src.size,
                BitmapFactory.Options().apply { inSampleSize = ReceiptMath.sampleSize(b.outWidth, b.outHeight, 64, 64) })
                ?: throw Unreadable(damaged)
            probe.recycle()
            return ReceiptImage(src, mime, b.outWidth, b.outHeight)
        }

        val (tw, th) = ReceiptMath.fitted(b.outWidth, b.outHeight)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = ReceiptMath.sampleSize(b.outWidth, b.outHeight, tw, th)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(src, 0, src.size, opts) ?: throw Unreadable(damaged)
        var base = transform(decoded, tw, th, rotation)
        if (base.hasAlpha()) base = onWhite(base)
        try {
            var cached: Bitmap? = null
            val fit = ReceiptMath.compress(base.width, base.height, { w, h, q ->
                val bmp = if (w == base.width && h == base.height) base
                else cached?.takeIf { it.width == w && it.height == h }
                    ?: Bitmap.createScaledBitmap(base, w, h, true).also { cached?.recycle(); cached = it }
                ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
            }, { if (it.isEmpty()) Int.MAX_VALUE else it.size }) // an empty result means the encoder failed
            cached?.recycle()
            return ReceiptImage(fit.result, "image/jpeg", fit.width, fit.height)
        } finally {
            base.recycle()
        }
    }

    private fun exifRotation(src: ByteArray): Int = try {
        ReceiptMath.rotationFor(ExifInterface(ByteArrayInputStream(src)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    } catch (_: Throwable) { 0 }

    /** Scales to tw x th (sizes before rotation) and rotates upright. */
    private fun transform(src: Bitmap, tw: Int, th: Int, rotation: Int): Bitmap {
        if (rotation == 0 && src.width == tw && src.height == th) return src
        val m = Matrix().apply {
            postScale(tw.toFloat() / src.width, th.toFloat() / src.height)
            postRotate(rotation.toFloat())
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    /** JPEG has no transparency: transparent pixels would turn black, so draw the image on white first. */
    private fun onWhite(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        src.recycle()
        return out
    }

    /** Decodes for display at roughly [px] on the long side; null for missing or invalid data. */
    fun decode(bytes: ByteArray, px: Int): Bitmap? = try {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) null
        else {
            var s = 1
            while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= px) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (_: Throwable) { null }

    /** Row for receipt_image. */
    fun values(txId: Long, img: ReceiptImage) = ContentValues().apply {
        put("tx_id", txId)
        put("mime", img.mime)
        put("bytes", img.bytes.size)
        put("width", img.width)
        put("height", img.height)
        put("data", img.base64())
        put("updated", System.currentTimeMillis())
    }

    /** Daily tidy-up: rows whose transaction is gone, and leftover pre-1.1.1 files nothing points to. */
    fun cleanOrphans(ctx: Context) {
        Repo.deleteOrphanReceipts()
        val used = Repo.receiptsInUse()
        val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L
        dir(ctx).listFiles()?.forEach { if (it.name !in used && it.lastModified() < cutoff) it.delete() }
    }
}

/**
 * Backup = one .zip with the SQLite file, receipt photos and a small manifest.
 * Must run on the Repo thread (Repo.async / Repo.blocking) so nothing writes during the copy.
 */
object Backup {
    private const val MAGIC = "fintrack-idr-backup"
    private const val MAX_TOTAL = 1L shl 30 // 1 GB safety cap when restoring

    fun write(ctx: Context, out: OutputStream) {
        Repo.close() // flush and release the file; it reopens lazily
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("meta.json"))
            val meta = JSONObject().put("app", MAGIC).put("schema", Db.VERSION).put("created", System.currentTimeMillis())
            zip.write(meta.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(Db.NAME))
            Repo.dbFile().inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            Receipts.dir(ctx).listFiles()?.forEach { f ->
                zip.putNextEntry(ZipEntry("receipts/" + f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private val safeName = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}")

    fun restore(ctx: Context, input: InputStream) {
        val tmp = File(ctx.cacheDir, "restore")
        tmp.deleteRecursively()
        val tmpReceipts = File(tmp, "receipts").apply { mkdirs() }
        val tmpDb = File(tmp, Db.NAME)
        var meta: JSONObject? = null
        var total = 0L
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = e.name
                    val target: File? = when {
                        name == "meta.json" -> { meta = JSONObject(String(zip.readBytes(), Charsets.UTF_8)); null }
                        name == Db.NAME -> tmpDb
                        name.startsWith("receipts/") && safeName.matches(name.substring(9)) -> File(tmpReceipts, name.substring(9))
                        else -> null
                    }
                    if (target != null) {
                        target.outputStream().use { os ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_TOTAL) throw IllegalStateException("Backup too large")
                                os.write(buf, 0, n)
                            }
                        }
                    }
                    e = zip.nextEntry
                }
            }
            val m = meta
            if (m == null || m.optString("app") != MAGIC || !tmpDb.exists()) {
                throw IllegalStateException(tr("This file is not a Finance Tracker backup.", "File ini bukan cadangan Catatan Keuangan."))
            }
            if (m.optInt("schema", 0) > Db.VERSION) {
                throw IllegalStateException(tr("This backup was made by a newer app version. Update the app first.", "Cadangan ini dibuat oleh versi aplikasi yang lebih baru. Perbarui aplikasi terlebih dahulu."))
            }
            // Validate it is a readable database with our tables.
            val check = SQLiteDatabase.openDatabase(tmpDb.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                check.rawQuery("SELECT COUNT(*) FROM tx", null).close()
                check.rawQuery("SELECT COUNT(*) FROM wallet", null).close()
            } finally {
                check.close()
            }

            Repo.close()
            val dbFile = Repo.dbFile()
            dbFile.parentFile?.mkdirs()
            tmpDb.copyTo(dbFile, overwrite = true)
            listOf("-journal", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }
            val rd = Receipts.dir(ctx)
            rd.listFiles()?.forEach { it.delete() }
            tmpReceipts.listFiles()?.forEach { it.copyTo(File(rd, it.name), overwrite = true) }
            Repo.markChanged()
            Repo.db // reopen (runs migrations if the backup is from an older version)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
