package com.fintrack.idr.data

import com.fintrack.idr.util.TxType
import com.fintrack.idr.util.tr
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pure-Kotlin exporters (no Android dependencies, unit-tested on the JVM). */
object Export {

    data class MonthSum(val ym: YearMonth, val income: Long, val expense: Long)

    private fun headers() = listOf(
        tr("Date", "Tanggal"), tr("Type", "Jenis"), tr("Category", "Kategori"), tr("Wallet", "Dompet"),
        tr("To wallet", "Ke dompet"), tr("Name", "Nama"), tr("Description", "Keterangan"),
        tr("Income", "Pemasukan"), tr("Expense", "Pengeluaran"), tr("Transfer", "Transfer"),
    )

    // ------------------------------------------------------------------ CSV

    private fun csvCell(s: String): String {
        // Neutralise spreadsheet formula injection, then quote if needed.
        var v = s
        if (v.isNotEmpty() && v[0] in "=+-@\t\r") v = "'$v"
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    fun csv(rows: List<TxRow>): ByteArray {
        val sb = StringBuilder()
        sb.append('﻿') // BOM so Excel opens UTF-8 (emoji, Indonesian text) correctly
        sb.append(headers().joinToString(",") { csvCell(it) }).append("\r\n")
        for (r in rows) {
            val t = r.tx
            val cells = listOf(
                LocalDate.ofEpochDay(t.date).toString(),
                TxType.label(t.type),
                if (t.type == TxType.TRANSFER) "" else r.catLabel,
                r.wallet,
                r.toWallet ?: "",
                t.title,
                t.note,
                if (t.type == TxType.INCOME) t.amount.toString() else "",
                if (t.type == TxType.EXPENSE) t.amount.toString() else "",
                if (t.type == TxType.TRANSFER) t.amount.toString() else "",
            )
            sb.append(cells.joinToString(",") { csvCell(it) }).append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ XLSX (hand-written SpreadsheetML)

    private const val S_HEADER = 1
    private const val S_DATE = 2
    private const val S_MONEY = 3
    private const val S_MONEY_BOLD = 4
    private const val S_PCT = 5
    private const val S_BOLD = 6
    private const val S_TITLE = 7

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> { /* invalid in XML: drop */ }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private class Sheet {
        val sb = StringBuilder()
        var row = 0
        private var cells = StringBuilder()
        private var c = 0

        fun str(s: String, style: Int = 0) = apply {
            if (s.isNotEmpty()) cells.append("<c r=\"${col(c)}${row + 1}\" t=\"inlineStr\"${st(style)}><is><t xml:space=\"preserve\">${esc(s)}</t></is></c>")
            c++
        }
        fun num(v: Long?, style: Int = 0) = apply {
            if (v != null) cells.append("<c r=\"${col(c)}${row + 1}\"${st(style)}><v>$v</v></c>")
            c++
        }
        fun dbl(v: Double, style: Int = 0) = apply {
            cells.append("<c r=\"${col(c)}${row + 1}\"${st(style)}><v>$v</v></c>")
            c++
        }
        fun skip() = apply { c++ }
        fun end() {
            sb.append("<row r=\"${row + 1}\">").append(cells).append("</row>")
            row++; c = 0; cells = StringBuilder()
        }
        private fun st(s: Int) = if (s == 0) "" else " s=\"$s\""
    }

    private fun excelDate(epochDay: Long): Long = epochDay + 25569 // 1970-01-01 is serial 25569

    fun xlsx(rows: List<TxRow>, months: List<MonthSum>, cats: List<CatSum>, periodLabel: String): ByteArray {
        val txSheet = Sheet()
        val h = headers()
        h.forEach { txSheet.str(it, S_HEADER) }; txSheet.end()
        for (r in rows) {
            val t = r.tx
            txSheet.num(excelDate(t.date), S_DATE)
                .str(TxType.label(t.type))
                .str(if (t.type == TxType.TRANSFER) "" else r.catLabel)
                .str(r.wallet)
                .str(r.toWallet ?: "")
                .str(t.title)
                .str(t.note)
                .num(if (t.type == TxType.INCOME) t.amount else null, S_MONEY)
                .num(if (t.type == TxType.EXPENSE) t.amount else null, S_MONEY)
                .num(if (t.type == TxType.TRANSFER) t.amount else null, S_MONEY)
            txSheet.end()
        }
        val lastRow = maxOf(1, txSheet.row)

        val sum = Sheet()
        val inc = months.sumOf { it.income }
        val exp = months.sumOf { it.expense }
        sum.str(tr("Financial report", "Laporan keuangan"), S_TITLE).end()
        sum.str(tr("Period", "Periode"), S_BOLD).str(periodLabel).end()
        sum.end()
        sum.str(tr("Total income", "Total pemasukan"), S_BOLD).num(inc, S_MONEY).end()
        sum.str(tr("Total expense", "Total pengeluaran"), S_BOLD).num(exp, S_MONEY).end()
        sum.str(tr("Net (income - expense)", "Selisih (pemasukan - pengeluaran)"), S_BOLD).num(inc - exp, S_MONEY_BOLD).end()
        if (inc > 0) sum.str(tr("Savings rate", "Rasio tabungan"), S_BOLD).dbl((inc - exp).toDouble() / inc, S_PCT).end()
        sum.end()
        if (months.isNotEmpty()) {
            sum.str(tr("Month", "Bulan"), S_HEADER).str(tr("Income", "Pemasukan"), S_HEADER)
                .str(tr("Expense", "Pengeluaran"), S_HEADER).str(tr("Net", "Selisih"), S_HEADER).end()
            for (m in months) {
                sum.str("%04d-%02d".format(m.ym.year, m.ym.monthValue)).num(m.income, S_MONEY).num(m.expense, S_MONEY)
                    .num(m.income - m.expense, S_MONEY).end()
            }
            sum.end()
        }
        if (cats.isNotEmpty()) {
            val totalCat = cats.sumOf { it.total }
            sum.str(tr("Expense category", "Kategori pengeluaran"), S_HEADER).str(tr("Amount", "Jumlah"), S_HEADER)
                .str(tr("Share", "Porsi"), S_HEADER).str(tr("Transactions", "Transaksi"), S_HEADER).end()
            for (c in cats) {
                sum.str(c.label).num(c.total, S_MONEY)
                    .dbl(if (totalCat > 0) c.total.toDouble() / totalCat else 0.0, S_PCT).num(c.count.toLong()).end()
            }
        }

        val sheet1 = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="15"/><cols><col min="1" max="1" width="13" customWidth="1"/><col min="2" max="2" width="13" customWidth="1"/><col min="3" max="3" width="22" customWidth="1"/><col min="4" max="5" width="18" customWidth="1"/><col min="6" max="6" width="26" customWidth="1"/><col min="7" max="7" width="32" customWidth="1"/><col min="8" max="10" width="16" customWidth="1"/></cols><sheetData>${txSheet.sb}</sheetData><autoFilter ref="A1:J$lastRow"/></worksheet>"""

        val sheet2 = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetFormatPr defaultRowHeight="15"/><cols><col min="1" max="1" width="34" customWidth="1"/><col min="2" max="4" width="18" customWidth="1"/></cols><sheetData>${sum.sb}</sheetData></worksheet>"""

        val sheetName1 = tr("Transactions", "Transaksi")
        val sheetName2 = tr("Summary", "Ringkasan")
        val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><bookViews><workbookView/></bookViews><sheets><sheet name="$sheetName1" sheetId="1" r:id="rId1"/><sheet name="$sheetName2" sheetId="2" r:id="rId2"/></sheets><definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">'$sheetName1'!${'$'}A${'$'}1:${'$'}J${'$'}$lastRow</definedName></definedNames></workbook>"""

        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="&quot;Rp&quot;#,##0;-&quot;Rp&quot;#,##0"/><numFmt numFmtId="165" formatCode="yyyy-mm-dd"/></numFmts><fonts count="3"><font><sz val="11"/><name val="Calibri"/><family val="2"/></font><font><b/><sz val="11"/><name val="Calibri"/><family val="2"/></font><font><b/><sz val="14"/><name val="Calibri"/><family val="2"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFCD03"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="8"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="164" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/><xf numFmtId="10" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""

        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""

        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

        val wbRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, content: String) {
                z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rootRels)
            put("xl/workbook.xml", workbook)
            put("xl/_rels/workbook.xml.rels", wbRels)
            put("xl/styles.xml", styles)
            put("xl/worksheets/sheet1.xml", sheet1)
            put("xl/worksheets/sheet2.xml", sheet2)
        }
        return bos.toByteArray()
    }
}
