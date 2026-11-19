package com.fintrack.idr.data

import java.time.LocalDate

/**
 * Recurring rules generate real transactions when they fall due.
 * The k-th occurrence is always computed from the start date (not from the previous occurrence),
 * so a rule on the 31st gives 31 Jan, 28 Feb, 31 Mar ... instead of drifting to the 28th forever.
 */
object RecurringEngine {
    /** Safety cap per rule per run (e.g. a daily rule backdated years ago). */
    const val MAX_PER_RUN = 1000

    fun occurrence(start: Long, freq: Int, every: Int, k: Int): Long {
        val s = LocalDate.ofEpochDay(start)
        val n = k.toLong() * maxOf(1, every)
        val d = when (freq) {
            Freq.DAILY -> s.plusDays(n)
            Freq.WEEKLY -> s.plusWeeks(n)
            Freq.MONTHLY -> s.plusMonths(n)
            else -> s.plusYears(n)
        }
        return d.toEpochDay()
    }

    /** Occurrence dates that are due (<= today) starting from occurrence index [count]. */
    fun dueDates(r: Recurring, today: Long, limit: Int = MAX_PER_RUN): List<Long> {
        val out = ArrayList<Long>()
        if (!r.active) return out
        var k = r.count
        while (out.size < limit) {
            val d = occurrence(r.startDate, r.freq, r.every, k)
            if (d > today) break
            if (r.endDate != null && d > r.endDate) break
            out.add(d)
            k++
        }
        return out
    }

    /** Creates any transactions that have fallen due. Returns the expense category ids touched (for budget alerts). */
    fun run(today: Long): Set<Long> {
        val touched = HashSet<Long>()
        for (r in Repo.recurringRules()) {
            val due = dueDates(r, today)
            if (due.isEmpty()) continue
            Repo.inTransaction {
                for (d in due) {
                    Repo.insertTx(
                        Tx(
                            type = r.type, amount = r.amount, categoryId = r.categoryId, walletId = r.walletId,
                            toWalletId = r.toWalletId, title = r.title, note = r.note, date = d,
                            time = System.currentTimeMillis(), recurringId = r.id,
                        )
                    )
                }
                Repo.setRecurringCount(r.id, r.count + due.size)
            }
            if (r.type == com.fintrack.idr.util.TxType.EXPENSE && r.categoryId != null) touched.add(r.categoryId)
        }
        return touched
    }
}
