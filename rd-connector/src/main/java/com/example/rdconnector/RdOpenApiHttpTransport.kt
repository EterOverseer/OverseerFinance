package com.example.rdconnector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Isolated HTTPS-only transport implementation for future Thai Revenue Department Open API
 * integration (https://efiling.rd.go.th/rd-cms/openapi and /rd-cms/api).
 *
 * Kept strictly separated in `:rd-connector` so the default `:app` build remains 100% offline
 * with zero network permissions in its merged AndroidManifest.
 *
 * Exact production RD Open API paths and JWS signing profiles must match the official developer
 * portal specification once registered with the Revenue Department.
 */
class RdOpenApiHttpTransport {

    data class HttpResponse(
        val statusCode: Int,
        val body: String
    )

    suspend fun postJsonHttps(
        baseUrl: String,
        endpointPath: String,
        bearerToken: String,
        idempotencyKey: String,
        jsonBody: String
    ): HttpResponse = withContext(Dispatchers.IO) {
        require(baseUrl.startsWith("https://", ignoreCase = true)) {
            "RD Open API transport requires HTTPS"
        }
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = if (endpointPath.startsWith("/")) endpointPath else "/$endpointPath"
        val url = URL("$cleanBase$cleanPath")
        val conn = (url.openConnection() as? HttpsURLConnection)
            ?: throw IllegalStateException("Expected HttpsURLConnection")

        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
            conn.setRequestProperty("Idempotency-Key", idempotencyKey)

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResponse(statusCode = code, body = responseText)
        } finally {
            conn.disconnect()
        }
    }
}
