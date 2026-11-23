package com.example.data.local

import kotlinx.serialization.Serializable

@Serializable
enum class TransactionType(val labelTh: String, val isCashInflow: Boolean, val affectsIncomeExpense: Boolean) {
    INCOME("รายรับ", true, true),
    EXPENSE("รายจ่าย", false, true),
    INTERNAL_TRANSFER("โอนระหว่างบัญชีตนเอง", false, false),
    REFUND("เงินคืน / รีฟันด์", true, false),
    LOAN_RECEIVED("รับเงินกู้ยืม", true, false),
    LOAN_REPAYMENT("ชำระคืนหนี้", false, false),
    DEBT_COLLECTION("รับชำระหนี้", true, false),
    OTHER("อื่นๆ / ยังไม่จำแนก", false, false)
}

@Serializable
enum class TransactionSource(val labelTh: String) {
    MANUAL("บันทึกด้วยตนเอง"),
    K_PLUS_OCR("สแกนจาก K PLUS"),
    BANK_STATEMENT("นำเข้ารายการเดินบัญชี (CSV)"),
    RECURRING("รายการประจำ"),
    DEBT_REPAYMENT("ชำระหนี้")
}

@Serializable
enum class ReviewStatus(val labelTh: String) {
    VERIFIED("ตรวจสอบแล้ว"),
    NEEDS_REVIEW("รอตรวจสอบ"),
    DRAFT("แบบร่าง")
}

@Serializable
enum class AccountType(val labelTh: String) {
    KBANK("บัญชี KBank / K PLUS"),
    CASH("เงินสด"),
    SAVINGS("บัญชีออมทรัพย์"),
    BUSINESS("บัญชีธุรกิจ / ร้านค้า"),
    E_WALLET("กระเป๋าเงินอิเล็กทรอนิกส์"),
    OTHER("บัญชีอื่นๆ")
}

@Serializable
enum class TaxSection(val code: String, val labelTh: String, val isTaxableIncome: Boolean) {
    NONE("ไม่ระบุ", "ยังไม่ระบุประเภทภาษี", false),
    NON_TAXABLE("ยกเว้น", "เงินโอน/กู้ยืม/คืนเงิน (ไม่ถือเป็นเงินได้พึงประเมิน)", false),
    SEC_40_1("40(1)", "มาตรา 40(1) เงินเดือน ค่าจ้าง โบนัส", true),
    SEC_40_2("40(2)", "มาตรา 40(2) รับจ้างทำงาน ค่านายหน้า ฟรีแลนซ์", true),
    SEC_40_3("40(3)", "มาตรา 40(3) ค่าลิขสิทธิ์ สิทธิบัตร", true),
    SEC_40_4("40(4)", "มาตรา 40(4) ดอกเบี้ย เงินปันผล", true),
    SEC_40_5("40(5)", "มาตรา 40(5) ค่าเช่าทรัพย์สิน", true),
    SEC_40_6("40(6)", "มาตรา 40(6) วิชาชีพอิสระ (แพทย์ วิศวกร บัญชี ฯลฯ)", true),
    SEC_40_7("40(7)", "มาตรา 40(7) รับเหมาทั้งค่าแรงและสัมภาระ", true),
    SEC_40_8("40(8)", "มาตรา 40(8) ขายสินค้าออนไลน์ ธุรกิจ พาณิชย์ อื่นๆ", true)
}

@Serializable
enum class ImageImportState(val labelTh: String) {
    PENDING("รอนำเข้า"),
    IMPORTED_VERIFIED("นำเข้าและตรวจสอบแฮชแล้ว"),
    FAILED("นำเข้าล้มเหลว"),
    DUPLICATE("ไฟล์ซ้ำ (SHA-256 ตรงกัน)"),
    SIMILAR_IMAGE("ภาพคล้ายคลึง (pHash) รอตรวจ")
}

@Serializable
enum class OcrModelStatus(val labelTh: String) {
    READY("พร้อมใช้งานออฟไลน์ (PP-OCRv5)"),
    MISSING_MODEL_ASSETS("ยังไม่ได้ติดตั้งไฟล์โมเดล ONNX (ตรวจทานด้วยตนเอง)"),
    INFERENCE_ERROR("ประมวลผล OCR ไม่สำเร็จ")
}

@Serializable
enum class RuleMatchField(val labelTh: String) {
    RECIPIENT("ชื่อผู้รับ"),
    SENDER("ชื่อผู้โอน"),
    DESCRIPTION("รายละเอียด"),
    ANY("ทุกฟิลด์")
}

@Serializable
enum class RecurrenceFrequency(val labelTh: String) {
    DAILY("ทุกวัน"),
    WEEKLY("ทุกสัปดาห์"),
    MONTHLY("ทุกเดือน"),
    YEARLY("ทุกปี")
}

@Serializable
enum class DebtDirection(val labelTh: String) {
    LENT("ให้ผู้อื่นกู้ยืม (ลูกหนี้)"),
    BORROWED("กู้ยืมจากผู้อื่น (เจ้าหนี้)")
}

@Serializable
enum class DebtStatus(val labelTh: String) {
    ACTIVE("กำลังผ่อนชำระ"),
    PAID_OFF("ปิดยอดแล้ว"),
    OVERDUE("เลยกำหนดชำระ")
}

@Serializable
enum class FilingStatus(val labelTh: String) {
    SINGLE("โสด"),
    MARRIED_JOINT("สมรส (ยื่นรวม)"),
    MARRIED_SEPARATE("สมรส (แยกยื่น)")
}

@Serializable
enum class DeductionType(val labelTh: String, val defaultCapSatang: Long) {
    PERSONAL("ค่าลดหย่อนส่วนตัว", 6_000_000L), // 60,000 THB
    SPOUSE("ลดหย่อนคู่สมรส (ไม่มีเงินได้)", 6_000_000L), // 60,000 THB
    CHILD("ลดหย่อนบุตร", 3_000_000L), // 30,000 THB/child
    PARENT("อุปการะเลี้ยงดูบิดามารดา", 3_000_000L), // 30,000 THB/parent max 4
    DISABILITY("อุปการะผู้พิการ/ทุพพลภาพ", 6_000_000L),
    SOCIAL_SECURITY("ประกันสังคม", 900_000L), // 9,000 THB
    LIFE_INSURANCE("เบี้ยประกันชีวิต", 10_000_000L), // 100,000 THB
    HEALTH_INSURANCE("เบี้ยประกันสุขภาพตนเอง", 2_500_000L), // 25,000 THB
    PROVIDENT_FUND("กองทุนสำรองเลี้ยงชีพ / กบข.", 50_000_000L), // 500,000 THB
    RMF("กองทุน RMF", 50_000_000L),
    SSF("กองทุน SSF", 20_000_000L),
    THAI_ESG("กองทุน Thai ESG", 30_000_000L),
    HOME_LOAN_INTEREST("ดอกเบี้ยเงินกู้ที่อยู่อาศัย", 10_000_000L),
    DONATION_GENERAL("เงินบริจาคทั่วไป", 0L),
    DONATION_EDUCATION_2X("เงินบริจาคเพื่อการศึกษา/รพ. (2 เท่า)", 0L)
}

@Serializable
enum class BackupFormat(val extension: String, val labelTh: String) {
    EFBACKUP("efbackup", "ไฟล์กู้คืนเข้ารหัส (.efbackup)"),
    SQLITE("sqlite", "ฐานข้อมูลพกพา (.sqlite)")
}

@Serializable
enum class BackupStatus(val labelTh: String) {
    SUCCESS("สำเร็จ"),
    VERIFIED("ตรวจสอบความสมบูรณ์ผ่าน"),
    FAILED("ล้มเหลว")
}

@Serializable
enum class ThemeMode(val labelTh: String) {
    DARK("ธีมมืดพรีเมียม (แนะนำ)"),
    LIGHT("ธีมสว่าง"),
    SYSTEM("ตามระบบ")
}

@Serializable
enum class DateDisplayFormat(val labelTh: String) {
    THAI_BE("พ.ศ. (เช่น 23 พ.ย. 2569)"),
    GREGORIAN_CE("ค.ศ. (เช่น 23/11/2026)")
}

@Serializable
enum class PeriodFilter(val labelTh: String) {
    TODAY("วันนี้"),
    THIS_WEEK("สัปดาห์นี้"),
    THIS_MONTH("เดือนนี้"),
    THIS_YEAR("ปีนี้"),
    CUSTOM("กำหนดเอง")
}
