import com.fintrack.idr.data.BudgetLine
import com.fintrack.idr.data.CatSum
import com.fintrack.idr.data.Category
import com.fintrack.idr.data.Export
import com.fintrack.idr.data.Freq
import com.fintrack.idr.data.Insights
import com.fintrack.idr.data.Recurring
import com.fintrack.idr.data.RecurringEngine
import com.fintrack.idr.data.ReportData
import com.fintrack.idr.data.Tx
import com.fintrack.idr.data.TxRow
import com.fintrack.idr.util.D
import com.fintrack.idr.util.L
import com.fintrack.idr.util.Money
import com.fintrack.idr.util.Privacy
import com.fintrack.idr.util.tr
import com.fintrack.idr.util.TxType
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

var failures = 0
var passes = 0
fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) passes++ else { failures++; println("FAIL $name: expected <$expected> but was <$actual>") }
}
fun d(s: String) = LocalDate.parse(s).toEpochDay()

fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "/tmp" })
    for (loc in listOf(Locale.US, Locale("in", "ID"))) {
        Locale.setDefault(loc)
        val id = loc.language == "in" || loc.language == "id"
        // ---- Money
        check("group0", Money.group(0), "0")
        check("group", Money.group(1_250_000), "1.250.000")
        check("group999", Money.group(999), "999")
        check("groupBig", Money.group(999_999_999_999), "999.999.999.999")
        check("fmtNeg", Money.fmt(-50_000), "-Rp50.000")
        check("signedExp", Money.signed(25_000, TxType.EXPENSE), "-Rp25.000")
        check("signedInc", Money.signed(25_000, TxType.INCOME), "+Rp25.000")
        check("compact small", Money.compact(950), "950")
        check("compact 15k", Money.compact(15_000), if (id) "15 rb" else "15K")
        check("compact 1.25jt", Money.compact(1_250_000), if (id) "1,3 jt" else "1.3M")
        check("compact roll", Money.compact(999_950), if (id) "1 jt" else "1M")
        check("compact 2.5B", Money.compact(2_500_000_000), if (id) "2,5 M" else "2.5B")
        check("compact neg", Money.compact(-12_000), if (id) "-12 rb" else "-12K")
        check("parse", Money.parse("1.250.000"), 1_250_000L)
        check("parse null", Money.parse(null), 0L)
        check("parse junk", Money.parse("Rp 12a3"), 123L)
        check("parse cap", Money.parse("99999999999999999"), Money.MAX)
    }
    Locale.setDefault(Locale.US)

    // ---- Recurring dates
    val jan31 = d("2026-01-31")
    check("monthly k1", LocalDate.ofEpochDay(RecurringEngine.occurrence(jan31, Freq.MONTHLY, 1, 1)).toString(), "2026-02-28")
    check("monthly k2 no drift", LocalDate.ofEpochDay(RecurringEngine.occurrence(jan31, Freq.MONTHLY, 1, 2)).toString(), "2026-03-31")
    check("monthly every 3", LocalDate.ofEpochDay(RecurringEngine.occurrence(jan31, Freq.MONTHLY, 3, 1)).toString(), "2026-04-30")
    check("weekly every 2", LocalDate.ofEpochDay(RecurringEngine.occurrence(d("2026-11-02"), Freq.WEEKLY, 2, 3)).toString(), "2026-12-14")
    check("yearly leap", LocalDate.ofEpochDay(RecurringEngine.occurrence(d("2024-02-29"), Freq.YEARLY, 1, 1)).toString(), "2025-02-28")
    check("yearly leap 4", LocalDate.ofEpochDay(RecurringEngine.occurrence(d("2024-02-29"), Freq.YEARLY, 1, 4)).toString(), "2028-02-29")
    check("daily", LocalDate.ofEpochDay(RecurringEngine.occurrence(d("2026-12-30"), Freq.DAILY, 1, 3)).toString(), "2027-01-02")

    val today = d("2026-11-19")
    fun rule(start: Long, freq: Int, every: Int, count: Int, end: Long? = null, active: Boolean = true) =
        Recurring(1, TxType.EXPENSE, 100, 1, 1, null, "Rent", "", freq, every, start, end, count, active, 1, -1)
    check("due none future", RecurringEngine.dueDates(rule(today + 1, Freq.MONTHLY, 1, 0), today).size, 0)
    check("due today", RecurringEngine.dueDates(rule(today, Freq.MONTHLY, 1, 0), today).size, 1)
    check("due catch-up", RecurringEngine.dueDates(rule(d("2026-08-25"), Freq.MONTHLY, 1, 1), today).map { LocalDate.ofEpochDay(it).toString() },
        listOf("2026-09-25", "2026-10-25"))
    check("due end date", RecurringEngine.dueDates(rule(d("2026-01-01"), Freq.MONTHLY, 1, 0, end = d("2026-03-15")), today).size, 3)
    check("due paused", RecurringEngine.dueDates(rule(d("2026-01-01"), Freq.MONTHLY, 1, 0, active = false), today).size, 0)
    check("due cap", RecurringEngine.dueDates(rule(d("2000-01-01"), Freq.DAILY, 1, 0), today).size, RecurringEngine.MAX_PER_RUN)
    check("finished flag", rule(d("2026-01-01"), Freq.MONTHLY, 1, 3, end = d("2026-03-15")).finished, true)
    check("next date", LocalDate.ofEpochDay(rule(d("2026-08-25"), Freq.MONTHLY, 1, 3).nextDate).toString(), "2026-11-25")

    // ---- CSV
    fun row(id: Long, type: Int, amt: Long, title: String, note: String, date: String, cat: String = "Food & Beverage", to: String? = null) =
        TxRow(Tx(id, type, amt, if (type == TxType.TRANSFER) null else 1L, 1, if (to != null) 2L else null, title, note, d(date), 0), cat, "🍔", "Cash", to)
    val rows = listOf(
        row(1, TxType.EXPENSE, 35_000, "Lunch, \"warteg\"", "with team\nnasi", "2026-11-03"),
        row(2, TxType.INCOME, 12_500_000, "Salary", "", "2026-11-01", "Salary"),
        row(3, TxType.TRANSFER, 500_000, "", "", "2026-11-05", "Transfer", to = "GoPay"),
        row(4, TxType.EXPENSE, 9_000, "=HYPERLINK(\"x\")", "-2+3", "2026-11-06"),
        row(5, TxType.EXPENSE, 120_000, "Bensin & <tol>", "Jalan tol ✓ 🚗", "2026-11-07", "Transportation"),
    )
    val csv = String(Export.csv(rows), Charsets.UTF_8)
    check("csv BOM", csv[0], '﻿')
    val lines = csv.substring(1).split("\r\n")
    check("csv header", lines[0], "Date,Type,Category,Wallet,To wallet,Name,Description,Income,Expense,Transfer")
    check("csv quoted", lines[1], "2026-11-03,Expense,Food & Beverage,Cash,,\"Lunch, \"\"warteg\"\"\",\"with team\nnasi\",,35000,")
    check("csv income", lines[2], "2026-11-01,Income,Salary,Cash,,Salary,,12500000,,")
    check("csv transfer", lines[3], "2026-11-05,Transfer,,Cash,GoPay,,,,,500000")
    check("csv injection", lines[4], "2026-11-06,Expense,Food & Beverage,Cash,,\"'=HYPERLINK(\"\"x\"\")\",'-2+3,,9000,")
    File(outDir, "test.csv").writeBytes(Export.csv(rows))

    // ---- XLSX (validated separately with openpyxl / LibreOffice)
    val months = listOf(Export.MonthSum(YearMonth.of(2026, 10), 12_000_000, 9_100_000), Export.MonthSum(YearMonth.of(2026, 11), 12_500_000, 164_000))
    val cats = listOf(CatSum(2, "Transportation", "🚗", 120_000, 1), CatSum(1, "Food & Beverage", "🍔", 44_000, 2))
    File(outDir, "test-en.xlsx").writeBytes(Export.xlsx(rows, months, cats, "November 2026"))
    Locale.setDefault(Locale("in", "ID"))
    File(outDir, "test-id.xlsx").writeBytes(Export.xlsx(rows, months, cats, "November 2026"))
    File(outDir, "test-empty.xlsx").writeBytes(Export.xlsx(emptyList(), emptyList(), emptyList(), "Kosong"))
    Locale.setDefault(Locale.US)

    // ---- Insights
    val food = Category(1, TxType.EXPENSE, "food", null, "🍔", 1_500_000)
    val trans = Category(2, TxType.EXPENSE, "transport", null, "🚗", 500_000)
    val daily = LongArray(30)
    daily[0] = 200_000; daily[6] = 900_000; daily[7] = 700_000; daily[13] = 400_000 // Nov 7/8 2026 are Sat/Sun
    val rep = ReportData(
        YearMonth.of(2026, 11), 10_000_000, 11_200_000, 9_000_000, 8_000_000,
        listOf(CatSum(1, "Food & Beverage", "🍔", 5_000_000, 40), CatSum(2, "Transportation", "🚗", 900_000, 10)),
        mapOf(1L to 3_000_000L, 2L to 1_000_000L),
        emptyList(), daily, months, emptyList(), emptyList(),
        listOf(BudgetLine(food, 1_500_000, 5_000_000), BudgetLine(trans, 500_000, 450_000)),
        List(12) { 20_000L } + listOf(3_000_000L),
    )
    for (loc in listOf(Locale.US, Locale("in", "ID"))) {
        Locale.setDefault(loc)
        val ins = Insights.build(rep, LocalDate.of(2026, 11, 19), 80)
        println("--- insights (${loc.language}, current month)")
        ins.forEach { println("  $it") }
        check("insights nonempty ${loc.language}", ins.isNotEmpty(), true)
        check("insights max 6", ins.size <= 6, true)
    }
    Locale.setDefault(Locale.US)
    val past = Insights.build(rep, LocalDate.of(2026, 12, 5), 80)
    println("--- insights (past month)"); past.forEach { println("  $it") }
    val empty = ReportData(YearMonth.of(2026, 11), 0, 0, 0, 0, emptyList(), emptyMap(), emptyList(), LongArray(30), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    check("insights empty", Insights.build(empty, LocalDate.of(2026, 11, 19), 80).size, 1)

    // ---- In-app language setting overrides the phone language
    Locale.setDefault(Locale("in", "ID"))
    L.mode = L.EN
    check("lang EN on ID phone", tr("Income", "Pemasukan"), "Income")
    check("lang EN month", D.month(YearMonth.of(2026, 8)), "August 2026")
    check("lang EN compact", Money.compact(1_500_000), "1.5M")
    Locale.setDefault(Locale.US)
    L.mode = L.ID
    check("lang ID on EN phone", tr("Income", "Pemasukan"), "Pemasukan")
    check("lang ID month", D.month(YearMonth.of(2026, 8)), "Agustus 2026")
    check("lang ID compact", Money.compact(1_500_000), "1,5 jt")
    check("lang ID export header", String(Export.csv(emptyList()), Charsets.UTF_8).substring(1, 8), "Tanggal")
    L.mode = L.SYSTEM
    check("lang system (US phone)", tr("Income", "Pemasukan"), "Income")

    // ---- Show/hide amounts
    Privacy.hidden = true
    check("mask fmt", Money.fmt(1_250_000), "Rp••••••")
    check("mask negative hides sign", Money.fmt(-5_000), "Rp••••••")
    check("mask signed", Money.signed(5_000, TxType.INCOME), "+Rp••••••")
    check("mask compact", Money.compact(12_000), "•••")
    check("export never masked", String(Export.csv(rows), Charsets.UTF_8).contains("12500000"), true)
    check("edit field never masked", Money.group(1_250_000), "1.250.000")
    Privacy.hidden = false
    check("unmask", Money.fmt(1_250_000), "Rp1.250.000")

    println("passed=$passes failed=$failures")
    if (failures > 0) System.exit(1)
}
