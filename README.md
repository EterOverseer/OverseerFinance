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
  - 20 normalized Room SQLite tables (`AppDatabase`).
  - Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) K PLUS scanner + `WorkManager` periodic/manual scanning + K Plus Storage Cleaner.
  - Offline local notifications via `AlarmManager` + `BroadcastReceiver`.

## Offline PaddleOCR Model Setup

The Thai character dictionary (`app/src/main/assets/ocr/th_dict.txt`) and ONNX Runtime (`com.microsoft.onnxruntime:onnxruntime-android`) are bundled. To enable full on-device neural OCR detection and recognition, place these official PaddleOCR PP-OCRv5 ONNX binaries in `app/src/main/assets/ocr/`:

1. `PP-OCRv5_mobile_det.onnx`
2. `th_PP-OCRv5_mobile_rec.onnx`

Until those `.onnx` binaries are added, OverseerFinance honestly reports `MISSING_MODEL_ASSETS` and routes imported slips to the side-by-side manual verification screen without fabricating OCR results.

## Build & Test Commands

```bash
# Build debug APK
gradle :app:assembleDebug

# Run unit & Robolectric integration tests
gradle :app:testDebugUnitTest
```
