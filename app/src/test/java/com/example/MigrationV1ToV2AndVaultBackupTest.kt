package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AccountEntity
import com.example.data.local.AccountType
import com.example.data.local.AppDatabase
import com.example.data.local.DatabaseMigrations
import com.example.data.local.DocumentVerificationStatus
import com.example.data.local.ReviewStatus
import com.example.data.local.TaxDocumentCategory
import com.example.data.local.TaxFilingHistoryEntity
import com.example.data.local.TaxFilingState
import com.example.data.local.TaxFormType
import com.example.data.local.TaxSection
import com.example.data.local.TransactionEntity
import com.example.data.local.TransactionSource
import com.example.data.local.TransactionType
import com.example.data.repository.FinanceRepository
import com.example.domain.BackupCenterManager
import com.example.tax.reconciliation.BankCsvColumnMapping
import com.example.tax.reconciliation.BankMappingPreset
import com.example.tax.reconciliation.BankStatementReconciler
import com.example.tax.vault.TaxDocumentVaultManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationV1ToV2AndVaultBackupTest {

    @After
    fun tearDown() {
        AppDatabase.closeAndClearInstance()
    }

    @Test
    fun `room v1 to v2 migration preserves existing v1 transactions and creates tax filing tables`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "test_v1_to_v2.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        // Create authentic v1 SQLite database containing existing accounts + transactions
        val v1Db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        v1Db.execSQL("CREATE TABLE accounts (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, accountType TEXT NOT NULL, openingBalanceSatang INTEGER NOT NULL, isOwnAccount INTEGER NOT NULL, isBusinessAccount INTEGER NOT NULL, accountNumberHint TEXT NOT NULL, notes TEXT NOT NULL, isActive INTEGER NOT NULL, createdAtEpochMillis INTEGER NOT NULL)")
        v1Db.execSQL("CREATE TABLE categories (id TEXT NOT NULL PRIMARY KEY, nameTh TEXT NOT NULL, nameEn TEXT NOT NULL, type TEXT NOT NULL, iconKey TEXT NOT NULL, colorHex INTEGER NOT NULL, isBusinessCategory INTEGER NOT NULL, defaultTaxSection TEXT NOT NULL, isSystem INTEGER NOT NULL, isActive INTEGER NOT NULL)")
        v1Db.execSQL("CREATE TABLE contacts (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, bankName TEXT NOT NULL, accountHint TEXT NOT NULL, notes TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL)")
        v1Db.execSQL("CREATE TABLE transactions (id TEXT NOT NULL PRIMARY KEY, amountSatang INTEGER NOT NULL, currency TEXT NOT NULL, type TEXT NOT NULL, timestampEpochMillis INTEGER NOT NULL, description TEXT NOT NULL, categoryId TEXT, accountId TEXT NOT NULL, toAccountId TEXT, contactId TEXT, senderName TEXT NOT NULL, recipientName TEXT NOT NULL, paymentReference TEXT NOT NULL, notes TEXT NOT NULL, source TEXT NOT NULL, taxSection TEXT NOT NULL, isBusiness INTEGER NOT NULL, businessCostSatang INTEGER NOT NULL, shippingCostSatang INTEGER NOT NULL, adSpendSatang INTEGER NOT NULL, isCustomerPaid INTEGER NOT NULL, reviewStatus TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL, FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(toAccountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(contactId) REFERENCES contacts(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
        v1Db.execSQL("CREATE INDEX index_transactions_timestampEpochMillis ON transactions(timestampEpochMillis)")
        v1Db.execSQL("CREATE INDEX index_transactions_categoryId ON transactions(categoryId)")
        v1Db.execSQL("CREATE INDEX index_transactions_accountId ON transactions(accountId)")
        v1Db.execSQL("CREATE INDEX index_transactions_toAccountId ON transactions(toAccountId)")
        v1Db.execSQL("CREATE INDEX index_transactions_contactId ON transactions(contactId)")
        v1Db.execSQL("CREATE INDEX index_transactions_type ON transactions(type)")
        v1Db.execSQL("CREATE INDEX index_transactions_reviewStatus ON transactions(reviewStatus)")
        v1Db.execSQL("CREATE TABLE imported_images (id TEXT NOT NULL PRIMARY KEY, sourceUri TEXT NOT NULL, sourceFolderUri TEXT NOT NULL, originalFileName TEXT NOT NULL, mimeType TEXT NOT NULL, fileSizeBytes INTEGER NOT NULL, sourceLastModifiedEpochMillis INTEGER NOT NULL, sha256 TEXT NOT NULL, pHash TEXT NOT NULL, privateFilePath TEXT NOT NULL, importState TEXT NOT NULL, similarToImageId TEXT, errorMessage TEXT NOT NULL, importedAtEpochMillis INTEGER NOT NULL, sourceDeletedAtEpochMillis INTEGER)")
        v1Db.execSQL("CREATE UNIQUE INDEX index_imported_images_sha256 ON imported_images(sha256)")
        v1Db.execSQL("CREATE INDEX index_imported_images_pHash ON imported_images(pHash)")
        v1Db.execSQL("CREATE INDEX index_imported_images_importState ON imported_images(importState)")
        v1Db.execSQL("CREATE INDEX index_imported_images_sourceUri ON imported_images(sourceUri)")
        v1Db.execSQL("CREATE TABLE attachments (id TEXT NOT NULL PRIMARY KEY, transactionId TEXT NOT NULL, importedImageId TEXT, localFilePath TEXT NOT NULL, originalFileName TEXT NOT NULL, mimeType TEXT NOT NULL, fileSizeBytes INTEGER NOT NULL, sha256 TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL, FOREIGN KEY(transactionId) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(importedImageId) REFERENCES imported_images(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
        v1Db.execSQL("CREATE INDEX index_attachments_transactionId ON attachments(transactionId)")
        v1Db.execSQL("CREATE INDEX index_attachments_importedImageId ON attachments(importedImageId)")
        v1Db.execSQL("CREATE TABLE scan_sources (id TEXT NOT NULL PRIMARY KEY, treeUri TEXT NOT NULL, displayName TEXT NOT NULL, lastScanEpochMillis INTEGER, lastSuccessEpochMillis INTEGER, lastStatus TEXT NOT NULL, lastErrorMessage TEXT NOT NULL, scannedCount INTEGER NOT NULL, importedCount INTEGER NOT NULL, permissionValid INTEGER NOT NULL)")
        v1Db.execSQL("CREATE TABLE ocr_results (id TEXT NOT NULL PRIMARY KEY, importedImageId TEXT NOT NULL, rawText TEXT NOT NULL, engineName TEXT NOT NULL, modelVersion TEXT NOT NULL, modelStatus TEXT NOT NULL, detectedBank TEXT NOT NULL, parsedAmountSatang INTEGER, amountConfidence REAL NOT NULL, parsedDateEpochMillis INTEGER, dateConfidence REAL NOT NULL, parsedTimeText TEXT NOT NULL, timeConfidence REAL NOT NULL, parsedSender TEXT NOT NULL, senderConfidence REAL NOT NULL, parsedRecipient TEXT NOT NULL, recipientConfidence REAL NOT NULL, parsedReference TEXT NOT NULL, referenceConfidence REAL NOT NULL, parsedAccountHint TEXT NOT NULL, overallConfidence REAL NOT NULL, needsReview INTEGER NOT NULL, createdAtEpochMillis INTEGER NOT NULL, FOREIGN KEY(importedImageId) REFERENCES imported_images(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        v1Db.execSQL("CREATE UNIQUE INDEX index_ocr_results_importedImageId ON ocr_results(importedImageId)")
        v1Db.execSQL("CREATE TABLE categorization_rules (id TEXT NOT NULL PRIMARY KEY, keywordOrRecipient TEXT NOT NULL, matchField TEXT NOT NULL, categoryId TEXT NOT NULL, transactionType TEXT NOT NULL, taxSection TEXT NOT NULL, isBusiness INTEGER NOT NULL, priority INTEGER NOT NULL, isEnabled INTEGER NOT NULL, FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        v1Db.execSQL("CREATE INDEX index_categorization_rules_categoryId ON categorization_rules(categoryId)")
        v1Db.execSQL("CREATE TABLE budgets (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, categoryId TEXT, yearMonth TEXT NOT NULL, limitSatang INTEGER NOT NULL, alertThresholdPercent INTEGER NOT NULL, isActive INTEGER NOT NULL, FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        v1Db.execSQL("CREATE INDEX index_budgets_categoryId ON budgets(categoryId)")
        v1Db.execSQL("CREATE INDEX index_budgets_yearMonth ON budgets(yearMonth)")
        v1Db.execSQL("CREATE TABLE recurring_transactions (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, amountSatang INTEGER NOT NULL, type TEXT NOT NULL, categoryId TEXT, accountId TEXT NOT NULL, frequency TEXT NOT NULL, nextDueEpochMillis INTEGER NOT NULL, reminderDaysBefore INTEGER NOT NULL, isActive INTEGER NOT NULL, lastProcessedEpochMillis INTEGER, FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        v1Db.execSQL("CREATE INDEX index_recurring_transactions_categoryId ON recurring_transactions(categoryId)")
        v1Db.execSQL("CREATE INDEX index_recurring_transactions_accountId ON recurring_transactions(accountId)")
        v1Db.execSQL("CREATE INDEX index_recurring_transactions_nextDueEpochMillis ON recurring_transactions(nextDueEpochMillis)")
        v1Db.execSQL("CREATE TABLE debts (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, direction TEXT NOT NULL, counterpartyName TEXT NOT NULL, principalSatang INTEGER NOT NULL, remainingSatang INTEGER NOT NULL, dueEpochMillis INTEGER, installmentCount INTEGER NOT NULL, installmentAmountSatang INTEGER NOT NULL, reminderEnabled INTEGER NOT NULL, notes TEXT NOT NULL, status TEXT NOT NULL, attachmentPath TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL)")
        v1Db.execSQL("CREATE TABLE debt_repayments (id TEXT NOT NULL PRIMARY KEY, debtId TEXT NOT NULL, transactionId TEXT, amountSatang INTEGER NOT NULL, paidAtEpochMillis INTEGER NOT NULL, notes TEXT NOT NULL, attachmentPath TEXT NOT NULL, FOREIGN KEY(debtId) REFERENCES debts(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(transactionId) REFERENCES transactions(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
        v1Db.execSQL("CREATE INDEX index_debt_repayments_debtId ON debt_repayments(debtId)")
        v1Db.execSQL("CREATE INDEX index_debt_repayments_transactionId ON debt_repayments(transactionId)")
        v1Db.execSQL("CREATE TABLE tax_years (taxYearBe INTEGER NOT NULL PRIMARY KEY, taxYearCe INTEGER NOT NULL, displayName TEXT NOT NULL, isActive INTEGER NOT NULL)")
        v1Db.execSQL("CREATE TABLE tax_profiles (taxYearBe INTEGER NOT NULL PRIMARY KEY, filingStatus TEXT NOT NULL, spouseNoIncome INTEGER NOT NULL, childCount INTEGER NOT NULL, parentCareCount INTEGER NOT NULL, disabilityCareCount INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL, FOREIGN KEY(taxYearBe) REFERENCES tax_years(taxYearBe) ON UPDATE NO ACTION ON DELETE CASCADE)")
        v1Db.execSQL("CREATE TABLE tax_rules (id TEXT NOT NULL PRIMARY KEY, taxYearBe INTEGER NOT NULL, ruleVersion TEXT NOT NULL, sourceUrl TEXT NOT NULL, bracketsJson TEXT NOT NULL, personalAllowanceSatang INTEGER NOT NULL, spouseAllowanceSatang INTEGER NOT NULL, childAllowanceSatang INTEGER NOT NULL, parentAllowanceSatang INTEGER NOT NULL, disabilityAllowanceSatang INTEGER NOT NULL, section401_402ExpenseRatePercent INTEGER NOT NULL, section401_402ExpenseCapSatang INTEGER NOT NULL, notes TEXT NOT NULL, FOREIGN KEY(taxYearBe) REFERENCES tax_years(taxYearBe) ON UPDATE NO ACTION ON DELETE CASCADE)")
        v1Db.execSQL("CREATE UNIQUE INDEX index_tax_rules_taxYearBe ON tax_rules(taxYearBe)")
        v1Db.execSQL("CREATE TABLE tax_deductions (id TEXT NOT NULL PRIMARY KEY, taxYearBe INTEGER NOT NULL, deductionType TEXT NOT NULL, label TEXT NOT NULL, amountSatang INTEGER NOT NULL, maxAllowedSatang INTEGER NOT NULL, isUserConfirmed INTEGER NOT NULL, notes TEXT NOT NULL)")
        v1Db.execSQL("CREATE INDEX index_tax_deductions_taxYearBe ON tax_deductions(taxYearBe)")
        v1Db.execSQL("CREATE TABLE withholding_records (id TEXT NOT NULL PRIMARY KEY, taxYearBe INTEGER NOT NULL, payerName TEXT NOT NULL, incomeAmountSatang INTEGER NOT NULL, withheldSatang INTEGER NOT NULL, taxSection TEXT NOT NULL, certificateRef TEXT NOT NULL, dateEpochMillis INTEGER NOT NULL)")
        v1Db.execSQL("CREATE INDEX index_withholding_records_taxYearBe ON withholding_records(taxYearBe)")
        v1Db.execSQL("CREATE TABLE app_settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, updatedAtEpochMillis INTEGER NOT NULL)")
        v1Db.execSQL("CREATE TABLE backup_history (id TEXT NOT NULL PRIMARY KEY, fileName TEXT NOT NULL, format TEXT NOT NULL, fileSizeBytes INTEGER NOT NULL, sha256 TEXT NOT NULL, transactionCount INTEGER NOT NULL, imageCount INTEGER NOT NULL, status TEXT NOT NULL, errorMessage TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL)")

        v1Db.execSQL("INSERT INTO accounts VALUES ('a1', 'KBank', 'KBANK', 0, 1, 0, '', '', 1, 1000)")
        v1Db.execSQL("INSERT INTO transactions VALUES ('tx_v1', 500000, 'THB', 'INCOME', 1770000000000, 'Salary v1', NULL, 'a1', NULL, NULL, '', '', '', '', 'MANUAL', 'SEC_40_1', 0, 0, 0, 0, 1, 'VERIFIED', 1000, 1000)")
        v1Db.version = 1
        v1Db.close()

        val migratedDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val preserved = migratedDb.transactionDao().getById("tx_v1")
        assertNotNull(preserved)
        assertEquals(500_000L, preserved!!.amountSatang)
        assertEquals(0, migratedDb.taxFilingDao().getAllDocumentsOnce().size)

        migratedDb.close()
        dbFile.delete()
    }

    @Test
    fun `reconciliation avoids duplicates, never auto-taxes bank deposits, records audits, and marks XML outdated`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val repo = FinanceRepository(context)
        repo.ensureInitialized()

        db.accountDao().upsert(AccountEntity(id = "acc_kbank_main", name = "KBank", accountType = AccountType.KBANK))
        val txMillis = LocalDateTime.of(2026, 6, 10, 10, 0)
            .atZone(ZoneId.of("Asia/Bangkok")).toInstant().toEpochMilli()

        // Existing K PLUS slip matching row 1 of CSV
        repo.saveTransaction(
            TransactionEntity(
                id = "kplus_tx1",
                amountSatang = 1_500_000L,
                type = TransactionType.INCOME,
                timestampEpochMillis = txMillis,
                description = "โอนเข้า K PLUS",
                accountId = "acc_kbank_main",
                paymentReference = "KB20260610",
                source = TransactionSource.K_PLUS_OCR,
                taxSection = TaxSection.SEC_40_2,
                reviewStatus = ReviewStatus.VERIFIED
            )
        )

        val csv = """
            วันที่,รายการ,ถอน,ฝาก,อ้างอิง
            10/06/2569,โอนเข้า K PLUS,,"15,000.00",KB20260610
            11/06/2569,รับโอนจากบัญชีเพื่อน,,"8,500.00",KB20260611
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val mapping = BankCsvColumnMapping.forPreset(BankMappingPreset.KBANK_CSV)
        val outcome1 = BankStatementReconciler.importCsvIntoDatabase(db, csv, "kbank_june.csv", "acc_kbank_main", mapping)
        assertEquals(1, outcome1.duplicateSkippedCount)
        assertEquals(1, outcome1.importedCount)

        // Importing exact same CSV again must skip entire file
        val outcome2 = BankStatementReconciler.importCsvIntoDatabase(db, csv, "kbank_june.csv", "acc_kbank_main", mapping)
        assertTrue(outcome2.alreadyImportedFile)

        // Newly imported deposit MUST NOT be auto-classified as taxable income
        val importedTx = db.transactionDao().getAllOnce().first { it.paymentReference == "KB20260611" }
        assertEquals(TransactionType.OTHER, importedTx.type)
        assertEquals(TaxSection.NONE, importedTx.taxSection)
        assertEquals(ReviewStatus.NEEDS_REVIEW, importedTx.reviewStatus)

        // Save an active XML_GENERATED history entry, then reclassify -> audit recorded & XML marked outdated
        repo.saveFilingHistory(
            TaxFilingHistoryEntity(
                id = "hist_1",
                taxYearBe = 2569,
                formType = TaxFormType.PND90,
                filingVersion = 1,
                totalAssessableSatang = 1_500_000L,
                totalDeductionSatang = 6_000_000L,
                netTaxableSatang = 0L,
                calculatedTaxSatang = 0L,
                withholdingSatang = 0L,
                payableSatang = 0L,
                refundSatang = 0L,
                rulesetVersion = "RD-2569-v1",
                filingState = TaxFilingState.XML_GENERATED
            )
        )

        repo.reclassifyTransactionWithAudit(
            tx = importedTx,
            newType = TransactionType.LOAN_RECEIVED,
            newTaxSection = TaxSection.NON_TAXABLE,
            reason = "เงินยืมเพื่อน ไม่ใช่รายรับภาษี"
        )

        val audits = repo.observeReconciliationAudits(2569).first()
        assertEquals(1, audits.size)
        assertEquals(TransactionType.LOAN_RECEIVED, audits.first().newType)

        val history = repo.observeTaxFilingHistory(2569).first()
        assertTrue(history.first { it.id == "hist_1" }.isOutdated)
    }

    @Test
    fun `tax document vault PDF and filing history survive encrypted efbackup and sqlite full restore`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val repo = FinanceRepository(context)
        repo.ensureInitialized()

        val vault = TaxDocumentVaultManager(context)
        val fakePdfBytes = "%PDF-1.4\n50TaviSampleContent\n%%EOF".toByteArray(Charsets.UTF_8)
        val doc = vault.importFromStream(
            inputStream = ByteArrayInputStream(fakePdfBytes),
            originalFileName = "50tavi_2569.pdf",
            mimeType = "application/pdf",
            taxYearBe = 2569,
            category = TaxDocumentCategory.WITHHOLDING_50_TAVI
        )
        assertEquals(DocumentVerificationStatus.PENDING_REVIEW, doc.verificationStatus)
        repo.saveTaxDocument(doc)
        repo.saveFilingHistory(
            TaxFilingHistoryEntity(
                id = "hist_backup",
                taxYearBe = 2569,
                formType = TaxFormType.PND91,
                filingVersion = 1,
                totalAssessableSatang = 50_000_000L,
                totalDeductionSatang = 16_000_000L,
                netTaxableSatang = 34_000_000L,
                calculatedTaxSatang = 1_150_000L,
                withholdingSatang = 1_000_000L,
                payableSatang = 150_000L,
                refundSatang = 0L,
                rulesetVersion = "RD-2569-v1",
                filingState = TaxFilingState.MANUALLY_SUBMITTED,
                submissionRef = "EF-2569-999"
            )
        )

        val backupManager = BackupCenterManager(context)
        val efOut = ByteArrayOutputStream()
        val exportRes = backupManager.exportEncryptedEfBackup(efOut, "tax_backup.efbackup", "Passw0rd!234")
        assertTrue(exportRes.isSuccess)

        // Wipe vault files & database rows, then run Full Restore
        File(doc.privateFilePath).delete()
        repo.deleteTaxDocument(doc)
        assertFalse(File(doc.privateFilePath).exists())

        val restoreRes = backupManager.restoreEfBackupFullReplace(ByteArrayInputStream(efOut.toByteArray()), "Passw0rd!234")
        assertTrue("Restore failed: ${restoreRes.exceptionOrNull()?.message}", restoreRes.isSuccess)
        assertEquals(1, restoreRes.getOrThrow().taxDocumentCount)

        val restoredDocs = AppDatabase.getInstance(context).taxFilingDao().getAllDocumentsOnce()
        assertEquals(1, restoredDocs.size)
        assertTrue(File(restoredDocs.first().privateFilePath).exists())
        val restoredHistory = AppDatabase.getInstance(context).taxFilingDao().observeAllFilingHistory().first()
        assertTrue(restoredHistory.any { it.submissionRef == "EF-2569-999" })
    }
}
