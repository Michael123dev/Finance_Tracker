package com.fintrack.idr.util

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.fintrack.idr.R
import com.fintrack.idr.data.RecurringEngine
import com.fintrack.idr.data.Repo
import com.fintrack.idr.screens.LockActivity
import com.fintrack.idr.screens.MainActivity
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object Notif {
    const val CH_REMINDER = "reminders"
    const val CH_BUDGET = "budget"
    const val CH_RECURRING = "recurring"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REMINDER, tr("Daily reminder", "Pengingat harian"), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_BUDGET, tr("Budget alerts", "Peringatan anggaran"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_RECURRING, tr("Upcoming payments", "Pembayaran mendatang"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(ctx: Context, id: Int, channel: String, title: String, text: String) {
        if (!allowed(ctx)) return
        val open = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFFFFCD03.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) { }
    }
}

/** Notifies once per category per month when spending crosses the alert threshold and again when it exceeds 100%. */
object BudgetAlert {
    /** Call on the Repo thread. Returns an in-app message (or null). */
    fun check(ctx: Context, categoryId: Long, day: Long, notify: Boolean): String? {
        val cat = Repo.category(categoryId) ?: return null
        if (cat.budget <= 0) return null
        val ym = D.ym(day)
        val spent = Repo.budgetLines(D.first(ym), D.last(ym), true).firstOrNull { it.cat.id == categoryId }?.spent ?: return null
        val pct = (spent * 100 / cat.budget).toInt()
        val level = when {
            pct >= 100 -> 2
            pct >= Prefs.budgetThreshold -> 1
            else -> 0
        }
        if (level == 0) return null
        val key = D.ymKey(ym)
        val msg = if (level == 2)
            tr("${cat.label} budget exceeded: $pct% used (${Money.fmt(spent)} of ${Money.fmt(cat.budget)})",
                "Anggaran ${cat.label} terlampaui: $pct% terpakai (${Money.fmt(spent)} dari ${Money.fmt(cat.budget)})")
        else tr("${cat.label} budget is $pct% used. ${Money.fmt(cat.budget - spent)} left this month.",
            "Anggaran ${cat.label} sudah $pct% terpakai. Sisa ${Money.fmt(cat.budget - spent)} bulan ini.")
        if (notify && Prefs.budgetAlerts && Prefs.alertLevel(categoryId, key) < level) {
            Prefs.setAlertLevel(categoryId, key, level)
            Notif.post(ctx, 2000 + categoryId.toInt(), Notif.CH_BUDGET, tr("Budget alert", "Peringatan anggaran"), msg)
        }
        return msg
    }
}

/** Background work: create due recurring transactions and send "upcoming payment" reminders. */
object Maintenance {
    fun run(ctx: Context) {
        Repo.blocking {
            val today = D.today()
            val touched = RecurringEngine.run(today)
            touched.forEach { BudgetAlert.check(ctx, it, today, true) }
            if (Prefs.recurringReminders) {
                for (r in Repo.recurringRules()) {
                    val next = r.nextDate
                    if (r.finished || r.remindDays <= 0) continue
                    val daysLeft = next - today
                    if (daysLeft in 1..r.remindDays.toLong() && r.lastReminded != next) {
                        val title = r.title.ifBlank { r.catLabel }
                        val whenEn = if (daysLeft == 1L) "tomorrow" else "in $daysLeft days"
                        val whenId = if (daysLeft == 1L) "besok" else "dalam $daysLeft hari"
                        val text = tr("$title (${Money.fmt(r.amount)}) is due $whenEn, ${D.day(next)}. It will be recorded automatically.",
                            "$title (${Money.fmt(r.amount)}) jatuh tempo $whenId, ${D.day(next)}. Akan dicatat otomatis.")
                        Notif.post(ctx, 3000 + r.id.toInt(), Notif.CH_RECURRING, tr("Upcoming payment", "Pembayaran mendatang"), text)
                        Repo.setRecurringReminded(r.id, next)
                    }
                }
            }
            com.fintrack.idr.data.Receipts.cleanOrphans(ctx)
        }
    }
}

object Scheduler {
    private const val JOB_ID = 42

    fun ensure(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) == null) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, MaintenanceJob::class.java))
                .setPeriodic(6 * 60 * 60 * 1000L)
                .setPersisted(true)
                .build()
            try { js.schedule(job) } catch (_: Throwable) { }
        }
        scheduleReminder(ctx)
    }

    private fun reminderIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun scheduleReminder(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = reminderIntent(ctx)
        am.cancel(pi)
        if (!Prefs.dailyReminder) return
        val mins = Prefs.reminderTime
        var at = LocalDate.now().atTime(mins / 60, mins % 60)
        if (!at.isAfter(LocalDateTime.now())) at = at.plusDays(1)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, millis, AlarmManager.INTERVAL_DAY, pi)
    }
}

class MaintenanceJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try { Maintenance.run(applicationContext) } catch (_: Throwable) { }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val app = ctx.applicationContext
                val count = Repo.blocking { Repo.txCountOn(D.today()) }
                if (count == 0L) {
                    Notif.post(app, 1000, Notif.CH_REMINDER, tr("Record today's spending", "Catat pengeluaran hari ini"),
                        tr("You haven't recorded any transactions today. It takes 10 seconds.", "Kamu belum mencatat transaksi hari ini. Cuma butuh 10 detik."))
                }
                Maintenance.run(app)
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.ensure(ctx.applicationContext)
    }
}

/** Minimal FileProvider replacement: lets the camera app write a photo into our cache folder. */
class FilesProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.fintrack.idr.files"
        fun cameraFile(ctx: Context): File = File(File(ctx.cacheDir, "camera").apply { mkdirs() }, "capture.jpg")
        fun cameraUri(): Uri = Uri.parse("content://$AUTHORITY/camera/capture.jpg")
    }

    private fun resolve(uri: Uri): File? {
        val c = context ?: return null
        return if (uri.path == "/camera/capture.jpg") cameraFile(c) else null
    }

    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = resolve(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = resolve(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else if (it == OpenableColumns.DISPLAY_NAME) f.name else null }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/**
 * App lock: after the app has been in the background longer than the configured time,
 * any screen that resumes sends the user to the PIN / biometric screen first.
 */
object Lock : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var backgroundAt = 0L
    @Volatile var unlocked = false
    /** Set before opening the camera / file picker so returning from it doesn't lock. */
    var externalUntil = 0L

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    fun allowExternal() { externalUntil = System.currentTimeMillis() + 10 * 60 * 1000L }

    /** Any lock switched on: app PIN and/or fingerprint lock. */
    val enabled: Boolean get() = Security.enabled || Prefs.biometric

    fun required(): Boolean = enabled && !unlocked

    fun guard(a: Activity) {
        if (a is LockActivity) return
        if (required()) {
            a.startActivity(Intent(a, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
            a.overridePendingTransition(0, 0)
        }
    }

    override fun onActivityStarted(a: Activity) {
        if (started == 0 && backgroundAt > 0) {
            val away = System.currentTimeMillis() - backgroundAt
            val external = System.currentTimeMillis() < externalUntil
            if (!external && away >= Prefs.lockAfterSec * 1000L) unlocked = false
        }
        started++
    }

    override fun onActivityStopped(a: Activity) {
        started--
        if (started <= 0) {
            started = 0
            backgroundAt = System.currentTimeMillis()
        }
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityResumed(a: Activity) { externalUntil = 0 }
    override fun onActivityPaused(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
