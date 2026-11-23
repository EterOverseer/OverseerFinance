# OverseerFinance

**Personal Finance Intelligence — 100% Offline Native Android Application**

OverseerFinance is a native Kotlin + Jetpack Compose personal and small-business finance manager designed for Thai users, freelancers, online sellers, and Thai individual taxpayers. All data, OCR inference, analytics, tax rules, and encrypted backups stay strictly on-device with zero internet permissions or cloud dependencies.

## Architecture Overview

- **UI Layer (`com.example.ui`)**: Jetpack Compose + Material 3 Premium Dark Navy & Emerald UI, custom `Canvas` financial charts, bundled `Noto Sans Thai`, `Plus Jakarta Sans`, and `JetBrains Mono` typography, and adaptive `NavigationBar` / `NavigationRail`.
- **ViewModel (`com.example.ui.viewmodel.OverseerViewModel`)**: Reactive `StateFlow` orchestration via `collectAsStateWithLifecycle()`.
- **Domain Engines (`com.example.domain`)**:
  - `MoneyFormatter`: Exact integer Satang (`Long`) arithmetic (zero floating-point currency math).
  - `ThaiDateFormatter`: `Asia/Bangkok` timezone with Thai Buddhist Era (`พ.ศ.`) & Gregorian (`ค.ศ.`) support.
  - `ImageHashing`: Streaming SHA-256 + deterministic 64-bit DCT perceptual hash (`pHash`).
  - `PaddleOcrEngine` & `ThaiBankSlipParser`: Offline ONNX Runtime inference wrapper (`PP-OCRv5_mobile_det.onnx` + `th_PP-OCRv5_mobile_rec.onnx` + bundled `th_dict.txt`) and flexible Thai slip field parser with confidence tracking.
  - `ThaiTaxEngine`: Year-based Revenue Department progressive marginal tax ruleset (`0%–35%`, `rd.go.th/26218.html`), Section `40(1)–40(8)`, user-confirmed deductions, and Section `48(2)` 0.5% check.
  - `FinancialIntelligenceEngine`: Rule-based offline analytics over stored transactions.
  - `BackupCenterManager` & `BackupCrypto`: AES-256-GCM + PBKDF2-HMAC-SHA256 `.efbackup` archives, portable `.sqlite` exports with embedded `portable_image_blobs`, and atomic staged Full Restore with rollback.
- **Data & Background Layer (`com.example.data`, `com.example.worker`, `com.example.notifications`)**:
  - 26 normalized Room SQLite tables (`AppDatabase` v2 with explicit `MIGRATION_1_2`).
  - Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) K PLUS scanner + `WorkManager` periodic/manual scanning + K Plus Storage Cleaner.
  - Offline local notifications via `AlarmManager` + `BroadcastReceiver`.
- **Advanced Thai Personal Tax Filing (`com.example.tax`)**:
  - **Tax Center → Annual Tax Filing Workspace**: 10-section annual preparation workspace (`OVERVIEW`, `RECONCILIATION`, `DEDUCTIONS`, `WITHHOLDING`, `VAULT`, `CALCULATOR`, `CHECKLIST`, `XML_HANDOFF`, `OPEN_API`, `HISTORY`).
  - **Bank Statement CSV Reconciliation (`BankStatementReconciler`)**: Configurable bank presets (KBank, SCB, KTB, BBL, Custom), UTF-8 & TIS-620/Windows-874 decoding, SHA-256 batch deduplication, transaction matching across K PLUS slips & manual entries, mandatory user review (deposits are never auto-taxed), and `ReconciliationAuditEntity` change logging.
  - **Extended Form & Readiness Engine (`ThaiTaxFilingEngine`)**: Statutory eligibility checks for **ภ.ง.ด.91**, **ภ.ง.ด.90**, and half-year **ภ.ง.ด.94** (40(5)–40(8)), Thai 13-digit TIN modulus-11 checksum validation, itemized readiness checklist (zero fake percentages), and missing evidence detection.
  - **Tax Document Vault (`TaxDocumentVaultManager`)**: SAF PDF/image import into `filesDir/tax_vault_docs/` with SHA-256 verification, optional local OCR, mandatory user confirmation (`PENDING_REVIEW` -> `USER_CONFIRMED`), and full `.efbackup` & `.sqlite` backup/restore integration.
  - **Official RD XML Public & e-Filing Handoff (`RdXmlPublicEngine`)**: Strict UTF-8 serializer driven by `RdXmlSpecDescriptor`. Built-in registry marks official RD XML XSD tags as unverified (`OFFICIAL_SPEC_NOT_VERIFIED`) until an official XSD package from `https://efiling.rd.go.th/rd-cms/tax?_target=system` is loaded — **never fabricates XML tags**. Provides offline PDF e-Filing preparation sheets, external browser handoff to `https://efiling.rd.go.th/rd-cms/`, and versioned `TaxFilingHistoryEntity` tracking with automatic `isOutdated` flagging when underlying data changes.
  - **Future RD Open API Abstraction & `:rd-connector` Isolation (`TaxSubmissionProvider`, `:rd-connector`)**: `LocalXmlExportProvider` (offline, active) and `FutureRdOpenApiProvider` (disabled by default; HTTPS-only, token expiry, idempotency keys, zero credential logging). Network transport (`RdOpenApiHttpTransport`) is strictly isolated in the unlinked `:rd-connector` Gradle library module so the core `:app` APK never declares `INTERNET`.

## Offline PaddleOCR Model Setup

The Thai character dictionary (`app/src/main/assets/ocr/th_dict.txt`) and ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime-android`) are bundled. To enable full on-device neural OCR detection and recognition, place these official PaddleOCR PP-OCRv5 ONNX binaries in `app/src/main/assets/ocr/`:

1. `PP-OCRv5_mobile_det.onnx`
2. `th_PP-OCRv5_mobile_rec.onnx`

Until those `.onnx` binaries are added, OverseerFinance honestly reports `MISSING_MODEL_ASSETS` and routes imported slips to the side-by-side manual verification screen without fabricating OCR results.

## Build & Test Commands

```bash
# Build offline app debug APK (verified zero INTERNET permission)
gradle :app:assembleDebug

# Build isolated optional RD Open API connector library
gradle :rd-connector:assembleDebug

# Run unit & Robolectric integration tests
gradle :app:testDebugUnitTest
```

## GitHub Actions APK Build

The repository includes `.github/workflows/build-apk.yml`, which runs on pushes/PRs to `main` / `master`, version tags (`v*`), and manual `workflow_dispatch`:

- Sets up Temurin JDK 21 and Gradle 9.3.1, restores `debug.keystore` from `debug.keystore.base64`, runs `:app:testDebugUnitTest`, and builds `:app:assembleDebug` + `:rd-connector:assembleDebug`.
- Uploads the APK to both **Actions → Artifacts** (`OverseerFinance-debug-apk`) and **GitHub Releases** (`OverseerFinance-debug.apk` under the `latest` release or the `v*` tag).
- To build a signed Release APK via **Actions → Build Android APK → Run workflow (`release` or `all`)**, configure these GitHub Repository Secrets (with key alias `upload`):
  - `RELEASE_KEYSTORE_BASE64`: Base64-encoded `.jks` keystore
  - `STORE_PASSWORD`: Keystore password
  - `KEY_PASSWORD`: Key password

