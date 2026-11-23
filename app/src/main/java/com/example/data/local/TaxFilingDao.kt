package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaxFilingDao {
    @Query("SELECT * FROM taxpayer_identities WHERE taxYearBe = :yearBe LIMIT 1")
    fun observeIdentity(yearBe: Int): Flow<TaxpayerIdentityEntity?>

    @Query("SELECT * FROM taxpayer_identities WHERE taxYearBe = :yearBe LIMIT 1")
    suspend fun getIdentityOnce(yearBe: Int): TaxpayerIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIdentity(identity: TaxpayerIdentityEntity)

    @Query("SELECT * FROM statement_import_batches ORDER BY importedAtEpochMillis DESC")
    fun observeImportBatches(): Flow<List<StatementImportBatchEntity>>

    @Query("SELECT * FROM statement_import_batches WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findBatchBySha256(sha256: String): StatementImportBatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImportBatch(batch: StatementImportBatchEntity)

    @Query("SELECT * FROM reconciliation_audits WHERE taxYearBe = :yearBe ORDER BY adjustedAtEpochMillis DESC")
    fun observeAudits(yearBe: Int): Flow<List<ReconciliationAuditEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudit(audit: ReconciliationAuditEntity)

    @Query("SELECT * FROM tax_documents WHERE taxYearBe = :yearBe ORDER BY createdAtEpochMillis DESC")
    fun observeDocuments(yearBe: Int): Flow<List<TaxDocumentEntity>>

    @Query("SELECT * FROM tax_documents")
    suspend fun getAllDocumentsOnce(): List<TaxDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(doc: TaxDocumentEntity)

    @Delete
    suspend fun deleteDocument(doc: TaxDocumentEntity)

    @Query("SELECT * FROM tax_filing_history WHERE taxYearBe = :yearBe ORDER BY filingVersion DESC, generatedAtEpochMillis DESC")
    fun observeFilingHistory(yearBe: Int): Flow<List<TaxFilingHistoryEntity>>

    @Query("SELECT * FROM tax_filing_history ORDER BY generatedAtEpochMillis DESC")
    fun observeAllFilingHistory(): Flow<List<TaxFilingHistoryEntity>>

    @Query("SELECT MAX(filingVersion) FROM tax_filing_history WHERE taxYearBe = :yearBe AND formType = :formType")
    suspend fun getMaxFilingVersion(yearBe: Int, formType: TaxFormType): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFilingHistory(entry: TaxFilingHistoryEntity)

    @Query("UPDATE tax_filing_history SET isOutdated = 1, lastDataMutationEpochMillis = :mutationTime WHERE taxYearBe = :yearBe AND filingState IN ('XML_GENERATED', 'READY')")
    suspend fun markActiveXmlOutdated(yearBe: Int, mutationTime: Long = System.currentTimeMillis())

    @Query("SELECT * FROM open_api_settings WHERE id = 'rd_open_api' LIMIT 1")
    fun observeOpenApiSettings(): Flow<OpenApiSettingsEntity?>

    @Query("SELECT * FROM open_api_settings WHERE id = 'rd_open_api' LIMIT 1")
    suspend fun getOpenApiSettingsOnce(): OpenApiSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOpenApiSettings(settings: OpenApiSettingsEntity)
}
