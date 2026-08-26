package com.chineseboot.android.core.backend

/** Result of a call against the Python backend that never invents data on failure. */
sealed class BackendResult<out T> {
    data class Success<T>(val value: T) : BackendResult<T>()
    object Offline : BackendResult<Nothing>()
    data class Error(val message: String) : BackendResult<Nothing>()
}

data class BackendStatus(
    val service: String,
    val version: String,
    val apiKeyConfigured: Boolean,
)

data class AnalyzeRequest(
    val symbol: String,
    val candles: List<Map<String, Double>>,
)

/**
 * Contract for talking to the existing FastAPI backend (`/api/status`,
 * `/api/analyze`, `/api/assets`). The real HTTP implementation lives in the
 * `app` module (it needs Android's networking/permissions); this interface
 * keeps the calling code testable and makes the "must not fabricate data when
 * offline" requirement explicit via [BackendResult.Offline].
 */
interface BackendClient {
    suspend fun status(): BackendResult<BackendStatus>
    suspend fun analyze(request: AnalyzeRequest): BackendResult<Map<String, Any?>>
}
