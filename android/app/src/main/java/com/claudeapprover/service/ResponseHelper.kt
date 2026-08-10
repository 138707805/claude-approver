package com.claudeapprover.service

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.claudeapprover.data.Prefs
import com.claudeapprover.data.RequestStatus
import com.claudeapprover.net.NtfyClient
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * 알림의 허용/거부 버튼과 앱 안 "최근 요청" 목록의 허용/거부 버튼이
 * 공통으로 쓰는 응답 처리 로직. 네트워크 호출은 항상 백그라운드 스레드에서 한다.
 */
object ResponseHelper {

    fun respond(context: Context, requestId: String, replyTopic: String, decision: String, onDone: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        thread {
            val body = JSONObject().put("id", requestId).put("decision", decision).toString()
            NtfyClient.publish(replyTopic, body)

            val prefs = Prefs(appContext)
            val items = prefs.loadHistory().toMutableList()
            val idx = items.indexOfFirst { it.id == requestId }
            if (idx >= 0) {
                items[idx].status = if (decision == "allow") RequestStatus.ALLOWED else RequestStatus.DENIED
                prefs.saveHistory(items)
            }

            val nm = appContext.getSystemService(NotificationManager::class.java)
            nm.cancel(requestId.hashCode())

            appContext.sendBroadcast(
                Intent(RelayService.ACTION_HISTORY_UPDATED).setPackage(appContext.packageName)
            )
            onDone?.invoke()
        }
    }
}
