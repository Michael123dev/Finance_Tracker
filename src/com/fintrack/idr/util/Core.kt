package com.fintrack.idr.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.io.File
import java.security.SecureRandom
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * UI language. mode 0 = follow the phone, 1 = English, 2 = Bahasa Indonesia (chosen in Settings, stored in Prefs).
 * Kept free of Android calls so the text logic stays unit-testable.
 */
object L {
    const val SYSTEM = 0
    const val EN = 1
    const val ID = 2

    @Volatile var mode = SYSTEM
    /** Region of the phone (for week start etc.), set at startup. */
    @Volatile var systemCountry = ""

    fun isId(l: Locale) = l.language == "in" || l.language == "id"

    val id: Boolean
        get() = when (mode) {
            EN -> false
            ID -> true
            else -> isId(Locale.getDefault())
        }

    /** Locale used for dates and system widgets; null means "use the phone's". */
    fun override(): Locale? = when (mode) {
        EN -> Locale("en", systemCountry)
        ID -> Locale("in", "ID")
        else -> null
    }

    fun locale(): Locale = override() ?: Locale.getDefault()
}

fun tr(en: String, id: String): String = if (L.id) id else en

/** Show/hide amounts (eye icon). Session only: resets to visible when the app process restarts. */
object Privacy {
    @Volatile var hidden = false
    const val MASK = "••••••"
}

/** All money is stored as whole rupiah in a Long. IDR has no minor unit in daily use. */
object Money {
    const val MAX = 999_999_999_999L

    fun group(v: Long): String {
        val s = (if (v == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(v)).toString()
        val sb = StringBuilder(s.length + s.length / 3)
        val n = s.length
        for (i in 0 until n) {
            if (i > 0 && (n - i) % 3 == 0) sb.append('.')
            sb.append(s[i])
        }
        return sb.toString()
    }

    /** Rp1.250.000 / -Rp1.250.000 */
    fun fmt(v: Long): String = if (Privacy.hidden) "Rp" + Privacy.MASK else (if (v < 0) "-" else "") + "Rp" + group(v)

    /** Prefixes + or - by transaction type. */
    fun signed(v: Long, type: Int): String = when (type) {
        TxType.INCOME -> "+" + fmt(v)
        TxType.EXPENSE -> "-" + fmt(v)
        else -> fmt(v)
    }

    /** Short form for charts and tight spaces: 1,2 jt / 1.2M */
    fun compact(v: Long): String {
        if (Privacy.hidden) return "•••"
        val a = if (v == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(v)
        val neg = if (v < 0) "-" else ""
        if (a < 1000) return neg + a
        val unitsEn = arrayOf("K", "M", "B", "T")
        val unitsId = arrayOf(" rb", " jt", " M", " T")
        var idx = 0
        var x = a / 1000.0
        while (x >= 999.95 && idx < 3) { x /= 1000.0; idx++ }
        val r = Math.round(x * 10) / 10.0
        var s = if (r == Math.floor(r)) r.toLong().toString() else r.toString()
        if (L.id) s = s.replace('.', ',')
        return neg + s + (if (L.id) unitsId[idx] else unitsEn[idx])
    }

    /** Parses user input; keeps digits only, capped at MAX. */
    fun parse(s: CharSequence?): Long {
        if (s == null) return 0
        var v = 0L
        for (c in s) {
            if (c in '0'..'9') {
                v = v * 10 + (c - '0')
                if (v > MAX) return MAX
            }
        }
        return v
    }
}

object TxType {
    const val EXPENSE = 0
    const val INCOME = 1
    const val TRANSFER = 2
    fun label(t: Int): String = when (t) {
        INCOME -> tr("Income", "Pemasukan")
        EXPENSE -> tr("Expense", "Pengeluaran")
        else -> tr("Transfer", "Transfer")
    }
}

/** Dates are stored as epoch days (LocalDate.toEpochDay) - timezone-safe and easy to range-query. */
object D {
    fun today(): Long = LocalDate.now().toEpochDay()
    fun ld(day: Long): LocalDate = LocalDate.ofEpochDay(day)
    fun ym(day: Long): YearMonth = YearMonth.from(ld(day))
    fun first(ym: YearMonth): Long = ym.atDay(1).toEpochDay()
    fun last(ym: YearMonth): Long = ym.atEndOfMonth().toEpochDay()
    fun ymKey(ym: YearMonth): Int = ym.year * 100 + ym.monthValue

    private fun loc(): Locale = L.locale()
    fun day(day: Long): String = DateTimeFormatter.ofPattern("d MMM yyyy", loc()).format(ld(day))
    fun dayShort(day: Long): String = DateTimeFormatter.ofPattern("d MMM", loc()).format(ld(day))
    fun month(ym: YearMonth): String {
        val m = ym.month.getDisplayName(TextStyle.FULL_STANDALONE, loc())
        return m.replaceFirstChar { it.uppercase() } + " " + ym.year
    }
    fun monthShort(ym: YearMonth): String = ym.month.getDisplayName(TextStyle.SHORT_STANDALONE, loc()).replaceFirstChar { it.uppercase() }
    fun weekday(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT_STANDALONE, loc()).replaceFirstChar { it.uppercase() }

    fun relative(day: Long): String {
        val t = today()
        return when (day) {
            t -> tr("Today", "Hari ini")
            t - 1 -> tr("Yesterday", "Kemarin")
            t + 1 -> tr("Tomorrow", "Besok")
            else -> {
                val d = ld(day)
                DateTimeFormatter.ofPattern(if (d.year == LocalDate.now().year) "EEE, d MMM" else "EEE, d MMM yyyy", loc()).format(d)
            }
        }
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) { sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    var theme: Int
        get() = sp.getInt("theme", THEME_SYSTEM)
        set(v) = sp.edit().putInt("theme", v).apply()

    var pinHash: String?
        get() = sp.getString("pin_hash", null)
        set(v) = sp.edit().putString("pin_hash", v).apply()
    var pinSalt: String?
        get() = sp.getString("pin_salt", null)
        set(v) = sp.edit().putString("pin_salt", v).apply()
    var pinLength: Int
        get() = sp.getInt("pin_length", 4)
        set(v) = sp.edit().putInt("pin_length", v).apply()
    var biometric: Boolean
        get() = sp.getBoolean("biometric", false)
        set(v) = sp.edit().putBoolean("biometric", v).apply()
    /** Seconds in background before the lock engages again. */
    var lockAfterSec: Int
        get() = sp.getInt("lock_after", 30)
        set(v) = sp.edit().putInt("lock_after", v).apply()
    var failedAttempts: Int
        get() = sp.getInt("pin_failed", 0)
        set(v) = sp.edit().putInt("pin_failed", v).apply()
    var lockoutUntil: Long
        get() = sp.getLong("pin_lockout", 0)
        set(v) = sp.edit().putLong("pin_lockout", v).apply()

    var dailyReminder: Boolean
        get() = sp.getBoolean("daily_reminder", false)
        set(v) = sp.edit().putBoolean("daily_reminder", v).apply()
    /** Minutes after midnight. */
    var reminderTime: Int
        get() = sp.getInt("reminder_time", 20 * 60)
        set(v) = sp.edit().putInt("reminder_time", v).apply()
    var budgetAlerts: Boolean
        get() = sp.getBoolean("budget_alerts", true)
        set(v) = sp.edit().putBoolean("budget_alerts", v).apply()
    var budgetThreshold: Int
        get() = sp.getInt("budget_threshold", 80)
        set(v) = sp.edit().putInt("budget_threshold", v).apply()
    var recurringReminders: Boolean
        get() = sp.getBoolean("recurring_reminders", true)
        set(v) = sp.edit().putBoolean("recurring_reminders", v).apply()
    var askedNotifPermission: Boolean
        get() = sp.getBoolean("asked_notif", false)
        set(v) = sp.edit().putBoolean("asked_notif", v).apply()
    var language: Int
        get() = sp.getInt("language", L.SYSTEM)
        set(v) = sp.edit().putInt("language", v).apply()
    var lastWallet: Long
        get() = sp.getLong("last_wallet", -1)
        set(v) = sp.edit().putLong("last_wallet", v).apply()

    /** Budget alert level already sent for category+month: 0 none, 1 threshold, 2 exceeded. */
    fun alertLevel(cat: Long, ymKey: Int): Int = sp.getInt("ba_${cat}_$ymKey", 0)
    fun setAlertLevel(cat: Long, ymKey: Int, level: Int) = sp.edit().putInt("ba_${cat}_$ymKey", level).apply()

    /** Removes only the app PIN (fingerprint lock stays as configured). */
    fun clearPin() = sp.edit().remove("pin_hash").remove("pin_salt").putInt("pin_failed", 0).putLong("pin_lockout", 0).apply()

    /** Removes every lock (used when erasing all data). */
    fun clearSecurity() = sp.edit().remove("pin_hash").remove("pin_salt").putBoolean("biometric", false)
        .putInt("pin_failed", 0).putLong("pin_lockout", 0).apply()
}

object Security {
    fun newSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    fun hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Base64.decode(salt, Base64.NO_WRAP), 20000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    fun setPin(pin: String) {
        val salt = newSalt()
        Prefs.pinSalt = salt
        Prefs.pinHash = hash(pin, salt)
        Prefs.failedAttempts = 0
        Prefs.lockoutUntil = 0
    }

    fun check(pin: String): Boolean {
        val salt = Prefs.pinSalt ?: return false
        val h = Prefs.pinHash ?: return false
        // constant-time compare
        val a = hash(pin, salt)
        if (a.length != h.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor h[i].code)
        return r == 0
    }

    val enabled: Boolean get() = Prefs.pinHash != null
}

/** Saves uncaught crashes so the next launch can show them (there is no crash reporting service). */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                file(ctx).writeText("Thread: ${t.name}\nAndroid SDK ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n$sw")
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val s = try { f.readText() } catch (_: Throwable) { null }
        f.delete()
        return s
    }
}
