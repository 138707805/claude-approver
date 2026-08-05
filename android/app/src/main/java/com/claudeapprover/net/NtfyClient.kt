package com.claudeapprover.net

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val NTFY_BASE = "https://ntfy.sh"
private val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()

object NtfyClient {

    /** 스트리밍 연결에는 읽기 타임아웃이 없어야 한다 (계속 열려 있는 연결). */
    val streamingClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val shortClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun publish(topic: String, jsonBody: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("$NTFY_BASE/$topic")
                .post(jsonBody.toRequestBody(TEXT_PLAIN))
                .build()
            shortClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    fun openStreamCall(topic: String, sinceEpochSec: Long): Call {
        val request = Request.Builder()
            .url("$NTFY_BASE/$topic/json?poll=false&since=$sinceEpochSec")
            .get()
            .build()
        return streamingClient.newCall(request)
    }
}
