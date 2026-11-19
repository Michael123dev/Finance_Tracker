package com.fintrack.idr.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.fintrack.idr.util.tr
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Receipt photos live in app-private storage and are included in backups. */
object Receipts {
    private const val MAX_DIM = 1600

    fun dir(ctx: Context): File = File(ctx.filesDir, "receipts").apply { mkdirs() }
    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    /** Decodes, downsizes, fixes rotation and stores as JPEG. [open] must return a fresh stream each call. */
    fun save(ctx: Context, open: () -> InputStream?): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw IllegalStateException("Cannot open image")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException(tr("Not an image", "Bukan gambar"))
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: throw IllegalStateException("Decode failed")

        val rotation = try {
            open()?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Throwable) { 0f }

        val scale = minOf(1f, MAX_DIM.toFloat() / maxOf(bmp.width, bmp.height))
        if (rotation != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        val name = UUID.randomUUID().toString() + ".jpg"
        file(ctx, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        bmp.recycle()
        return name
    }

    fun delete(ctx: Context, name: String?) {
        if (name.isNullOrBlank()) return
        file(ctx, name).delete()
    }

    fun thumbnail(ctx: Context, name: String, px: Int): Bitmap? = try {
        val f = file(ctx, name)
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, b)
        var s = 1
        while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= px) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    } catch (_: Throwable) { null }

    /** Removes receipt files that no transaction references (e.g. after a cancelled edit). */
    fun cleanOrphans(ctx: Context) {
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
