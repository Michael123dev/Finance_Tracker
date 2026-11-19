package com.fintrack.idr.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.fintrack.idr.util.Lock

/** Minimal ViewModel: survives Activity recreation via onRetainNonConfigurationInstance. */
open class ViewModel {
    open fun onCleared() {}
}

/** Minimal LiveData: main-thread observable value, observers removed when the Activity is destroyed. */
class Live<T>(initial: T) {
    var value: T = initial
        private set
    private val observers = ArrayList<(T) -> Unit>()

    fun set(v: T) {
        value = v
        for (o in ArrayList(observers)) o(v)
    }

    fun observe(owner: BaseActivity, f: (T) -> Unit) {
        observers.add(f)
        owner.onDestroyed { observers.remove(f) }
        f(value)
    }
}

open class BaseActivity : Activity() {
    private var createdDark = false
    private var createdLangId = false
    private val destroyHooks = ArrayList<() -> Unit>()
    private var retained: ViewModel? = null

    fun onDestroyed(f: () -> Unit) { destroyHooks.add(f) }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(ThemeMode.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        createdDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        C.dark = createdDark
        createdLangId = com.fintrack.idr.util.L.id
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.bg
        window.navigationBarColor = C.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (createdDark) 0 else
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        window.decorView.setBackgroundColor(C.bg)
        // Hide balances from the recent-apps preview and block screenshots when the app lock is on.
        if (Lock.enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Returns the retained ViewModel or creates one. */
    @Suppress("UNCHECKED_CAST")
    protected fun <V : ViewModel> viewModel(create: () -> V): V {
        val existing = retained ?: (lastNonConfigurationInstance as? ViewModel)
        val vm = (existing as? V) ?: create()
        retained = vm
        return vm
    }

    @Deprecated("Deprecated in Java")
    override fun onRetainNonConfigurationInstance(): Any? {
        val r = retained
        retained = null // so onDestroy doesn't clear it
        return r
    }

    override fun onResume() {
        super.onResume()
        C.dark = createdDark
        val sysDark = ThemeMode.isDark(applicationContext.resources.configuration.uiMode)
        if (sysDark != createdDark || com.fintrack.idr.util.L.id != createdLangId) { recreate(); return }
        Lock.guard(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyHooks.forEach { it() }
        destroyHooks.clear()
        if (!isChangingConfigurations) retained?.onCleared()
    }
}
