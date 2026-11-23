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
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

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
 * Looks for bundled local assets in assets/ocr/:
 * - PP-OCRv5_mobile_det.onnx
 * - th_PP-OCRv5_mobile_rec.onnx
 * - th_dict.txt
 *
 * Never downloads models over the network and never invents fake OCR results if models are absent.
 */
class PaddleOcrEngine(private val context: Context) {

    companion object {
        const val ENGINE_NAME = "PaddleOCR PP-OCRv5 (ONNX Runtime)"
        const val MODEL_VERSION = "th_PP-OCRv5_mobile_rec + PP-OCRv5_mobile_det"
        const val ASSET_DIR = "ocr"
        const val DET_MODEL_ASSET = "$ASSET_DIR/PP-OCRv5_mobile_det.onnx"
        const val REC_MODEL_ASSET = "$ASSET_DIR/th_PP-OCRv5_mobile_rec.onnx"
        const val DICT_ASSET = "$ASSET_DIR/th_dict.txt"
    }

    private val dictChars: List<String> by lazy { loadDictionary() }

    fun inspectModelAvailability(): OcrModelStatus {
        val assetList = runCatching { context.assets.list(ASSET_DIR)?.toSet() ?: emptySet() }
            .getOrDefault(emptySet())
        val hasDet = assetList.contains("PP-OCRv5_mobile_det.onnx")
        val hasRec = assetList.contains("th_PP-OCRv5_mobile_rec.onnx")
        val hasDict = assetList.contains("th_dict.txt")
        return if (hasDet && hasRec && hasDict) {
            OcrModelStatus.READY
        } else {
            OcrModelStatus.MISSING_MODEL_ASSETS
        }
    }

    fun recognizeSlip(imageFile: File): OcrExecutionOutcome {
        val status = inspectModelAvailability()
        if (status != OcrModelStatus.READY) {
            val emptyLines = emptyList<OcrTextLine>()
            return OcrExecutionOutcome(
                engineName = ENGINE_NAME,
                modelVersion = MODEL_VERSION,
                modelStatus = OcrModelStatus.MISSING_MODEL_ASSETS,
                lines = emptyLines,
                rawText = "",
                parsedSlip = ThaiBankSlipParser.parse(emptyLines),
                diagnosticMessage = "ไม่พบไฟล์โมเดล PP-OCRv5_mobile_det.onnx และ th_PP-OCRv5_mobile_rec.onnx ใน assets/ocr/ กรุณาตรวจสอบและกรอกข้อมูลสลิปด้วยตนเอง"
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
            val lines = runOnnxInference(bitmap)
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

    private fun runOnnxInference(bitmap: Bitmap): List<OcrTextLine> {
        val env = OrtEnvironment.getEnvironment()
        val detBytes = context.assets.open(DET_MODEL_ASSET).use { it.readBytes() }
        val recBytes = context.assets.open(REC_MODEL_ASSET).use { it.readBytes() }

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
                    val line = recognizeCroppedLine(env, recSession, crop)
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
        // Resize to multiple of 32 while preserving aspect ratio (max side 960)
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
        // Simple row-band grouping of active DBNet probability regions (> 0.3f)
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
        crop: Bitmap
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
                return ctcGreedyDecode(logits)
            }
        }
    }

    private fun ctcGreedyDecode(timesteps: Array<FloatArray>): OcrTextLine {
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
            // Index 0 is CTC blank in PaddleOCR
            if (bestIdx != 0 && bestIdx != prevIdx) {
                val dictIdx = bestIdx - 1
                if (dictIdx in dictChars.indices) {
                    sb.append(dictChars[dictIdx])
                    // Softmax approximation for confidence if logits aren't normalized
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
        return runCatching {
            context.assets.open(DICT_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLines()
            }
        }.getOrDefault(emptyList())
    }
}
