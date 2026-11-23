package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val accountType: AccountType,
    val openingBalanceSatang: Long = 0L,
    val isOwnAccount: Boolean = true,
    val isBusinessAccount: Boolean = false,
    val accountNumberHint: String = "",
    val notes: String = "",
    val isActive: Boolean = true,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val nameTh: String,
    val nameEn: String,
    val type: TransactionType,
    val iconKey: String,
    val colorHex: Long,
    val isBusinessCategory: Boolean = false,
    val defaultTaxSection: TaxSection = TaxSection.NONE,
    val isSystem: Boolean = false,
    val isActive: Boolean = true
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val bankName: String = "",
    val accountHint: String = "",
    val notes: String = "",
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["toAccountId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["timestampEpochMillis"]),
        Index(value = ["categoryId"]),
        Index(value = ["accountId"]),
        Index(value = ["toAccountId"]),
        Index(value = ["contactId"]),
        Index(value = ["type"]),
        Index(value = ["reviewStatus"])
    ]
)
data class TransactionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val amountSatang: Long,
    val currency: String = "THB",
    val type: TransactionType,
    val timestampEpochMillis: Long,
    val description: String,
    val categoryId: String? = null,
    val accountId: String,
    val toAccountId: String? = null,
    val contactId: String? = null,
    val senderName: String = "",
    val recipientName: String = "",
    val paymentReference: String = "",
    val notes: String = "",
    val source: TransactionSource = TransactionSource.MANUAL,
    val taxSection: TaxSection = TaxSection.NONE,
    val isBusiness: Boolean = false,
    val businessCostSatang: Long = 0L,
    val shippingCostSatang: Long = 0L,
    val adSpendSatang: Long = 0L,
    val isCustomerPaid: Boolean = true,
    val reviewStatus: ReviewStatus = ReviewStatus.VERIFIED,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "imported_images",
    indices = [
        Index(value = ["sha256"], unique = true),
        Index(value = ["pHash"]),
        Index(value = ["importState"]),
        Index(value = ["sourceUri"])
    ]
)
data class ImportedImageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sourceUri: String,
    val sourceFolderUri: String,
    val originalFileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val sourceLastModifiedEpochMillis: Long,
    val sha256: String,
    val pHash: String = "",
    val privateFilePath: String = "",
    val importState: ImageImportState,
    val similarToImageId: String? = null,
    val errorMessage: String = "",
    val importedAtEpochMillis: Long = System.currentTimeMillis(),
    val sourceDeletedAtEpochMillis: Long? = null
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ImportedImageEntity::class,
            parentColumns = ["id"],
            childColumns = ["importedImageId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["transactionId"]),
        Index(value = ["importedImageId"])
    ]
)
data class AttachmentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val transactionId: String,
    val importedImageId: String? = null,
    val localFilePath: String,
    val originalFileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val sha256: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "scan_sources")
data class ScanSourceEntity(
    @PrimaryKey val id: String = "kplus_primary",
    val treeUri: String,
    val displayName: String,
    val lastScanEpochMillis: Long? = null,
    val lastSuccessEpochMillis: Long? = null,
    val lastStatus: String = "พร้อมสแกน",
    val lastErrorMessage: String = "",
    val scannedCount: Int = 0,
    val importedCount: Int = 0,
    val permissionValid: Boolean = true
)

@Entity(
    tableName = "ocr_results",
    foreignKeys = [
        ForeignKey(
            entity = ImportedImageEntity::class,
            parentColumns = ["id"],
            childColumns = ["importedImageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["importedImageId"], unique = true)]
)
data class OcrResultEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val importedImageId: String,
    val rawText: String,
    val engineName: String,
    val modelVersion: String,
    val modelStatus: OcrModelStatus,
    val detectedBank: String = "",
    val parsedAmountSatang: Long? = null,
    val amountConfidence: Float = 0f,
    val parsedDateEpochMillis: Long? = null,
    val dateConfidence: Float = 0f,
    val parsedTimeText: String = "",
    val timeConfidence: Float = 0f,
    val parsedSender: String = "",
    val senderConfidence: Float = 0f,
    val parsedRecipient: String = "",
    val recipientConfidence: Float = 0f,
    val parsedReference: String = "",
    val referenceConfidence: Float = 0f,
    val parsedAccountHint: String = "",
    val overallConfidence: Float = 0f,
    val needsReview: Boolean = true,
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "categorization_rules",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["categoryId"])]
)
data class CategorizationRuleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val keywordOrRecipient: String,
    val matchField: RuleMatchField = RuleMatchField.ANY,
    val categoryId: String,
    val transactionType: TransactionType = TransactionType.EXPENSE,
    val taxSection: TaxSection = TaxSection.NONE,
    val isBusiness: Boolean = false,
    val priority: Int = 10,
    val isEnabled: Boolean = true
)

@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["categoryId"]), Index(value = ["yearMonth"])]
)
data class BudgetEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val categoryId: String? = null,
    val yearMonth: String, // e.g. "2026-11"
    val limitSatang: Long,
    val alertThresholdPercent: Int = 80,
    val isActive: Boolean = true
)

@Entity(
    tableName = "recurring_transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["categoryId"]), Index(value = ["accountId"]), Index(value = ["nextDueEpochMillis"])]
)
data class RecurringTransactionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val amountSatang: Long,
    val type: TransactionType = TransactionType.EXPENSE,
    val categoryId: String? = null,
    val accountId: String,
    val frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
    val nextDueEpochMillis: Long,
    val reminderDaysBefore: Int = 3,
    val isActive: Boolean = true,
    val lastProcessedEpochMillis: Long? = null
)

@Entity(tableName = "debts")
data class DebtEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val direction: DebtDirection,
    val counterpartyName: String,
    val principalSatang: Long,
    val remainingSatang: Long,
    val dueEpochMillis: Long? = null,
    val installmentCount: Int = 1,
    val installmentAmountSatang: Long = 0L,
    val reminderEnabled: Boolean = true,
    val notes: String = "",
    val status: DebtStatus = DebtStatus.ACTIVE,
    val attachmentPath: String = "",
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "debt_repayments",
    foreignKeys = [
        ForeignKey(
            entity = DebtEntity::class,
            parentColumns = ["id"],
            childColumns = ["debtId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["debtId"]), Index(value = ["transactionId"])]
)
data class DebtRepaymentEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val debtId: String,
    val transactionId: String? = null,
    val amountSatang: Long,
    val paidAtEpochMillis: Long,
    val notes: String = "",
    val attachmentPath: String = ""
)

@Entity(tableName = "tax_years")
data class TaxYearEntity(
    @PrimaryKey val taxYearBe: Int, // e.g. 2569 (2026 CE)
    val taxYearCe: Int,
    val displayName: String,
    val isActive: Boolean = true
)

@Entity(
    tableName = "tax_profiles",
    foreignKeys = [
        ForeignKey(
            entity = TaxYearEntity::class,
            parentColumns = ["taxYearBe"],
            childColumns = ["taxYearBe"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TaxProfileEntity(
    @PrimaryKey val taxYearBe: Int,
    val filingStatus: FilingStatus = FilingStatus.SINGLE,
    val spouseNoIncome: Boolean = false,
    val childCount: Int = 0,
    val parentCareCount: Int = 0,
    val disabilityCareCount: Int = 0,
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "tax_rules",
    foreignKeys = [
        ForeignKey(
            entity = TaxYearEntity::class,
            parentColumns = ["taxYearBe"],
            childColumns = ["taxYearBe"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["taxYearBe"], unique = true)]
)
data class TaxRuleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taxYearBe: Int,
    val ruleVersion: String,
    val sourceUrl: String,
    val bracketsJson: String,
    val personalAllowanceSatang: Long,
    val spouseAllowanceSatang: Long,
    val childAllowanceSatang: Long,
    val parentAllowanceSatang: Long,
    val disabilityAllowanceSatang: Long,
    val section401_402ExpenseRatePercent: Int,
    val section401_402ExpenseCapSatang: Long,
    val notes: String = ""
)

@Entity(
    tableName = "tax_deductions",
    indices = [Index(value = ["taxYearBe"])]
)
data class TaxDeductionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taxYearBe: Int,
    val deductionType: DeductionType,
    val label: String,
    val amountSatang: Long,
    val maxAllowedSatang: Long,
    val isUserConfirmed: Boolean = false,
    val notes: String = ""
)

@Entity(
    tableName = "withholding_records",
    indices = [Index(value = ["taxYearBe"])]
)
data class WithholdingRecordEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taxYearBe: Int,
    val payerName: String,
    val incomeAmountSatang: Long,
    val withheldSatang: Long,
    val taxSection: TaxSection = TaxSection.SEC_40_1,
    val certificateRef: String = "",
    val dateEpochMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAtEpochMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "backup_history")
data class BackupHistoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val format: BackupFormat,
    val fileSizeBytes: Long,
    val sha256: String,
    val transactionCount: Int,
    val imageCount: Int,
    val status: BackupStatus,
    val errorMessage: String = "",
    val createdAtEpochMillis: Long = System.currentTimeMillis()
)
