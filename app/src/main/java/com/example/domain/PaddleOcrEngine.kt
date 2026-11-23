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
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
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
    val pinnedVersion: String,
    val hasDetectionModel: Boolean,
    val detectionSizeBytes: Long,
    val detectionSha256Verified: Boolean,
    val hasRecognitionModel: Boolean,
    val recognitionSizeBytes: Long,
    val recognitionSha256Verified: Boolean,
    val hasDictionary: Boolean,
    val dictionarySizeBytes: Long,
    val dictionaryCharCount: Int,
    val dictionarySha256Verified: Boolean
) {
    val allIntegrityVerified: Boolean
        get() = hasDetectionModel && detectionSha256Verified &&
            hasRecognitionModel && recognitionSha256Verified &&
            hasDictionary && dictionarySha256Verified
}

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
 * Built-in, version-pinned Offline PaddleOCR PP-OCRv5 ONNX Runtime Engine.
 *
 * All required models and the matching Thai CTC dictionary are bundled inside `assets/ocr/`
 * with pinned upstream revisions and SHA-256 verification so no runtime downloads are needed
 * and future builds cannot suffer from model/dictionary version drift.
 */
class PaddleOcrEngine(private val context: Context) {

    private data class BundledAssetVerification(
        val exists: Boolean,
        val sizeBytes: Long,
        val checksumMatches: Boolean
    )

    companion object {
        const val ENGINE_NAME = "PaddleOCR PP-OCRv5 (ONNX Runtime)"
        const val PINNED_MODEL_VERSION = "PP-OCRv5-th-mobile-pinned-v1"
        const val MODEL_VERSION = "PP-OCRv5_mobile_det + th_PP-OCRv5_mobile_rec ($PINNED_MODEL_VERSION)"
        const val ASSET_DIR = "ocr"

        const val DET_MODEL_FILE = "PP-OCRv5_mobile_det.onnx"
        const val DET_MODEL_REVISION = "e6f4fa85f00e168c862bc462aebca69eef9b3d3d"
        const val DET_MODEL_SHA256 = "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d"
        const val DET_MODEL_BYTES = 4_826_518L

        const val REC_MODEL_FILE = "th_PP-OCRv5_mobile_rec.onnx"
        const val REC_MODEL_REVISION = "1d4adbbafb1034a2fd6618498575b81ea7b69f69"
        const val REC_MODEL_SHA256 = "27618be66018f8598ac0a526a593f9f1cebf794e7eded93428e8fb016e537f5f"
        const val REC_MODEL_BYTES = 7_891_015L

        const val DICT_FILE = "th_dict.txt"
        const val DICT_SHA256 = "57f5406f94bb6688fb7077f7be65f08bbd71cecf48c01ea26c522cb5c4836b7a"
        const val DICT_CHAR_COUNT = 524

        const val DET_MODEL_ASSET = "$ASSET_DIR/$DET_MODEL_FILE"
        const val REC_MODEL_ASSET = "$ASSET_DIR/$REC_MODEL_FILE"
        const val DICT_ASSET = "$ASSET_DIR/$DICT_FILE"

        private val verificationCache = ConcurrentHashMap<String, BundledAssetVerification>()
    }

    private fun verifyBundledAsset(
        assetPath: String,
        expectedSha256: String
    ): BundledAssetVerification {
        verificationCache[assetPath]?.let { return it }

        val computed = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            context.assets.open(assetPath).use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    totalBytes += read
                }
            }
            val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
            BundledAssetVerification(
                exists = totalBytes > 0L,
                sizeBytes = totalBytes,
                checksumMatches = actualHex.equals(expectedSha256, ignoreCase = true)
            )
        }.getOrElse {
            BundledAssetVerification(exists = false, sizeBytes = 0L, checksumMatches = false)
        }

        if (computed.exists) {
            verificationCache[assetPath] = computed
        }
        return computed
    }

    fun inspectDiagnostics(): OcrModelDiagnostics {
        val det = verifyBundledAsset(DET_MODEL_ASSET, DET_MODEL_SHA256)
        val rec = verifyBundledAsset(REC_MODEL_ASSET, REC_MODEL_SHA256)
        val dict = verifyBundledAsset(DICT_ASSET, DICT_SHA256)

        val status = when {
            !det.exists || !rec.exists || !dict.exists -> OcrModelStatus.MISSING_MODEL_ASSETS
            !det.checksumMatches || !rec.checksumMatches || !dict.checksumMatches -> OcrModelStatus.INFERENCE_ERROR
            else -> OcrModelStatus.READY
        }

        return OcrModelDiagnostics(
            status = status,
            pinnedVersion = PINNED_MODEL_VERSION,
            hasDetectionModel = det.exists,
            detectionSizeBytes = det.sizeBytes,
            detectionSha256Verified = det.checksumMatches,
            hasRecognitionModel = rec.exists,
            recognitionSizeBytes = rec.sizeBytes,
            recognitionSha256Verified = rec.checksumMatches,
            hasDictionary = dict.exists,
            dictionarySizeBytes = dict.sizeBytes,
            dictionaryCharCount = DICT_CHAR_COUNT,
            dictionarySha256Verified = dict.checksumMatches
        )
    }

    fun inspectModelAvailability(): OcrModelStatus = inspectDiagnostics().status

    fun recognizeSlip(imageFile: File): OcrExecutionOutcome {
        val diag = inspectDiagnostics()
        if (diag.status != OcrModelStatus.READY) {
            val emptyLines = emptyList<OcrTextLine>()
            val msg = if (diag.status == OcrModelStatus.MISSING_MODEL_ASSETS) {
                "ไม่พบไฟล์โมเดล OCR ที่ฝังมากับแอป ($DET_MODEL_FILE / $REC_MODEL_FILE)"
            } else {
                "ตรวจสอบความสมบูรณ์ (SHA-256) ของโมเดล OCR รุ่น $PINNED_MODEL_VERSION ไม่ผ่าน"
            }
            return OcrExecutionOutcome(
                engineName = ENGINE_NAME,
                modelVersion = MODEL_VERSION,
                modelStatus = diag.status,
                lines = emptyLines,
                rawText = "",
                parsedSlip = ThaiBankSlipParser.parse(emptyLines),
                diagnosticMessage = msg
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

    private fun readBundledAssetBytes(assetPath: String): ByteArray =
        context.assets.open(assetPath).use { it.readBytes() }

    private fun runOnnxInference(bitmap: Bitmap, dictChars: List<String>): List<OcrTextLine> {
        val env = OrtEnvironment.getEnvironment()
        val detBytes = readBundledAssetBytes(DET_MODEL_ASSET)
        val recBytes = readBundledAssetBytes(REC_MODEL_ASSET)

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
        // PP-OCRv5_mobile_det inference.yml: resize_long = 960, img_mode = BGR, mean/std ImageNet
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
                    0 -> Color.blue(p)
                    1 -> Color.green(p)
                    else -> Color.red(p)
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
        // th_PP-OCRv5_mobile_rec inference.yml: img_mode = BGR, RecResizeImg [3, 48, 320], [-1, 1], right zero-padded
        val targetH = 48
        val ratio = crop.width.toFloat() / crop.height.coerceAtLeast(1).toFloat()
        val resizedW = (targetH * ratio).toInt().coerceIn(16, 640)
        val paddedW = max(320, resizedW)
        val resized = Bitmap.createScaledBitmap(crop, resizedW, targetH, true)

        val buffer = FloatBuffer.allocate(3 * targetH * paddedW)
        val pixels = IntArray(resizedW * targetH)
        resized.getPixels(pixels, 0, resizedW, 0, 0, resizedW, targetH)
        resized.recycle()

        for (c in 0 until 3) {
            for (y in 0 until targetH) {
                val rowOffset = y * resizedW
                for (x in 0 until paddedW) {
                    if (x < resizedW) {
                        val p = pixels[rowOffset + x]
                        val v = when (c) {
                            0 -> Color.blue(p)
                            1 -> Color.green(p)
                            else -> Color.red(p)
                        }
                        buffer.put((v / 255f - 0.5f) / 0.5f)
                    } else {
                        buffer.put(0f)
                    }
                }
            }
        }
        buffer.rewind()

        val inputName = recSession.inputNames.first()
        val tensor = OnnxTensor.createTensor(
            env,
            buffer,
            longArrayOf(1L, 3L, targetH.toLong(), paddedW.toLong())
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

    fun loadDictionary(): List<String> {
        val baseChars = runCatching {
            context.assets.open(DICT_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLines().filter { it.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
        // PaddleOCR CTCLabelDecode appends space character at the end (use_space_char = true -> 524 + 1 = 525 + blank = 526)
        return if (baseChars.isNotEmpty() && baseChars.last() != " ") {
            baseChars + " "
        } else {
            baseChars
        }
    }
}
