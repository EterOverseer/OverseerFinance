package com.example.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.domain.ThaiTaxRulesCatalog

/**
 * Explicit Room migrations and structural schema defaults.
 * Never uses destructive fallback on user financial records.
 */
object DatabaseMigrations {
    const val CURRENT_SCHEMA_VERSION = 2

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_taxSection ON transactions(taxSection)")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS taxpayer_identities (
                    taxYearBe INTEGER NOT NULL PRIMARY KEY,
                    tin13 TEXT NOT NULL,
                    titleTh TEXT NOT NULL,
                    firstNameTh TEXT NOT NULL,
                    lastNameTh TEXT NOT NULL,
                    birthDateIso TEXT NOT NULL,
                    spouseTin13 TEXT NOT NULL,
                    spouseFirstNameTh TEXT NOT NULL,
                    spouseLastNameTh TEXT NOT NULL,
                    addressLine TEXT NOT NULL,
                    subDistrict TEXT NOT NULL,
                    district TEXT NOT NULL,
                    province TEXT NOT NULL,
                    postalCode TEXT NOT NULL,
                    phone TEXT NOT NULL,
                    email TEXT NOT NULL,
                    updatedAtEpochMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS statement_import_batches (
                    id TEXT NOT NULL PRIMARY KEY,
                    bankName TEXT NOT NULL,
                    fileName TEXT NOT NULL,
                    sha256 TEXT NOT NULL,
                    rowCount INTEGER NOT NULL,
                    importedCount INTEGER NOT NULL,
                    duplicateCount INTEGER NOT NULL,
                    importedAtEpochMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_statement_import_batches_sha256 ON statement_import_batches(sha256)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS reconciliation_audits (
                    id TEXT NOT NULL PRIMARY KEY,
                    taxYearBe INTEGER NOT NULL,
                    transactionId TEXT NOT NULL,
                    previousType TEXT NOT NULL,
                    newType TEXT NOT NULL,
                    previousTaxSection TEXT NOT NULL,
                    newTaxSection TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    adjustedAtEpochMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_reconciliation_audits_taxYearBe ON reconciliation_audits(taxYearBe)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_reconciliation_audits_transactionId ON reconciliation_audits(transactionId)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tax_documents (
                    id TEXT NOT NULL PRIMARY KEY,
                    taxYearBe INTEGER NOT NULL,
                    category TEXT NOT NULL,
                    title TEXT NOT NULL,
                    mimeType TEXT NOT NULL,
                    privateFilePath TEXT NOT NULL,
                    sha256 TEXT NOT NULL,
                    fileSizeBytes INTEGER NOT NULL,
                    linkedTransactionId TEXT,
                    linkedDeductionId TEXT,
                    ocrExtractedText TEXT NOT NULL,
                    extractedAmountSatang INTEGER,
                    notes TEXT NOT NULL,
                    verificationStatus TEXT NOT NULL,
                    createdAtEpochMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_tax_documents_taxYearBe ON tax_documents(taxYearBe)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_tax_documents_sha256 ON tax_documents(sha256)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tax_filing_history (
                    id TEXT NOT NULL PRIMARY KEY,
                    taxYearBe INTEGER NOT NULL,
                    formType TEXT NOT NULL,
                    filingVersion INTEGER NOT NULL,
                    xmlFileName TEXT NOT NULL,
                    xmlSha256 TEXT NOT NULL,
                    generatedAtEpochMillis INTEGER NOT NULL,
                    totalAssessableSatang INTEGER NOT NULL,
                    totalDeductionSatang INTEGER NOT NULL,
                    netTaxableSatang INTEGER NOT NULL,
                    calculatedTaxSatang INTEGER NOT NULL,
                    withholdingSatang INTEGER NOT NULL,
                    payableSatang INTEGER NOT NULL,
                    refundSatang INTEGER NOT NULL,
                    rulesetVersion TEXT NOT NULL,
                    filingState TEXT NOT NULL,
                    submissionRef TEXT NOT NULL,
                    confirmationRef TEXT NOT NULL,
                    associatedDocumentIdsJson TEXT NOT NULL,
                    isOutdated INTEGER NOT NULL,
                    lastDataMutationEpochMillis INTEGER NOT NULL,
                    notes TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_tax_filing_history_taxYearBe ON tax_filing_history(taxYearBe)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_tax_filing_history_filingVersion ON tax_filing_history(filingVersion)"
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS open_api_settings (
                    id TEXT NOT NULL PRIMARY KEY,
                    isEnabled INTEGER NOT NULL,
                    providerModel TEXT NOT NULL,
                    environment TEXT NOT NULL,
                    clientId TEXT NOT NULL,
                    baseUrl TEXT NOT NULL,
                    userConsented INTEGER NOT NULL,
                    lastAuthEpochMillis INTEGER,
                    updatedAtEpochMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_1_2
    )
}

object DatabaseSeedCatalog {
    fun defaultAccounts(): List<AccountEntity> = listOf(
        AccountEntity(
            id = "acc_kbank_main",
            name = "KBank (K PLUS)",
            accountType = AccountType.KBANK,
            openingBalanceSatang = 0L,
            isOwnAccount = true,
            isBusinessAccount = false,
            notes = "บัญชีหลักสำหรับสแกนสลิป K PLUS"
        ),
        AccountEntity(
            id = "acc_cash",
            name = "เงินสด",
            accountType = AccountType.CASH,
            openingBalanceSatang = 0L,
            isOwnAccount = true,
            isBusinessAccount = false,
            notes = "กระเป๋าเงินสด"
        )
    )

    fun defaultCategories(): List<CategoryEntity> = listOf(
        CategoryEntity("cat_salary", "เงินเดือน", "Salary", TransactionType.INCOME, "work", 0xFF34D399, false, TaxSection.SEC_40_1, true),
        CategoryEntity("cat_online_sales", "ยอดขายออนไลน์", "Online Sales", TransactionType.INCOME, "storefront", 0xFF10B981, true, TaxSection.SEC_40_8, true),
        CategoryEntity("cat_freelance", "ฟรีแลนซ์ / รับจ้าง", "Freelance", TransactionType.INCOME, "laptop", 0xFF38BDF8, false, TaxSection.SEC_40_2, true),
        CategoryEntity("cat_biz_income", "รายได้ธุรกิจ", "Business Income", TransactionType.INCOME, "business", 0xFF2DD4BF, true, TaxSection.SEC_40_8, true),
        CategoryEntity("cat_other_income", "รายรับอื่นๆ", "Other Income", TransactionType.INCOME, "payments", 0xFF6EE7B7, false, TaxSection.NONE, true),

        CategoryEntity("cat_food", "อาหารและเครื่องดื่ม", "Food", TransactionType.EXPENSE, "restaurant", 0xFFFB7185, false, TaxSection.NONE, true),
        CategoryEntity("cat_transport", "เดินทาง / น้ำมัน", "Transportation", TransactionType.EXPENSE, "directions_car", 0xFFFBBF24, false, TaxSection.NONE, true),
        CategoryEntity("cat_shopping", "ช้อปปิ้ง / ของใช้", "Shopping", TransactionType.EXPENSE, "shopping_bag", 0xFFC084FC, false, TaxSection.NONE, true),
        CategoryEntity("cat_utilities", "ค่าน้ำ ค่าไฟ เน็ต", "Utilities", TransactionType.EXPENSE, "bolt", 0xFF60A5FA, false, TaxSection.NONE, true),
        CategoryEntity("cat_rent", "ค่าเช่า / ที่พัก", "Rent", TransactionType.EXPENSE, "home", 0xFFF472B6, false, TaxSection.NONE, true),
        CategoryEntity("cat_entertainment", "บันเทิง / สมาชิก", "Entertainment", TransactionType.EXPENSE, "movie", 0xFFA78BFA, false, TaxSection.NONE, true),
        CategoryEntity("cat_biz_expense", "ต้นทุนและค่าใช้จ่ายธุรกิจ", "Business Expenses", TransactionType.EXPENSE, "inventory_2", 0xFFF59E0B, true, TaxSection.NONE, true),
        CategoryEntity("cat_other_expense", "รายจ่ายอื่นๆ", "Other Expenses", TransactionType.EXPENSE, "receipt_long", 0xFF94A3B8, false, TaxSection.NONE, true)
    )

    fun defaultTaxYears(): List<TaxYearEntity> = listOf(
        TaxYearEntity(2569, 2026, "ปีภาษี 2569 (2026)", true),
        TaxYearEntity(2568, 2025, "ปีภาษี 2568 (2025)", true),
        TaxYearEntity(2567, 2024, "ปีภาษี 2567 (2024)", true)
    )

    fun defaultTaxRules(): List<TaxRuleEntity> = listOf(
        ThaiTaxRulesCatalog.createRuleEntityForYear(2569),
        ThaiTaxRulesCatalog.createRuleEntityForYear(2568),
        ThaiTaxRulesCatalog.createRuleEntityForYear(2567)
    )
}
