package com.example.domain

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import com.example.data.local.OcrModelStatus
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * Uses 1536px detection scaling, 8-connected-component DBNet post-processing with Thai-safe
 * vertical unclipping (preserving upper tone marks and lower vowels), horizontal word-only
 * merging (never merging across column gaps or side icons), and dynamic-width BGR recognition.
 */
class PaddleOcrEngine(private val context: Context) {

    data class BoxRect(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
        val centerY: Float get() = top + height / 2f
    }

    private data class RawComponent(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val area: Int,
        val meanScore: Float
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val centerY: Float get() = (minY + maxY) / 2f
    }

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

        // High-detail detection & DBPostProcess tuning for Thai slips (small fonts + tone marks)
        private const val DET_LIMIT_SIDE_LEN = 1536f
        private const val DET_PIXEL_THRESH = 0.22f
        private const val DET_BOX_SCORE_THRESH = 0.38f
        private const val DB_UNCLIP_RATIO = 1.6f
        private const val REC_MIN_CONFIDENCE = 0.35f

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

        val bitmap = decodeUprightBitmap(imageFile)
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

    private fun decodeUprightBitmap(imageFile: File): Bitmap? {
        val raw = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return null
        val rotationDegrees = runCatching {
            when (ExifInterface(imageFile.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        if (rotationDegrees == 0f) return raw
        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        return rotated
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
                    if (line.text.isNotBlank() && line.confidence >= REC_MIN_CONFIDENCE) {
                        recognized.add(line)
                    }
                }
                return recognized
            }
        }
    }

    private fun detectTextRegions(
        env: OrtEnvironment,
        detSession: OrtSession,
        bitmap: Bitmap
    ): List<BoxRect> {
        // PP-OCRv5_mobile_det: keep high resolution up to 1536px so Thai tone marks & small refs stay sharp
        val maxSide = max(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scale = min(DET_LIMIT_SIDE_LEN / maxSide, 1f)
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
                return extractTextBoxesFromProbMap(probMap, targetW, targetH, bitmap.width, bitmap.height)
            }
        }
    }

    /**
     * Extracts individual text boxes from DBNet probability map using 8-connected components,
     * horizontal same-line word merging (without merging across column gaps or left-side icons),
     * DBNet unclip expansion with Thai upper/lower vowel headroom, and reading-order sorting.
     */
    fun extractTextBoxesFromProbMap(
        probMap: Array<FloatArray>,
        mapW: Int,
        mapH: Int,
        origW: Int,
        origH: Int
    ): List<BoxRect> {
        if (mapW <= 0 || mapH <= 0 || origW <= 0 || origH <= 0) return emptyList()

        val visited = BooleanArray(mapW * mapH)
        val queue = IntArray(mapW * mapH)
        val components = mutableListOf<RawComponent>()

        for (sy in 0 until mapH) {
            val row = probMap[sy]
            for (sx in 0 until mapW) {
                val startIdx = sy * mapW + sx
                if (visited[startIdx] || row[sx] <= DET_PIXEL_THRESH) continue

                visited[startIdx] = true
                var head = 0
                var tail = 0
                queue[tail++] = startIdx

                var minX = sx
                var maxX = sx
                var minY = sy
                var maxY = sy
                var area = 0
                var scoreSum = 0f

                while (head < tail) {
                    val curr = queue[head++]
                    val cy = curr / mapW
                    val cx = curr % mapW
                    area++
                    scoreSum += probMap[cy][cx]
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    for (dy in -1..1) {
                        val ny = cy + dy
                        if (ny !in 0 until mapH) continue
                        val nRow = probMap[ny]
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            if (nx !in 0 until mapW) continue
                            val nIdx = ny * mapW + nx
                            if (!visited[nIdx] && nRow[nx] > DET_PIXEL_THRESH) {
                                visited[nIdx] = true
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }

                val compW = maxX - minX + 1
                val compH = maxY - minY + 1
                val meanScore = scoreSum / area.coerceAtLeast(1)
                if (area >= 4 && compW >= 3 && compH >= 2 && meanScore >= DET_BOX_SCORE_THRESH) {
                    components.add(RawComponent(minX, minY, maxX, maxY, area, meanScore))
                }
            }
        }

        if (components.isEmpty()) return emptyList()

        // Group words on the same text line cleanly (do not bridge wide column gaps or side icons)
        val sortedForMerge = components.sortedWith(compareBy<RawComponent> { it.minY }.thenBy { it.minX })
        val merged = mutableListOf<RawComponent>()
        val used = BooleanArray(sortedForMerge.size)

        for (i in sortedForMerge.indices) {
            if (used[i]) continue
            used[i] = true
            var cur = sortedForMerge[i]

            var changed: Boolean
            do {
                changed = false
                for (j in sortedForMerge.indices) {
                    if (used[j]) continue
                    val cand = sortedForMerge[j]
                    if (shouldMergeSameLineWords(cur, cand)) {
                        cur = RawComponent(
                            minX = min(cur.minX, cand.minX),
                            minY = min(cur.minY, cand.minY),
                            maxX = max(cur.maxX, cand.maxX),
                            maxY = max(cur.maxY, cand.maxY),
                            area = cur.area + cand.area,
                            meanScore = (cur.meanScore + cand.meanScore) / 2f
                        )
                        used[j] = true
                        changed = true
                    }
                }
            } while (changed)

            merged.add(cur)
        }

        // Apply DBNet unclip expansion mapped to original image coordinates
        val scaleX = origW.toFloat() / mapW.toFloat()
        val scaleY = origH.toFloat() / mapH.toFloat()

        val unclipped = merged.map { comp ->
            val rawLeft = comp.minX * scaleX
            val rawTop = comp.minY * scaleY
            val rawRight = (comp.maxX + 1) * scaleX
            val rawBottom = (comp.maxY + 1) * scaleY
            val boxW = (rawRight - rawLeft).coerceAtLeast(1f)
            val boxH = (rawBottom - rawTop).coerceAtLeast(1f)

            // DBNet Vatti distance = (area * unclip_ratio) / perimeter
            val unclipDist = ((boxW * boxH * DB_UNCLIP_RATIO) / (2f * (boxW + boxH))).coerceAtLeast(3f)
            val expandTop = max(6, (unclipDist * 1.15f).roundToInt()) // Extra margin for Thai upper tone marks
            val expandBottom = max(5, (unclipDist * 0.95f).roundToInt()) // Margin for lower vowels (ุ / ู)
            val expandSide = max(6, (unclipDist * 0.85f).roundToInt())

            val left = (rawLeft.roundToInt() - expandSide).coerceAtLeast(0)
            val top = (rawTop.roundToInt() - expandTop).coerceAtLeast(0)
            val right = (rawRight.roundToInt() + expandSide).coerceAtMost(origW)
            val bottom = (rawBottom.roundToInt() + expandBottom).coerceAtMost(origH)

            BoxRect(
                left = left,
                top = top,
                width = (right - left).coerceAtLeast(1),
                height = (bottom - top).coerceAtLeast(1)
            )
        }

        return sortBoxesInReadingOrder(unclipped)
    }

    private fun shouldMergeSameLineWords(a: RawComponent, b: RawComponent): Boolean {
        val overlapY = min(a.maxY, b.maxY) - max(a.minY, b.minY) + 1
        val minH = min(a.height, b.height).coerceAtLeast(1)
        val maxH = max(a.height, b.height).coerceAtLeast(1)
        if (overlapY < (minH * 0.50f)) return false
        if (maxH > minH * 2.2f) return false
        if (kotlin.math.abs(a.centerY - b.centerY) > maxH * 0.45f) return false

        val horizontalGap = max(a.minX, b.minX) - min(a.maxX, b.maxX) - 1
        val maxWordGap = max(minH * 1.6f, 8f)
        return horizontalGap <= maxWordGap
    }

    private fun sortBoxesInReadingOrder(boxes: List<BoxRect>): List<BoxRect> {
        if (boxes.size <= 1) return boxes
        val sortedByY = boxes.sortedBy { it.centerY }
        val lines = mutableListOf<MutableList<BoxRect>>()
        for (box in sortedByY) {
            val existingLine = lines.lastOrNull()
            if (existingLine == null) {
                lines.add(mutableListOf(box))
            } else {
                val lineAvgY = existingLine.map { it.centerY }.average().toFloat()
                val lineAvgH = existingLine.map { it.height }.average().toFloat()
                if (kotlin.math.abs(box.centerY - lineAvgY) <= max(lineAvgH, box.height.toFloat()) * 0.55f) {
                    existingLine.add(box)
                } else {
                    lines.add(mutableListOf(box))
                }
            }
        }
        return lines.flatMap { row -> row.sortedBy { it.left } }
    }

    private fun recognizeCroppedLine(
        env: OrtEnvironment,
        recSession: OrtSession,
        crop: Bitmap,
        dictChars: List<String>
    ): OcrTextLine {
        // th_PP-OCRv5_mobile_rec inference.yml: img_mode = BGR, RecResizeImg [3, 48, 320..3200], [-1, 1], right zero-padded
        val targetH = 48
        val ratio = crop.width.toFloat() / crop.height.coerceAtLeast(1).toFloat()
        val resizedW = ceil(targetH * ratio).toInt().coerceIn(16, 1440)
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
