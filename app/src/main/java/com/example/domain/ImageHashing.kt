package com.example.domain

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Cryptographic SHA-256 and deterministic 64-bit DCT Perceptual Hash (pHash) utilities.
 */
object ImageHashing {
    const val DEFAULT_PHASH_SIMILARITY_THRESHOLD = 8

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.toHex()
    }

    fun sha256(file: File): String = FileInputStream(file).use { sha256(it) }

    fun sha256(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
        }
        return md.digest().toHex()
    }

    /**
     * Deterministic 64-bit DCT pHash from an Android Bitmap.
     * Steps: resize 32x32 -> luminance grayscale -> 2D DCT (top-left 8x8 excluding DC) -> median threshold.
     */
    fun computePerceptualHash(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val gray = Array(32) { DoubleArray(32) }
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                val pixel = scaled.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                gray[y][x] = 0.299 * r + 0.587 * g + 0.114 * b
            }
        }
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        return computePerceptualHashFromMatrix(gray)
    }

    /**
     * Pure JVM-testable 64-bit DCT pHash from a 32x32 luminance matrix.
     */
    fun computePerceptualHashFromMatrix(gray32x32: Array<DoubleArray>): String {
        require(gray32x32.size == 32 && gray32x32.all { it.size == 32 }) {
            "Expected 32x32 grayscale matrix for pHash"
        }
        val n = 32
        val dctValues = DoubleArray(64)
        var idx = 0
        for (u in 0 until 8) {
            for (v in 0 until 8) {
                var sum = 0.0
                for (x in 0 until n) {
                    val cosX = cos(((2 * x + 1) * u * PI) / (2.0 * n))
                    for (y in 0 until n) {
                        val cosY = cos(((2 * y + 1) * v * PI) / (2.0 * n))
                        sum += gray32x32[x][y] * cosX * cosY
                    }
                }
                val cu = if (u == 0) 1.0 / sqrt(2.0) else 1.0
                val cv = if (v == 0) 1.0 / sqrt(2.0) else 1.0
                dctValues[idx++] = 0.25 * cu * cv * sum
            }
        }
        // Exclude DC component (index 0) for median calculation to stay brightness-invariant
        val acValues = dctValues.copyOfRange(1, 64).sorted()
        val median = acValues[acValues.size / 2]

        var hash = 0L
        for (i in 0 until 64) {
            if (dctValues[i] > median) {
                hash = hash or (1L shl i)
            }
        }
        return String.format("%016x", hash)
    }

    fun hammingDistance(hexA: String, hexB: String): Int {
        if (hexA.length != 16 || hexB.length != 16) return 64
        val a = hexA.toULongOrNull(16) ?: return 64
        val b = hexB.toULongOrNull(16) ?: return 64
        return (a xor b).countOneBits()
    }

    fun isVisuallySimilar(
        hexA: String,
        hexB: String,
        threshold: Int = DEFAULT_PHASH_SIMILARITY_THRESHOLD
    ): Boolean = hexA.isNotBlank() && hexB.isNotBlank() && hammingDistance(hexA, hexB) <= threshold

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
