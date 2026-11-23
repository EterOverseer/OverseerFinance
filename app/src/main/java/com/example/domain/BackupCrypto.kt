package com.example.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupCryptoHeader(
    val magic: String = "EFBK",
    val formatVersion: Int = 1,
    val cipherAlgorithm: String = "AES/GCM/NoPadding",
    val keySizeBits: Int = 256,
    val kdfAlgorithm: String = "PBKDF2WithHmacSHA256",
    val kdfIterations: Int = 210_000,
    val saltHex: String,
    val nonceHex: String,
    val schemaVersion: Int,
    val appVersion: String,
    val createdAtEpochMillis: Long
)

class BackupWrongPasswordOrCorruptException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Authenticated AES-256-GCM encryption for .efbackup files using PBKDF2-HMAC-SHA256.
 * Header JSON is bound as GCM Additional Authenticated Data (AAD) so tampering is detected immediately.
 */
object BackupCrypto {
    private const val MAGIC_BYTES = "EFBK"
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val KDF_ITERATIONS = 210_000

    private val json = Json { ignoreUnknownKeys = true }

    fun encryptToStream(
        plaintextPayload: ByteArray,
        password: String,
        schemaVersion: Int,
        appVersion: String,
        outputStream: OutputStream
    ) {
        require(password.length >= 6) { "รหัสผ่านสำรองข้อมูลต้องมีอย่างน้อย 6 ตัวอักษร" }
        val rng = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rng.nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { rng.nextBytes(it) }

        val header = BackupCryptoHeader(
            saltHex = salt.toHex(),
            nonceHex = nonce.toHex(),
            kdfIterations = KDF_ITERATIONS,
            schemaVersion = schemaVersion,
            appVersion = appVersion,
            createdAtEpochMillis = System.currentTimeMillis()
        )
        val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)

        val secretKey = deriveAes256Key(password, salt, KDF_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(headerBytes)
        val ciphertext = cipher.doFinal(plaintextPayload)

        val dos = DataOutputStream(outputStream)
        dos.write(MAGIC_BYTES.toByteArray(Charsets.US_ASCII))
        dos.writeInt(headerBytes.size)
        dos.write(headerBytes)
        dos.writeInt(ciphertext.size)
        dos.write(ciphertext)
        dos.flush()
    }

    fun readHeader(inputStream: InputStream): BackupCryptoHeader {
        val dis = DataInputStream(inputStream)
        val magic = ByteArray(4)
        dis.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != MAGIC_BYTES) {
            throw BackupWrongPasswordOrCorruptException("รูปแบบไฟล์ไม่ถูกต้อง (ไม่ใช่ไฟล์ .efbackup)")
        }
        val headerLen = dis.readInt()
        if (headerLen <= 0 || headerLen > 64 * 1024) {
            throw BackupWrongPasswordOrCorruptException("ส่วนหัวไฟล์สำรองข้อมูลเสียหาย")
        }
        val headerBytes = ByteArray(headerLen)
        dis.readFully(headerBytes)
        return json.decodeFromString(String(headerBytes, Charsets.UTF_8))
    }

    fun decryptFromStream(
        inputStream: InputStream,
        password: String
    ): Pair<BackupCryptoHeader, ByteArray> {
        val dis = DataInputStream(inputStream)
        val magic = ByteArray(4)
        try {
            dis.readFully(magic)
        } catch (e: Exception) {
            throw BackupWrongPasswordOrCorruptException("ไม่สามารถอ่านไฟล์สำรองข้อมูลได้", e)
        }
        if (String(magic, Charsets.US_ASCII) != MAGIC_BYTES) {
            throw BackupWrongPasswordOrCorruptException("รูปแบบไฟล์ไม่ถูกต้อง (ไม่ใช่ไฟล์ .efbackup)")
        }
        val headerLen = dis.readInt()
        if (headerLen <= 0 || headerLen > 64 * 1024) {
            throw BackupWrongPasswordOrCorruptException("ส่วนหัวไฟล์สำรองข้อมูลเสียหาย")
        }
        val headerBytes = ByteArray(headerLen)
        dis.readFully(headerBytes)
        val header = try {
            json.decodeFromString<BackupCryptoHeader>(String(headerBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupWrongPasswordOrCorruptException("ข้อมูลส่วนหัวไม่ถูกต้อง", e)
        }

        val cipherLen = dis.readInt()
        if (cipherLen <= 16) {
            throw BackupWrongPasswordOrCorruptException("ข้อมูลที่เข้ารหัสไม่สมบูรณ์")
        }
        val ciphertext = ByteArray(cipherLen)
        dis.readFully(ciphertext)

        val salt = header.saltHex.hexToBytes()
        val nonce = header.nonceHex.hexToBytes()
        val secretKey = deriveAes256Key(password, salt, header.kdfIterations)

        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(headerBytes)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw BackupWrongPasswordOrCorruptException(
                "รหัสผ่านไม่ถูกต้อง หรือไฟล์สำรองข้อมูลถูกดัดแปลง/เสียหาย",
                e
            )
        }

        return header to plaintext
    }

    private fun deriveAes256Key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Invalid hex string" }
        return ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
