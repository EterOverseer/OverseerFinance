package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class OverseerConverters {
    @TypeConverter fun fromTransactionType(v: TransactionType): String = v.name
    @TypeConverter fun toTransactionType(v: String): TransactionType = TransactionType.valueOf(v)

    @TypeConverter fun fromTransactionSource(v: TransactionSource): String = v.name
    @TypeConverter fun toTransactionSource(v: String): TransactionSource = TransactionSource.valueOf(v)

    @TypeConverter fun fromReviewStatus(v: ReviewStatus): String = v.name
    @TypeConverter fun toReviewStatus(v: String): ReviewStatus = ReviewStatus.valueOf(v)

    @TypeConverter fun fromAccountType(v: AccountType): String = v.name
    @TypeConverter fun toAccountType(v: String): AccountType = AccountType.valueOf(v)

    @TypeConverter fun fromTaxSection(v: TaxSection): String = v.name
    @TypeConverter fun toTaxSection(v: String): TaxSection = TaxSection.valueOf(v)

    @TypeConverter fun fromImageImportState(v: ImageImportState): String = v.name
    @TypeConverter fun toImageImportState(v: String): ImageImportState = ImageImportState.valueOf(v)

    @TypeConverter fun fromOcrModelStatus(v: OcrModelStatus): String = v.name
    @TypeConverter fun toOcrModelStatus(v: String): OcrModelStatus = OcrModelStatus.valueOf(v)

    @TypeConverter fun fromRuleMatchField(v: RuleMatchField): String = v.name
    @TypeConverter fun toRuleMatchField(v: String): RuleMatchField = RuleMatchField.valueOf(v)

    @TypeConverter fun fromRecurrenceFrequency(v: RecurrenceFrequency): String = v.name
    @TypeConverter fun toRecurrenceFrequency(v: String): RecurrenceFrequency = RecurrenceFrequency.valueOf(v)

    @TypeConverter fun fromDebtDirection(v: DebtDirection): String = v.name
    @TypeConverter fun toDebtDirection(v: String): DebtDirection = DebtDirection.valueOf(v)

    @TypeConverter fun fromDebtStatus(v: DebtStatus): String = v.name
    @TypeConverter fun toDebtStatus(v: String): DebtStatus = DebtStatus.valueOf(v)

    @TypeConverter fun fromFilingStatus(v: FilingStatus): String = v.name
    @TypeConverter fun toFilingStatus(v: String): FilingStatus = FilingStatus.valueOf(v)

    @TypeConverter fun fromDeductionType(v: DeductionType): String = v.name
    @TypeConverter fun toDeductionType(v: String): DeductionType = DeductionType.valueOf(v)

    @TypeConverter fun fromBackupFormat(v: BackupFormat): String = v.name
    @TypeConverter fun toBackupFormat(v: String): BackupFormat = BackupFormat.valueOf(v)

    @TypeConverter fun fromBackupStatus(v: BackupStatus): String = v.name
    @TypeConverter fun toBackupStatus(v: String): BackupStatus = BackupStatus.valueOf(v)
}

@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        ContactEntity::class,
        TransactionEntity::class,
        ImportedImageEntity::class,
        AttachmentEntity::class,
        ScanSourceEntity::class,
        OcrResultEntity::class,
        CategorizationRuleEntity::class,
        BudgetEntity::class,
        RecurringTransactionEntity::class,
        DebtEntity::class,
        DebtRepaymentEntity::class,
        TaxYearEntity::class,
        TaxProfileEntity::class,
        TaxRuleEntity::class,
        TaxDeductionEntity::class,
        WithholdingRecordEntity::class,
        AppSettingEntity::class,
        BackupHistoryEntity::class
    ],
    version = DatabaseMigrations.CURRENT_SCHEMA_VERSION,
    exportSchema = false
)
@TypeConverters(OverseerConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun contactDao(): ContactDao
    abstract fun transactionDao(): TransactionDao
    abstract fun importedImageDao(): ImportedImageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun scanSourceDao(): ScanSourceDao
    abstract fun ocrResultDao(): OcrResultDao
    abstract fun categorizationRuleDao(): CategorizationRuleDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringDao(): RecurringTransactionDao
    abstract fun debtDao(): DebtDao
    abstract fun taxDao(): TaxDao
    abstract fun appSettingDao(): AppSettingDao
    abstract fun backupHistoryDao(): BackupHistoryDao

    companion object {
        const val DB_NAME = "overseer_finance.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME
            )
                .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
                .build()
        }

        fun closeAndClearInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
