package com.claudeapprover.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.claudeapprover.R
import com.claudeapprover.data.Prefs
import com.claudeapprover.data.RequestItem
import com.claudeapprover.data.RequestStatus
import com.claudeapprover.net.NtfyClient
import com.claudeapprover.ui.MainActivity
import okhttp3.Call
import org.json.JSONObject
import java.io.IOException
import kotlin.concurrent.thread

class RelayService : Service() {

    companion object {
        const val CHANNEL_APPROVAL = "approval_requests"
        const val CHANNEL_FOREGROUND = "foreground_status"
        const val FG_NOTIF_ID = 1
        const val ACTION_HISTORY_UPDATED = "com.claudeapprover.HISTORY_UPDATED"
        const val ACTION_STOP = "com.claudeapprover.STOP_SERVICE"
    }

    private lateinit var prefs: Prefs
    @Volatile private var running = false
    private var currentCall: Call? = null
    private var streamThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfClean()
            return START_NOT_STICKY
        }
        startForeground(FG_NOTIF_ID, buildForegroundNotification())
        if (!running) {
            running = true
            streamThread = thread(start = true, name = "ntfy-stream") { streamLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        currentCall?.cancel()
        streamThread?.interrupt()
        prefs.monitoringEnabled = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun stopSelfClean() {
        running = false
        currentCall?.cancel()
        prefs.monitoringEnabled = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun streamLoop() {
        var backoffMs = 2000L
        while (running) {
            val topic = prefs.askTopic
            if (topic.isBlank() || topic == "-ask") {
                Thread.sleep(3000)
                continue
            }
            val sinceSec = System.currentTimeMillis() / 1000 - 5
            val call = NtfyClient.openStreamCall(topic, sinceSec)
            currentCall = call
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    backoffMs = 2000L
                    val source = response.body?.source() ?: return@use
                    while (running && !source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        handleStreamLine(line)
                    }
                }
            } catch (e: Exception) {
                // 연결이 끊기면 재시도 (백오프)
            }
            if (running) {
                try {
                    Thread.sleep(backoffMs)
                } catch (ignored: InterruptedException) {
                }
                backoffMs = (backoffMs * 2).coerceAtMost(30000L)
            }
        }
    }

    private fun handleStreamLine(line: String) {
        try {
            val envelope = JSONObject(line)
            if (envelope.optString("event") != "message") return
            val inner = JSONObject(envelope.getString("message"))
            val item = RequestItem(
                id = inner.getString("id"),
                tool = inner.optString("tool"),
                title = inner.optString("title"),
                body = inner.optString("body"),
                cwd = inner.optString("cwd").ifEmpty { null },
                timestamp = System.currentTimeMillis(),
                status = RequestStatus.PENDING
            )
            prefs.addOrUpdate(item)
            postApprovalNotification(item)
            broadcastHistoryUpdated()
        } catch (e: Exception) {
            // 잘못된 형식의 메시지는 무시
        }
    }

    private fun broadcastHistoryUpdated() {
        sendBroadcast(Intent(ACTION_HISTORY_UPDATED).setPackage(packageName))
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_APPROVAL,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = getString(R.string.channel_description) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FOREGROUND,
                getString(R.string.fg_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(getString(R.string.fg_service_title))
            .setContentText(getString(R.string.fg_service_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun postApprovalNotification(item: RequestItem) {
        val notifId = item.id.hashCode()
        val replyTopic = prefs.replyTopic

        val allowIntent = replyPendingIntent(item.id, replyTopic, "allow", notifId * 2)
        val denyIntent = replyPendingIntent(item.id, replyTopic, "deny", notifId * 2 + 1)

        val openAppIntent = PendingIntent.getActivity(
            this, notifId,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_APPROVAL)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(item.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
            .setContentText(item.body)
            .setContentIntent(openAppIntent)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, getString(R.string.allow), allowIntent)
            .addAction(0, getString(R.string.deny), denyIntent)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(notifId, notification)
    }

    private fun replyPendingIntent(requestId: String, replyTopic: String, decision: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ReplyActionReceiver::class.java).apply {
            action = "com.claudeapprover.REPLY_${requestId}_$decision"
            putExtra(ReplyActionReceiver.EXTRA_REQUEST_ID, requestId)
            putExtra(ReplyActionReceiver.EXTRA_DECISION, decision)
            putExtra(ReplyActionReceiver.EXTRA_REPLY_TOPIC, replyTopic)
        }
        return PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
