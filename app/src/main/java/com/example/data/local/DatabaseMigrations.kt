package com.example.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.domain.ThaiTaxRulesCatalog

/**
 * Explicit Room migrations and structural schema defaults.
 * Never uses destructive fallback on user financial records.
 */
object DatabaseMigrations {
    const val CURRENT_SCHEMA_VERSION = 1

    /**
     * Forward-ready migration example verifying schema evolution safety in unit tests.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_taxSection ON transactions(taxSection)")
        }
    }

    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        // Registered automatically as versions advance
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
