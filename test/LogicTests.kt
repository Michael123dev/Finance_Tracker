import com.fintrack.idr.data.BudgetLine
import com.fintrack.idr.data.CatSum
import com.fintrack.idr.data.Category
import com.fintrack.idr.data.Export
import com.fintrack.idr.data.Freq
import com.fintrack.idr.data.Insights
import com.fintrack.idr.data.ReceiptDraft
import com.fintrack.idr.data.ReceiptImage
import com.fintrack.idr.data.ReceiptMath
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


    // ---- Receipt images (1.1.1)
    run {
        val M = ReceiptMath
        check("fit 12MP photo", M.fitted(4000, 3000), Pair(1632, 1224))
        check("fit within 2MP", M.fitted(4000, 3000).let { it.first.toLong() * it.second <= M.MAX_PIXELS }, true)
        check("fit keeps aspect", M.fitted(3000, 4000), Pair(1224, 1632))
        check("fit long receipt by long side", M.fitted(1000, 6000), Pair(400, 2400))
        check("fit small untouched", M.fitted(800, 600), Pair(800, 600))
        check("sample 4000->1632", M.sampleSize(4000, 3000, 1632, 1224), 2)
        check("sample 12000->1632", M.sampleSize(12000, 9000, 1632, 1224), 4)
        check("sample never below target", M.sampleSize(4000, 3000, 1632, 1224).let { 4000 / it >= 1632 && 3000 / it >= 1224 }, true)
        check("sample small", M.sampleSize(800, 600, 800, 600), 1)
        check("keep small upright jpeg", M.keepOriginal(150_000, "image/jpeg", 1200, 900, 0), true)
        check("keep small png", M.keepOriginal(90_000, "image/png", 1080, 1920, 0), true)
        check("keep small webp", M.keepOriginal(90_000, "image/webp", 1080, 1920, 0), true)
        check("compress when over limit", M.keepOriginal(200_001, "image/jpeg", 1200, 900, 0), false)
        check("limit is inclusive", M.keepOriginal(200_000, "image/jpeg", 1200, 900, 0), true)
        check("compress when rotated", M.keepOriginal(150_000, "image/jpeg", 1200, 900, 90), false)
        check("keep small file even if many pixels", M.keepOriginal(150_000, "image/png", 4000, 3000, 0), true)
        check("convert other formats", M.keepOriginal(150_000, "image/heif", 1200, 900, 0), false)
        check("convert unknown", M.keepOriginal(150_000, null, 1200, 900, 0), false)
        check("exif 6 -> 90", M.rotationFor(6), 90)
        check("exif 3 -> 180", M.rotationFor(3), 180)
        check("exif 8 -> 270", M.rotationFor(8), 270)
        check("exif 1 -> 0", M.rotationFor(1), 0)

        // Compression loop against a size model: bytes ~ pixels x bits-per-pixel(quality).
        fun model(bitsPerPixel: Double) = { w: Int, h: Int, q: Int -> (w.toLong() * h * bitsPerPixel / 8 * q / 85.0).toInt() }
        val easy = M.compress(1632, 1224, model(0.6), { it })      // a plain receipt: first try fits
        check("easy fits first try", easy.attempts, 1)
        check("easy keeps size", Pair(easy.width, easy.height), Pair(1632, 1224))
        val hard = M.compress(1632, 1224, model(3.0), { it })      // a noisy photo: needs quality and size steps
        check("hard ends within limit", hard.result <= M.LIMIT, true)
        check("hard keeps aspect", Math.abs(hard.width.toDouble() / hard.height - 1632.0 / 1224) < 0.01, true)
        check("hard tries quality before size", hard.quality < 85, true)
        println("  receipt model: noisy 2MP photo -> ${hard.width}x${hard.height} q${hard.quality}, ${hard.result} bytes, ${hard.attempts} encodes")
        val awful = M.compress(2400, 400, model(60.0), { it })     // pathological: still terminates within limit
        check("awful ends within limit", awful.result <= M.LIMIT, true)
        val seen = ArrayList<Int>()
        M.compress(1000, 1000, { _, _, q -> seen += q; if (seen.size < 6) 10_000_000 else 1 }, { it })
        check("quality order then shrink", seen, listOf(85, 75, 65, 55, 85, 75))

        // Base64 storage
        val bytes = ByteArray(200_000) { (it * 31 % 256).toByte() }
        val img = ReceiptImage(bytes, "image/jpeg", 10, 10)
        val text = img.base64()
        check("base64 no line breaks", text.contains('\n'), false)
        check("base64 size ~4/3", text.length, 266_668)
        check("base64 roundtrip", ReceiptImage.fromBase64(text, "image/jpeg", 10, 10)?.bytes?.contentEquals(bytes), true)
        check("base64 invalid -> null", ReceiptImage.fromBase64("not*base64!", "image/jpeg", 1, 1), null)
        check("base64 empty -> null", ReceiptImage.fromBase64("", "image/jpeg", 1, 1), null)
        check("base64 null -> null", ReceiptImage.fromBase64(null, "image/jpeg", 1, 1), null)

        // Edit flow
        val mark = "db"
        val pic = ReceiptImage(byteArrayOf(1, 2, 3), "image/jpeg", 1, 1)
        val keep = ReceiptDraft(stored = true)
        check("edit other fields keeps receipt", keep.action(), ReceiptDraft.Action.Keep)
        check("edit other fields keeps marker", keep.marker(mark, mark), mark)
        check("existing receipt visible", keep.visible, true)
        val rem = ReceiptDraft(stored = true).apply { remove() }
        check("remove -> delete", rem.action(), ReceiptDraft.Action.Delete)
        check("remove clears marker", rem.marker(mark, mark), null)
        check("removed not visible", rem.visible, false)
        val repl = ReceiptDraft(stored = true).apply { replace(pic) }
        check("replace -> put", (repl.action() as? ReceiptDraft.Action.Put)?.image === pic, true)
        check("replace marker", repl.marker(mark, mark), mark)
        val remRepl = ReceiptDraft(stored = true).apply { remove(); replace(pic) }
        check("remove then replace -> put", remRepl.action() is ReceiptDraft.Action.Put, true)
        val newTx = ReceiptDraft(stored = false).apply { replace(pic) }
        check("new tx with photo -> put", newTx.action() is ReceiptDraft.Action.Put, true)
        val newNone = ReceiptDraft(stored = false).apply { replace(pic); remove() }
        check("new tx pick then remove -> keep", newNone.action(), ReceiptDraft.Action.Keep)
        check("new tx pick then remove -> no marker", newNone.marker(null, mark), null)
        check("new tx nothing -> no marker", ReceiptDraft(stored = false).marker(null, mark), null)
    }

    println("passed=$passes failed=$failures")
    if (failures > 0) System.exit(1)
}
