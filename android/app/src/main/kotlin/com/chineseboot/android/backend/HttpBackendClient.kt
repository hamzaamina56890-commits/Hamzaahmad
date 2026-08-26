package com.chineseboot.android.backend

import com.chineseboot.android.core.backend.AnalyzeRequest
import com.chineseboot.android.core.backend.BackendClient
import com.chineseboot.android.core.backend.BackendResult
import com.chineseboot.android.core.backend.BackendStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/**
 * Minimal HTTP client for the existing FastAPI backend (`backend/app.py`).
 * Uses plain [HttpURLConnection] to avoid pulling in extra networking
 * dependencies. Any network failure surfaces as [BackendResult.Offline] /
 * [BackendResult.Error] — it never synthesizes a fake success value.
 */
class HttpBackendClient(private val baseUrl: String) : BackendClient {

    override suspend fun status(): BackendResult<BackendStatus> = withContext(Dispatchers.IO) {
        try {
            val json = get("$baseUrl/api/status")
            BackendResult.Success(
                BackendStatus(
                    service = json.optString("service"),
                    version = json.optString("version"),
                    apiKeyConfigured = json.optBoolean("api_key_configured", false),
                )
            )
        } catch (e: UnknownHostException) {
            BackendResult.Offline
        } catch (e: IOException) {
            BackendResult.Offline
        } catch (e: Exception) {
            BackendResult.Error(e.message ?: "Unknown backend error")
        }
    }

    override suspend fun analyze(request: AnalyzeRequest): BackendResult<Map<String, Any?>> =
        withContext(Dispatchers.IO) {
            try {
                val json = post("$baseUrl/api/analyze", request)
                BackendResult.Success(jsonToMap(json))
            } catch (e: UnknownHostException) {
                BackendResult.Offline
            } catch (e: IOException) {
                BackendResult.Offline
            } catch (e: Exception) {
                BackendResult.Error(e.message ?: "Unknown backend error")
            }
        }

    private fun get(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 4000
        connection.readTimeout = 4000
        return try {
            JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    private fun post(url: String, request: AnalyzeRequest): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 4000
        connection.readTimeout = 4000

        val body = JSONObject().apply {
            put("symbol", request.symbol)
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }

        return try {
            JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any?> =
        json.keys().asSequence().associateWith { key -> json.opt(key) }
}
