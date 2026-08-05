package com.claudeapprover.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.claudeapprover.data.Prefs
import com.claudeapprover.data.RequestStatus
import com.claudeapprover.net.NtfyClient
import org.json.JSONObject
import kotlin.concurrent.thread

class ReplyActionReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_DECISION = "decision"
        const val EXTRA_REPLY_TOPIC = "reply_topic"
        const val EXTRA_NOTIF_ID = "notif_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val decision = intent.getStringExtra(EXTRA_DECISION) ?: return
        val replyTopic = intent.getStringExtra(EXTRA_REPLY_TOPIC) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, requestId.hashCode())

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        thread {
            try {
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
                nm.cancel(notifId)

                appContext.sendBroadcast(
                    Intent(RelayService.ACTION_HISTORY_UPDATED).setPackage(appContext.packageName)
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
