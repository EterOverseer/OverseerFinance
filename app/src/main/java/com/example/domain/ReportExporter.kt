package com.example.domain

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.example.data.local.AccountEntity
import com.example.data.local.CategoryEntity
import com.example.data.local.DateDisplayFormat
import com.example.data.local.DebtEntity
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionType
import java.io.OutputStream

enum class ReportType(val labelTh: String) {
    DAILY("รายงานรายวัน"),
    WEEKLY("รายงานรายสัปดาห์"),
    MONTHLY("รายงานรายเดือน"),
    ANNUAL("รายงานประจำปี"),
    INCOME("รายงานรายรับ"),
    EXPENSE("รายงานรายจ่าย"),
    TAX("รายงานสรุปภาษี"),
    BUSINESS("รายงานบัญชีธุรกิจ"),
    DEBT("รายงานหนี้สินและการผ่อนชำระ")
}

object ReportExporter {

    fun writeTransactionsCsv(
        outputStream: OutputStream,
        transactions: List<TransactionEntity>,
        accounts: Map<String, AccountEntity>,
        categories: Map<String, CategoryEntity>,
        dateFormat: DateDisplayFormat
    ) {
        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            // UTF-8 BOM for clean Thai display in Excel
            writer.write("\uFEFF")
            writer.appendLine("ID,วันที่เวลา,ประเภท,จำนวนเงิน(THB),หมวดหมู่,บัญชี,ผู้โอน,ผู้รับ,เลขอ้างอิง,รายละเอียด,มาตราภาษี,ธุรกิจ,สถานะตรวจสอบ")
            for (tx in transactions) {
                val dateStr = ThaiDateFormatter.formatDateTime(tx.timestampEpochMillis, dateFormat)
                val amtStr = MoneyFormatter.satangToInputString(tx.amountSatang)
                val catName = categories[tx.categoryId]?.nameTh ?: "-"
                val accName = accounts[tx.accountId]?.name ?: "-"
                val row = listOf(
                    tx.id,
                    dateStr,
                    tx.type.labelTh,
                    amtStr,
                    catName,
                    accName,
                    tx.senderName,
                    tx.recipientName,
                    tx.paymentReference,
                    tx.description,
                    tx.taxSection.code,
                    if (tx.isBusiness) "ธุรกิจ" else "ส่วนตัว",
                    tx.reviewStatus.labelTh
                ).joinToString(",") { csvEscape(it) }
                writer.appendLine(row)
            }
        }
    }

    fun writePdfReport(
        context: Context,
        outputStream: OutputStream,
        reportType: ReportType,
        periodLabel: String,
        transactions: List<TransactionEntity>,
        debts: List<DebtEntity>,
        taxReport: TaxCalculationReport?,
        accounts: Map<String, AccountEntity>,
        categories: Map<String, CategoryEntity>,
        dateFormat: DateDisplayFormat
    ) {
        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40f

        val titlePaint = Paint().apply {
            color = Color.parseColor("#0B1320")
            textSize = 18f
            isFakeBoldText = true
        }
        val subtitlePaint = Paint().apply {
            color = Color.parseColor("#334155")
            textSize = 11f
        }
        val headerPaint = Paint().apply {
            color = Color.parseColor("#0F766E")
            textSize = 12f
            isFakeBoldText = true
        }
        val bodyPaint = Paint().apply {
            color = Color.parseColor("#1E293B")
            textSize = 10f
        }
        val linePaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 1f
        }

        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
        var canvas = page.canvas
        var y = 48f

        fun newPageIfNeeded(neededHeight: Float) {
            if (y + neededHeight > pageHeight - 44f) {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create())
                canvas = page.canvas
                y = 48f
            }
        }

        canvas.drawText("OverseerFinance - ${reportType.labelTh}", margin, y, titlePaint)
        y += 20f
        canvas.drawText("ช่วงข้อมูล: $periodLabel | สกุลเงิน: THB (Asia/Bangkok)", margin, y, subtitlePaint)
        y += 14f
        canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
        y += 20f

        when (reportType) {
            ReportType.TAX -> {
                if (taxReport != null) {
                    canvas.drawText("สรุปภาษีเงินได้บุคคลธรรมดา ปี ${taxReport.taxYearBe}", margin, y, headerPaint)
                    y += 18f
                    val taxLines = listOf(
                        "เงินได้พึงประเมินรวม: ${MoneyFormatter.formatThb(taxReport.totalAssessableIncomeSatang)}",
                        "รายการยกเว้น/เงินโอนที่คัดออก: ${MoneyFormatter.formatThb(taxReport.nonTaxableExcludedSatang)}",
                        "หักค่าใช้จ่าย: ${MoneyFormatter.formatThb(taxReport.standardExpenseDeductionSatang)}",
                        "หักค่าลดหย่อนส่วนตัวและครอบครัว: ${MoneyFormatter.formatThb(taxReport.personalAndFamilyAllowanceSatang)}",
                        "หักค่าลดหย่อนเพิ่มเติมที่ยืนยันแล้ว: ${MoneyFormatter.formatThb(taxReport.userConfirmedDeductionsSatang)}",
                        "เงินได้สุทธิ (Net Taxable Income): ${MoneyFormatter.formatThb(taxReport.netTaxableIncomeSatang)}",
                        "ภาษีคำนวณขั้นบันได: ${MoneyFormatter.formatThb(taxReport.progressiveTaxSatang)}",
                        "ภาษีหัก ณ ที่จ่าย: ${MoneyFormatter.formatThb(taxReport.totalWithholdingTaxSatang)}",
                        if (taxReport.netTaxPayableSatang > 0)
                            "ประมาณการภาษีที่ต้องชำระเพิ่ม: ${MoneyFormatter.formatThb(taxReport.netTaxPayableSatang)}"
                        else
                            "ประมาณการภาษีขอคืนได้: ${MoneyFormatter.formatThb(taxReport.netTaxRefundSatang)}"
                    )
                    for (line in taxLines) {
                        newPageIfNeeded(16f)
                        canvas.drawText(line, margin, y, bodyPaint)
                        y += 16f
                    }
                    y += 10f
                    canvas.drawText("รายละเอียดขั้นบันได (${taxReport.sourceUrl})", margin, y, headerPaint)
                    y += 16f
                    for (b in taxReport.bracketBreakdown) {
                        newPageIfNeeded(15f)
                        canvas.drawText(
                            "${b.label} (${b.ratePercent}%): ฐาน ${MoneyFormatter.formatThb(b.taxableInBracketSatang)} -> ภาษี ${MoneyFormatter.formatThb(b.taxAmountSatang)}",
                            margin,
                            y,
                            bodyPaint
                        )
                        y += 15f
                    }
                }
            }
            ReportType.DEBT -> {
                canvas.drawText("รายการหนี้สินและการผ่อนชำระ (${debts.size} รายการ)", margin, y, headerPaint)
                y += 18f
                for (debt in debts) {
                    newPageIfNeeded(32f)
                    canvas.drawText(
                        "${debt.title} (${debt.direction.labelTh}) - ${debt.counterpartyName}",
                        margin,
                        y,
                        bodyPaint
                    )
                    y += 14f
                    canvas.drawText(
                        "   ยอดต้น ${MoneyFormatter.formatThb(debt.principalSatang)} | คงเหลือ ${MoneyFormatter.formatThb(debt.remainingSatang)} | สถานะ: ${debt.status.labelTh}",
                        margin,
                        y,
                        subtitlePaint
                    )
                    y += 18f
                }
            }
            else -> {
                val filtered = when (reportType) {
                    ReportType.INCOME -> transactions.filter { it.type == TransactionType.INCOME }
                    ReportType.EXPENSE -> transactions.filter { it.type == TransactionType.EXPENSE }
                    ReportType.BUSINESS -> transactions.filter { it.isBusiness }
                    else -> transactions
                }
                val totalIn = filtered.filter { it.type == TransactionType.INCOME }.sumOf { it.amountSatang }
                val totalOut = filtered.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountSatang }
                val transferSum = filtered.filter { it.type == TransactionType.INTERNAL_TRANSFER }.sumOf { it.amountSatang }

                canvas.drawText(
                    "รายรับรวม: ${MoneyFormatter.formatThb(totalIn)} | รายจ่ายรวม: ${MoneyFormatter.formatThb(totalOut)}",
                    margin,
                    y,
                    headerPaint
                )
                y += 16f
                canvas.drawText(
                    "สุทธิ: ${MoneyFormatter.formatThb(totalIn - totalOut, showSign = true)} (ไม่นับรวมเงินโอนระหว่างบัญชี ${MoneyFormatter.formatThb(transferSum)})",
                    margin,
                    y,
                    subtitlePaint
                )
                y += 22f

                for (tx in filtered) {
                    newPageIfNeeded(28f)
                    val dt = ThaiDateFormatter.formatDate(tx.timestampEpochMillis, dateFormat)
                    val cat = categories[tx.categoryId]?.nameTh ?: tx.type.labelTh
                    val acc = accounts[tx.accountId]?.name ?: "-"
                    val line1 = "$dt | ${tx.description.take(36)} | ${MoneyFormatter.formatThb(tx.amountSatang)}"
                    val line2 = "   $cat • $acc • ${tx.type.labelTh}"
                    canvas.drawText(line1, margin, y, bodyPaint)
                    y += 13f
                    canvas.drawText(line2, margin, y, subtitlePaint)
                    y += 16f
                }
            }
        }

        doc.finishPage(page)
        outputStream.use { doc.writeTo(it) }
        doc.close()
    }

    private fun csvEscape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
