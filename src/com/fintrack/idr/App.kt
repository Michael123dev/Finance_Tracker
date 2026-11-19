package com.fintrack.idr

import android.app.Application
import com.fintrack.idr.data.Demo
import com.fintrack.idr.data.Repo
import com.fintrack.idr.util.L
import com.fintrack.idr.util.CrashLog
import com.fintrack.idr.util.Lang
import com.fintrack.idr.util.Lock
import com.fintrack.idr.util.Maintenance
import com.fintrack.idr.util.Notif
import com.fintrack.idr.util.Prefs
import com.fintrack.idr.util.Scheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Prefs.init(this)
        val demoFirstRun = Demo.active(this) && !getSharedPreferences("demo", MODE_PRIVATE).getBoolean("seeded", false)
        if (demoFirstRun) Prefs.language = L.ID // demo opens in Indonesian; can still be changed in Settings
        Lang.init()
        Repo.init(this)
        if (demoFirstRun) Demo.seedIfNeeded(this)
        Notif.createChannels(this)
        Lock.install(this)
        try { Scheduler.ensure(this) } catch (_: Throwable) { }
        // Record any recurring transactions that fell due while the app was closed.
        Thread { try { Maintenance.run(this) } catch (_: Throwable) { } }.start()
    }
}
