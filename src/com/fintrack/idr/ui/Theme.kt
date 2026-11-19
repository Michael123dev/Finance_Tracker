package com.fintrack.idr.ui

import android.content.Context
import android.content.res.Configuration
import com.fintrack.idr.util.Prefs

/**
 * Palette from the brief: Primary #FFCD03, Dark #010201, White #FEFEFF, Gray #6B6B6B.
 * Semantic and chart colors are added on top, chosen for contrast in both modes
 * (yellow is never used as text on a light surface - it fails contrast).
 */
object C {
    @JvmStatic var dark = false

    const val brand = 0xFFFFCD03.toInt()
    const val onBrand = 0xFF010201.toInt()
    const val ink = 0xFF010201.toInt()
    const val white = 0xFFFEFEFF.toInt()
    const val gray = 0xFF6B6B6B.toInt()

    val bg get() = if (dark) 0xFF010201.toInt() else 0xFFF3F3F0.toInt()
    val card get() = if (dark) 0xFF161616.toInt() else white
    val card2 get() = if (dark) 0xFF242424.toInt() else 0xFFECECE8.toInt()
    val text get() = if (dark) white else ink
    val text2 get() = if (dark) 0xFFA8A8A8.toInt() else gray
    val text3 get() = if (dark) 0xFF7A7A7A.toInt() else 0xFF8E8E8A.toInt()
    val line get() = if (dark) 0xFF2A2A2A.toInt() else 0xFFE2E2DE.toInt()
    val accent get() = if (dark) brand else 0xFF7A5F00.toInt()
    val brandSoft get() = if (dark) 0xFF2E2600.toInt() else 0xFFFFF2BA.toInt()
    val meter get() = if (dark) brand else ink

    val income get() = if (dark) 0xFF4CD38A.toInt() else 0xFF127A45.toInt()
    val expense get() = if (dark) 0xFFFF7A6E.toInt() else 0xFFC9372C.toInt()
    val transfer get() = if (dark) 0xFF8DB4FF.toInt() else 0xFF2F62C8.toInt()
    val warn get() = if (dark) 0xFFFFB020.toInt() else 0xFFB26B00.toInt()

    // Chart fills (validated: income/expense pair relies on legend + position, not color alone).
    val chartIncome get() = if (dark) 0xFF199E70.toInt() else 0xFF1BAF7A.toInt()
    val chartExpense get() = if (dark) 0xFFE66767.toInt() else 0xFFE34948.toInt()
    val grid get() = if (dark) 0xFF2C2C2A.toInt() else 0xFFE1E0D9.toInt()
    val ripple get() = if (dark) 0x33FFFFFF else 0x1F000000

    fun typeColor(type: Int) = when (type) {
        com.fintrack.idr.util.TxType.INCOME -> income
        com.fintrack.idr.util.TxType.EXPENSE -> expense
        else -> transfer
    }
}

object ThemeMode {
    fun isDark(systemUiMode: Int): Boolean = when (Prefs.theme) {
        Prefs.THEME_LIGHT -> false
        Prefs.THEME_DARK -> true
        else -> (systemUiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Applies the in-app Light/Dark and language choices to an Activity's resources,
     * so values-night styles and system widgets (date/time pickers, dialogs) follow them.
     */
    fun wrap(base: Context): Context {
        val sys = base.resources.configuration
        val dark = isDark(sys.uiMode)
        val cfg = Configuration(sys)
        cfg.uiMode = (sys.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        com.fintrack.idr.util.L.override()?.let { cfg.setLocale(it) }
        return base.createConfigurationContext(cfg)
    }
}
