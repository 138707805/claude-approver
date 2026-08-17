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

    /**
     * 스트리밍 연결용.
     *
     * 읽기 타임아웃을 0(무한)으로 두면 안 된다 — 휴대폰이 네트워크를 갈아타거나
     * 절전 모드로 들어가면서 TCP 연결이 조용히 죽는 경우, 소켓은 열려 있는 것처럼
     * 보이지만 아무것도 안 들어온다. 그러면 읽기에서 영원히 멈춰서 재연결도 못 하고
     * 알림이 통째로 안 오게 된다(v1.8까지 "완료 알림이 안 온다"의 주원인).
     *
     * ntfy는 45초마다 keepalive 줄을 보내므로, 그보다 넉넉히 긴 90초 동안 아무것도
     * 안 들어오면 연결이 죽은 것으로 보고 끊어서 재연결하게 한다.
     */
    val streamingClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
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
