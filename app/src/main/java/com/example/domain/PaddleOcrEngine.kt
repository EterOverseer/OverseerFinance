package com.example.domain

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.example.data.local.OcrModelStatus
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

enum class OcrModelSlot(val fileName: String, val labelTh: String) {
    DETECTION("PP-OCRv5_mobile_det.onnx", "โมเดลตรวจจับข้อความ (Det)"),
    RECOGNITION("th_PP-OCRv5_mobile_rec.onnx", "โมเดลอ่านข้อความไทย (Rec)"),
    DICTIONARY("th_dict.txt", "พจนานุกรมอักขระไทย (Dict)")
}

data class OcrModelDiagnostics(
    val status: OcrModelStatus,
    val hasDetectionModel: Boolean,
    val detectionSource: String,
    val detectionSizeBytes: Long,
    val hasRecognitionModel: Boolean,
    val recognitionSource: String,
    val recognitionSizeBytes: Long,
    val hasDictionary: Boolean,
    val dictionarySource: String,
    val dictionarySizeBytes: Long,
    val hasUserInstalledFiles: Boolean,
    val lastUpdatedEpochMillis: Long?
)

data class OcrInstallResult(
    val installedCount: Int,
    val messageTh: String,
    val diagnostics: OcrModelDiagnostics
)

data class OcrExecutionOutcome(
    val engineName: String,
    val modelVersion: String,
    val modelStatus: OcrModelStatus,
    val lines: List<OcrTextLine>,
    val rawText: String,
    val parsedSlip: ParsedBankSlip,
    val diagnosticMessage: String
)

/**
 * Offline PaddleOCR PP-OCRv5 ONNX Runtime Engine.
 *
 * Looks for model files in this priority order:
 * 1. User-installed/updated files inside Context.filesDir/ocr_models/
 * 2. Bundled APK assets inside assets/ocr/
 *
 * Required files:
 * - PP-OCRv5_mobile_det.onnx
 * - th_PP-OCRv5_mobile_rec.onnx
 * - th_dict.txt (bundled in assets/ocr/ by default)
 *
 * Never downloads models over the network inside the app and never invents fake OCR results.
 */
class PaddleOcrEngine(private val context: Context) {

    companion object {
        const val ENGINE_NAME = "PaddleOCR PP-OCRv5 (ONNX Runtime)"
        const val MODEL_VERSION = "th_PP-OCRv5_mobile_rec + PP-OCRv5_mobile_det"
        const val ASSET_DIR = "ocr"
        const val LOCAL_MODELS_DIR = "ocr_models"
        const val DET_MODEL_FILE = "PP-OCRv5_mobile_det.onnx"
        const val REC_MODEL_FILE = "th_PP-OCRv5_mobile_rec.onnx"
        const val DICT_FILE = "th_dict.txt"
        const val DET_MODEL_ASSET = "$ASSET_DIR/$DET_MODEL_FILE"
        const val REC_MODEL_ASSET = "$ASSET_DIR/$REC_MODEL_FILE"
        const val DICT_ASSET = "$ASSET_DIR/$DICT_FILE"
        const val OFFICIAL_MODEL_GUIDE_URL = "https://github.com/PaddlePaddle/PaddleOCR/blob/main/README.md"
    }

    fun ocrModelsDir(): File = File(context.filesDir, LOCAL_MODELS_DIR).apply { mkdirs() }

    private fun bundledAssetSet(): Set<String> =
        runCatching { context.assets.list(ASSET_DIR)?.toSet() ?: emptySet() }
            .getOrDefault(emptySet())

    private fun localFileFor(fileName: String): File = File(ocrModelsDir(), fileName)

    private fun assetSize(fileName: String): Long {
        return runCatching {
            context.assets.open("$ASSET_DIR/$fileName").use { it.available().toLong() }
        }.getOrDefault(0L)
    }

    fun inspectDiagnostics(): OcrModelDiagnostics {
        val dir = ocrModelsDir()
        val assets = bundledAssetSet()

        val detLocal = File(dir, DET_MODEL_FILE).takeIf { it.exists() && it.length() > 0L }
        val detAsset = assets.contains(DET_MODEL_FILE)
        val hasDet = detLocal != null || detAsset
        val detSource = when {
            detLocal != null -> "ติดตั้ง/อัปเดตในเครื่อง"
            detAsset -> "มากับแอป (Assets)"
            else -> "ยังไม่มีไฟล์"
        }
        val detSize = detLocal?.length() ?: if (detAsset) assetSize(DET_MODEL_FILE) else 0L

        val recLocal = File(dir, REC_MODEL_FILE).takeIf { it.exists() && it.length() > 0L }
        val recAsset = assets.contains(REC_MODEL_FILE)
        val hasRec = recLocal != null || recAsset
        val recSource = when {
            recLocal != null -> "ติดตั้ง/อัปเดตในเครื่อง"
            recAsset -> "มากับแอป (Assets)"
            else -> "ยังไม่มีไฟล์"
        }
        val recSize = recLocal?.length() ?: if (recAsset) assetSize(REC_MODEL_FILE) else 0L

        val dictLocal = File(dir, DICT_FILE).takeIf { it.exists() && it.length() > 0L }
        val dictAsset = assets.contains(DICT_FILE)
        val hasDict = dictLocal != null || dictAsset
        val dictSource = when {
            dictLocal != null -> "อัปเดตในเครื่อง"
            dictAsset -> "มากับแอป (Assets)"
            else -> "ยังไม่มีไฟล์"
        }
        val dictSize = dictLocal?.length() ?: if (dictAsset) assetSize(DICT_FILE) else 0L

        val localFiles = listOfNotNull(detLocal, recLocal, dictLocal)
        val lastUpdated = localFiles.maxOfOrNull { it.lastModified() }?.takeIf { it > 0L }

        val status = if (hasDet && hasRec && hasDict) {
            OcrModelStatus.READY
        } else {
            OcrModelStatus.MISSING_MODEL_ASSETS
        }

        return OcrModelDiagnostics(
            status = status,
            hasDetectionModel = hasDet,
            detectionSource = detSource,
            detectionSizeBytes = detSize,
            hasRecognitionModel = hasRec,
            recognitionSource = recSource,
            recognitionSizeBytes = recSize,
            hasDictionary = hasDict,
            dictionarySource = dictSource,
            dictionarySizeBytes = dictSize,
            hasUserInstalledFiles = localFiles.isNotEmpty(),
            lastUpdatedEpochMillis = lastUpdated
        )
    }

    fun inspectModelAvailability(): OcrModelStatus = inspectDiagnostics().status

    fun inferSlotFromFileName(fileName: String): OcrModelSlot? {
        val lower = fileName.lowercase()
        return when {
            lower == DET_MODEL_FILE.lowercase() || (lower.endsWith(".onnx") && lower.contains("det")) ->
                OcrModelSlot.DETECTION
            lower == REC_MODEL_FILE.lowercase() || (lower.endsWith(".onnx") && lower.contains("rec")) ->
                OcrModelSlot.RECOGNITION
            lower == DICT_FILE.lowercase() || lower.endsWith(".txt") || lower.contains("dict") ->
                OcrModelSlot.DICTIONARY
            else -> null
        }
    }

    private fun defaultValidateOnnxFile(file: File) {
        val env = OrtEnvironment.getEnvironment()
        val bytes = file.readBytes()
        env.createSession(bytes, OrtSession.SessionOptions()).use { /* valid */ }
    }

    /**
     * Installs or updates OCR model file(s) from user-selected InputStreams into Context.filesDir/ocr_models/.
     * Validates non-empty payload and verifies ONNX session compatibility when native ONNX Runtime is available.
     */
    fun installOrUpdateModelFiles(
        entries: List<Pair<String, InputStream>>,
        explicitSlot: OcrModelSlot? = null,
        validateOnnx: (File) -> Unit = ::defaultValidateOnnxFile
    ): OcrInstallResult {
        if (entries.isEmpty()) {
            return OcrInstallResult(0, "ไม่ได้เลือกไฟล์โมเดล", inspectDiagnostics())
        }

        val dir = ocrModelsDir()
        var installed = 0
        val errors = mutableListOf<String>()
        val installedNames = mutableListOf<String>()

        entries.forEach { (originalName, inputStream) ->
            val targetSlot = explicitSlot ?: inferSlotFromFileName(originalName)
            if (targetSlot == null) {
                errors += "ไม่รู้จักชนิดไฟล์ '$originalName' (รองรับ det*.onnx, rec*.onnx, th_dict.txt)"
                return@forEach
            }

            val stageFile = File(dir, "${targetSlot.fileName}.staging")
            val destFile = File(dir, targetSlot.fileName)
            try {
                FileOutputStream(stageFile).use { out ->
                    inputStream.copyTo(out, bufferSize = 32 * 1024)
                    out.flush()
                }
                val size = stageFile.length()
                if (size < 64L) {
                    stageFile.delete()
                    errors += "ไฟล์ '${originalName}' เล็กผิดปกติหรือว่างเปล่า"
                    return@forEach
                }

                if (targetSlot != OcrModelSlot.DICTIONARY) {
                    if (size < 1024L) {
                        stageFile.delete()
                        errors += "ไฟล์โมเดล ONNX '${originalName}' ไม่สมบูรณ์ (${size} bytes)"
                        return@forEach
                    }
                    // Validate ONNX file integrity if native runtime is loaded (skip gracefully on JVM if JNI unavailable)
                    try {
                        validateOnnx(stageFile)
                    } catch (_: LinkageError) {
                        // Native onnxruntime JNI unavailable on host JVM test environment (UnsatisfiedLinkError / NoClassDefFoundError); keep size/stream checks
                    } catch (e: Throwable) {
                        stageFile.delete()
                        errors += "ไฟล์ '${originalName}' ไม่ใช่โมเดล ONNX ที่ใช้งานได้: ${e.message ?: "invalid format"}"
                        return@forEach
                    }
                } else {
                    val lineCount = stageFile.readLines(Charsets.UTF_8).count { it.isNotEmpty() }
                    if (lineCount < 10) {
                        stageFile.delete()
                        errors += "ไฟล์พจนานุกรม '${originalName}' มีข้อมูลน้อยเกินไป"
                        return@forEach
                    }
                }

                if (destFile.exists()) destFile.delete()
                if (!stageFile.renameTo(destFile)) {
                    stageFile.copyTo(destFile, overwrite = true)
                    stageFile.delete()
                }
                destFile.setLastModified(System.currentTimeMillis())
                installed++
                installedNames += targetSlot.fileName
            } catch (t: Throwable) {
                stageFile.delete()
                errors += "ติดตั้ง '${originalName}' ไม่สำเร็จ: ${t.message ?: "IO Error"}"
            }
        }

        val diag = inspectDiagnostics()
        val message = buildString {
            if (installed > 0) {
                append("ติดตั้ง/อัปเดตสำเร็จ $installed ไฟล์ (${installedNames.joinToString(", ")})")
                if (diag.status == OcrModelStatus.READY) {
                    append(" • เอนจิน OCR พร้อมใช้งานแล้ว")
                } else {
                    val missing = buildList {
                        if (!diag.hasDetectionModel) add(DET_MODEL_FILE)
                        if (!diag.hasRecognitionModel) add(REC_MODEL_FILE)
                        if (!diag.hasDictionary) add(DICT_FILE)
                    }
                    append(" • ยังขาด: ${missing.joinToString(", ")}")
                }
            }
            if (errors.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(errors.joinToString(" | "))
            }
        }
        return OcrInstallResult(installed, message, diag)
    }

    fun resetInstalledModels(): OcrModelDiagnostics {
        val dir = ocrModelsDir()
        listOf(DET_MODEL_FILE, REC_MODEL_FILE, DICT_FILE).forEach { name ->
            File(dir, name).takeIf { it.exists() }?.delete()
        }
        return inspectDiagnostics()
    }

    fun sha256OfFile(fileName: String): String? {
        val f = localFileFor(fileName).takeIf { it.exists() } ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun recognizeSlip(imageFile: File): OcrExecutionOutcome {
        val diag = inspectDiagnostics()
        if (diag.status != OcrModelStatus.READY) {
            val emptyLines = emptyList<OcrTextLine>()
            return OcrExecutionOutcome(
                engineName = ENGINE_NAME,
                modelVersion = MODEL_VERSION,
                modelStatus = OcrModelStatus.MISSING_MODEL_ASSETS,
                lines = emptyLines,
                rawText = "",
                parsedSlip = ThaiBankSlipParser.parse(emptyLines),
                diagnosticMessage = "ยังไม่พบไฟล์โมเดล OCR ($DET_MODEL_FILE / $REC_MODEL_FILE) สามารถติดตั้งได้ที่ ตั้งค่า → จัดการโมเดล OCR หรือกรอกข้อมูลสลิปด้วยตนเอง"
            )
        }

        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: return OcrExecutionOutcome(
                engineName = ENGINE_NAME,
                modelVersion = MODEL_VERSION,
                modelStatus = OcrModelStatus.INFERENCE_ERROR,
                lines = emptyList(),
                rawText = "",
                parsedSlip = ThaiBankSlipParser.parse(emptyList()),
                diagnosticMessage = "ไม่สามารถถอดรหัสไฟล์ภาพสลิปเพื่อทำ OCR ได้"
            )

        return try {
            val dict = loadDictionary()
            val lines = runOnnxInference(bitmap, dict)
            val rawText = lines.joinToString("\n") { it.text }
            val parsed = ThaiBankSlipParser.parse(lines)
            OcrExecutionOutcome(
                engineName = ENGINE_NAME,
                modelVersion = MODEL_VERSION,
                modelStatus = OcrModelStatus.READY,
                lines = lines,
                rawText = rawText,
                parsedSlip = parsed,
                diagnosticMessage = "วิเคราะห์ข้อความภาษาไทยด้วยโมเดลออฟไลน์สำเร็จ (${lines.size} บรรทัด)"
            )
        } catch (t: Throwable) {
            OcrExecutionOutcome(
                engineName = ENGINE_NAME,
                modelVersion = MODEL_VERSION,
                modelStatus = OcrModelStatus.INFERENCE_ERROR,
                lines = emptyList(),
                rawText = "",
                parsedSlip = ThaiBankSlipParser.parse(emptyList()),
                diagnosticMessage = "การประมวลผล ONNX OCR ขัดข้อง: ${t.message ?: "Unknown error"}"
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun readModelBytes(fileName: String): ByteArray {
        val local = localFileFor(fileName)
        if (local.exists() && local.length() > 0L) {
            return local.readBytes()
        }
        return context.assets.open("$ASSET_DIR/$fileName").use { it.readBytes() }
    }

    private fun runOnnxInference(bitmap: Bitmap, dictChars: List<String>): List<OcrTextLine> {
        val env = OrtEnvironment.getEnvironment()
        val detBytes = readModelBytes(DET_MODEL_FILE)
        val recBytes = readModelBytes(REC_MODEL_FILE)

        env.createSession(detBytes, OrtSession.SessionOptions()).use { detSession ->
            env.createSession(recBytes, OrtSession.SessionOptions()).use { recSession ->
                val boxes = detectTextRegions(env, detSession, bitmap)
                val recognized = mutableListOf<OcrTextLine>()
                for (box in boxes) {
                    val crop = Bitmap.createBitmap(
                        bitmap,
                        box.left.coerceIn(0, bitmap.width - 1),
                        box.top.coerceIn(0, bitmap.height - 1),
                        box.width.coerceIn(1, bitmap.width - box.left),
                        box.height.coerceIn(1, bitmap.height - box.top)
                    )
                    val line = recognizeCroppedLine(env, recSession, crop, dictChars)
                    crop.recycle()
                    if (line.text.isNotBlank()) {
                        recognized.add(line)
                    }
                }
                return recognized
            }
        }
    }

    private data class BoxRect(val left: Int, val top: Int, val width: Int, val height: Int)

    private fun detectTextRegions(
        env: OrtEnvironment,
        detSession: OrtSession,
        bitmap: Bitmap
    ): List<BoxRect> {
        val scale = min(960f / max(bitmap.width, bitmap.height), 1f)
        val targetW = (((bitmap.width * scale).toInt() + 31) / 32 * 32).coerceAtLeast(32)
        val targetH = (((bitmap.height * scale).toInt() + 31) / 32 * 32).coerceAtLeast(32)
        val resized = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)

        val inputBuffer = FloatBuffer.allocate(3 * targetH * targetW)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val pixels = IntArray(targetW * targetH)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        resized.recycle()

        for (c in 0 until 3) {
            for (i in pixels.indices) {
                val p = pixels[i]
                val channel = when (c) {
                    0 -> Color.red(p)
                    1 -> Color.green(p)
                    else -> Color.blue(p)
                } / 255f
                inputBuffer.put((channel - mean[c]) / std[c])
            }
        }
        inputBuffer.rewind()

        val inputName = detSession.inputNames.first()
        val tensor = OnnxTensor.createTensor(
            env,
            inputBuffer,
            longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong())
        )
        tensor.use {
            detSession.run(mapOf(inputName to it)).use { result ->
                val rawOut = result[0].value as Array<*>
                @Suppress("UNCHECKED_CAST")
                val probMap = (rawOut[0] as Array<Array<FloatArray>>)[0]
                return extractHorizontalLineBoxes(probMap, targetW, targetH, bitmap.width, bitmap.height)
            }
        }
    }

    private fun extractHorizontalLineBoxes(
        probMap: Array<FloatArray>,
        mapW: Int,
        mapH: Int,
        origW: Int,
        origH: Int
    ): List<BoxRect> {
        val activeRows = BooleanArray(mapH)
        val rowMinX = IntArray(mapH) { mapW }
        val rowMaxX = IntArray(mapH) { 0 }

        for (y in 0 until mapH) {
            var count = 0
            for (x in 0 until mapW) {
                if (probMap[y][x] > 0.3f) {
                    count++
                    if (x < rowMinX[y]) rowMinX[y] = x
                    if (x > rowMaxX[y]) rowMaxX[y] = x
                }
            }
            if (count >= 4) activeRows[y] = true
        }

        val scaleX = origW.toFloat() / mapW.toFloat()
        val scaleY = origH.toFloat() / mapH.toFloat()
        val boxes = mutableListOf<BoxRect>()
        var y = 0
        while (y < mapH) {
            if (!activeRows[y]) {
                y++
                continue
            }
            val startY = y
            var minX = rowMinX[y]
            var maxX = rowMaxX[y]
            while (y < mapH && activeRows[y]) {
                minX = min(minX, rowMinX[y])
                maxX = max(maxX, rowMaxX[y])
                y++
            }
            val endY = y
            if (endY - startY >= 3 && maxX - minX >= 6) {
                val pad = 4
                val left = ((minX * scaleX).toInt() - pad).coerceAtLeast(0)
                val top = ((startY * scaleY).toInt() - pad).coerceAtLeast(0)
                val right = ((maxX * scaleX).toInt() + pad).coerceAtMost(origW)
                val bottom = ((endY * scaleY).toInt() + pad).coerceAtMost(origH)
                boxes.add(BoxRect(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1)))
            }
        }
        return boxes
    }

    private fun recognizeCroppedLine(
        env: OrtEnvironment,
        recSession: OrtSession,
        crop: Bitmap,
        dictChars: List<String>
    ): OcrTextLine {
        val targetH = 48
        val ratio = crop.width.toFloat() / crop.height.coerceAtLeast(1).toFloat()
        val targetW = (targetH * ratio).toInt().coerceIn(32, 640)
        val resized = Bitmap.createScaledBitmap(crop, targetW, targetH, true)

        val buffer = FloatBuffer.allocate(3 * targetH * targetW)
        val pixels = IntArray(targetW * targetH)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        resized.recycle()

        for (c in 0 until 3) {
            for (i in pixels.indices) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> Color.red(p)
                    1 -> Color.green(p)
                    else -> Color.blue(p)
                }
                buffer.put((v / 255f - 0.5f) / 0.5f)
            }
        }
        buffer.rewind()

        val inputName = recSession.inputNames.first()
        val tensor = OnnxTensor.createTensor(
            env,
            buffer,
            longArrayOf(1L, 3L, targetH.toLong(), targetW.toLong())
        )
        tensor.use {
            recSession.run(mapOf(inputName to it)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = (result[0].value as Array<Array<FloatArray>>)[0]
                return ctcGreedyDecode(logits, dictChars)
            }
        }
    }

    private fun ctcGreedyDecode(timesteps: Array<FloatArray>, dictChars: List<String>): OcrTextLine {
        val sb = StringBuilder()
        var prevIdx = 0
        var confSum = 0f
        var charCount = 0

        for (step in timesteps) {
            var bestIdx = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (i in step.indices) {
                if (step[i] > bestVal) {
                    bestVal = step[i]
                    bestIdx = i
                }
            }
            if (bestIdx != 0 && bestIdx != prevIdx) {
                val dictIdx = bestIdx - 1
                if (dictIdx in dictChars.indices) {
                    sb.append(dictChars[dictIdx])
                    val prob = if (bestVal in 0f..1f) bestVal else (1f / (1f + exp(-bestVal)))
                    confSum += prob
                    charCount++
                }
            }
            prevIdx = bestIdx
        }

        val avgConf = if (charCount == 0) 0f else (confSum / charCount).coerceIn(0f, 1f)
        return OcrTextLine(sb.toString().trim(), avgConf)
    }

    private fun loadDictionary(): List<String> {
        val localDict = localFileFor(DICT_FILE)
        if (localDict.exists() && localDict.length() > 0L) {
            return runCatching {
                localDict.bufferedReader(Charsets.UTF_8).use { it.readLines() }
            }.getOrDefault(emptyList())
        }
        return runCatching {
            context.assets.open(DICT_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLines()
            }
        }.getOrDefault(emptyList())
    }
}
