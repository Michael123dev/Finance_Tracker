package com.fintrack.idr.data

import com.fintrack.idr.util.TxType
import com.fintrack.idr.util.tr

object WalletKind {
    const val CASH = 0
    const val BANK = 1
    const val EWALLET = 2
    const val OTHER = 3
    val all = intArrayOf(CASH, BANK, EWALLET, OTHER)
    fun label(k: Int): String = when (k) {
        CASH -> tr("Cash", "Tunai")
        BANK -> tr("Bank account", "Rekening bank")
        EWALLET -> tr("E-wallet", "Dompet digital")
        else -> tr("Other", "Lainnya")
    }
    fun icon(k: Int): String = when (k) {
        CASH -> "💵"; BANK -> "🏦"; EWALLET -> "📱"; else -> "👛"
    }
}

data class Wallet(
    val id: Long,
    val name: String,
    val kind: Int,
    val icon: String,
    val initial: Long,
    val archived: Boolean,
    val balance: Long = 0,
)

/** Default categories keep a key (dkey) so their names follow the phone language until the user renames them. */
data class Category(
    val id: Long,
    val type: Int,
    val dkey: String?,
    val name: String?,
    val icon: String,
    val budget: Long,
) {
    val label: String get() = name ?: Defaults.categoryName(dkey) ?: "?"
}

data class Tx(
    val id: Long = 0,
    val type: Int,
    val amount: Long,
    val categoryId: Long?,
    val walletId: Long,
    val toWalletId: Long?,
    val title: String,
    val note: String,
    val date: Long,
    val time: Long,
    val recurringId: Long? = null,
    val receipt: String? = null,
)

/** A transaction joined with display names. */
data class TxRow(
    val tx: Tx,
    val catLabel: String,
    val catIcon: String,
    val wallet: String,
    val toWallet: String?,
) {
    val displayTitle: String
        get() = when {
            tx.title.isNotBlank() -> tx.title
            tx.type == TxType.TRANSFER -> "$wallet → ${toWallet ?: "?"}"
            else -> catLabel
        }
    val subtitle: String
        get() = if (tx.type == TxType.TRANSFER) tr("Transfer", "Transfer") + " · " + wallet + " → " + (toWallet ?: "?")
        else "$catLabel · $wallet"
}

object Freq {
    const val DAILY = 0
    const val WEEKLY = 1
    const val MONTHLY = 2
    const val YEARLY = 3
    val all = intArrayOf(DAILY, WEEKLY, MONTHLY, YEARLY)
    fun label(f: Int, every: Int = 1): String {
        if (every <= 1) return when (f) {
            DAILY -> tr("Daily", "Harian")
            WEEKLY -> tr("Weekly", "Mingguan")
            MONTHLY -> tr("Monthly", "Bulanan")
            else -> tr("Yearly", "Tahunan")
        }
        return when (f) {
            DAILY -> tr("Every $every days", "Setiap $every hari")
            WEEKLY -> tr("Every $every weeks", "Setiap $every minggu")
            MONTHLY -> tr("Every $every months", "Setiap $every bulan")
            else -> tr("Every $every years", "Setiap $every tahun")
        }
    }
}

data class Recurring(
    val id: Long = 0,
    val type: Int,
    val amount: Long,
    val categoryId: Long?,
    val walletId: Long,
    val toWalletId: Long?,
    val title: String,
    val note: String,
    val freq: Int,
    val every: Int,
    val startDate: Long,
    val endDate: Long?,
    val count: Int,
    val active: Boolean,
    val remindDays: Int,
    val lastReminded: Long,
    // display helpers (joined)
    val catLabel: String = "",
    val catIcon: String = "🔁",
    val walletName: String = "",
) {
    val nextDate: Long get() = RecurringEngine.occurrence(startDate, freq, every, count)
    val finished: Boolean get() = endDate != null && nextDate > endDate
}

data class Goal(
    val id: Long,
    val name: String,
    val icon: String,
    val target: Long,
    val deadline: Long?,
    val created: Long,
    val saved: Long,
) {
    val pct: Int get() = if (target <= 0) 0 else ((saved * 100) / target).toInt()
    val done: Boolean get() = saved >= target && target > 0
}

data class GoalEntry(val id: Long, val goalId: Long, val amount: Long, val date: Long, val note: String)

data class BudgetLine(val cat: Category, val budget: Long, val spent: Long) {
    val pct: Int get() = if (budget <= 0) 0 else ((spent * 100) / budget).toInt()
    val left: Long get() = budget - spent
}

data class CatSum(val catId: Long, val label: String, val icon: String, val total: Long, val count: Int)

data class TxFilter(
    val from: Long? = null,
    val to: Long? = null,
    val type: Int? = null,
    val categoryId: Long? = null,
    val walletId: Long? = null,
    val search: String = "",
    val limit: Int = 0,
)

object Defaults {
    class Cat(val key: String, val type: Int, val icon: String, val en: String, val id: String)

    val categories = listOf(
        Cat("food", TxType.EXPENSE, "🍔", "Food & Beverage", "Makanan & Minuman"),
        Cat("transport", TxType.EXPENSE, "🚗", "Transportation", "Transportasi"),
        Cat("installment", TxType.EXPENSE, "💳", "Installments", "Cicilan"),
        Cat("entertainment", TxType.EXPENSE, "🎬", "Entertainment", "Hiburan"),
        Cat("shopping", TxType.EXPENSE, "🛍️", "Shopping", "Belanja"),
        Cat("bills", TxType.EXPENSE, "💡", "Bills & Utilities", "Tagihan & Utilitas"),
        Cat("housing", TxType.EXPENSE, "🏠", "Rent & Housing", "Sewa & Rumah"),
        Cat("health", TxType.EXPENSE, "💊", "Health", "Kesehatan"),
        Cat("education", TxType.EXPENSE, "📚", "Education", "Pendidikan"),
        Cat("subscription", TxType.EXPENSE, "📺", "Subscriptions", "Langganan"),
        Cat("savings", TxType.EXPENSE, "🐷", "Savings", "Tabungan"),
        Cat("other_exp", TxType.EXPENSE, "📦", "Other", "Lainnya"),
        Cat("salary", TxType.INCOME, "💼", "Salary", "Gaji"),
        Cat("bonus", TxType.INCOME, "🎁", "Bonus", "Bonus"),
        Cat("business", TxType.INCOME, "🏪", "Business", "Usaha"),
        Cat("investment", TxType.INCOME, "📈", "Investment", "Investasi"),
        Cat("gift", TxType.INCOME, "🎀", "Gift", "Hadiah"),
        Cat("other_inc", TxType.INCOME, "💰", "Other income", "Pemasukan lain"),
    )
    private val byKey = categories.associateBy { it.key }
    fun categoryName(key: String?): String? = byKey[key]?.let { tr(it.en, it.id) }

    val emojis = listOf(
        "🍔", "🍜", "☕", "🍱", "🛒", "🚗", "🛵", "⛽", "🚌", "✈️", "🏠", "💡", "💧", "📶", "📱", "💳",
        "🧾", "🎬", "🎮", "🎵", "📺", "🛍️", "👕", "💄", "💊", "🏥", "🏋️", "📚", "🎓", "👶", "🐶", "🎁",
        "🎀", "💍", "🕌", "🙏", "🤝", "💼", "🏪", "📈", "💰", "💵", "🏦", "🐷", "🪙", "📦", "🔧", "🧹",
        "✂️", "🚬", "🍺", "🎉", "🏖️", "⚽", "🎯", "🛡️", "🚑", "🏍️", "🚕", "🧸",
    )
}
