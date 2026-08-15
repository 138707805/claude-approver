package com.claudeapprover.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
        const val CHANNEL_STATUS = "status_updates"
        const val CHANNEL_FOREGROUND = "foreground_status"
        const val FG_NOTIF_ID = 1
        const val STATUS_NOTIF_ID = 2
        const val ACTION_HISTORY_UPDATED = "com.claudeapprover.HISTORY_UPDATED"
        const val ACTION_STOP = "com.claudeapprover.STOP_SERVICE"
        const val ACTION_STAGE_DECISION = "com.claudeapprover.STAGE_DECISION"
        const val ACTION_UNDO_DECISION = "com.claudeapprover.UNDO_DECISION"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_DECISION = "decision"
        const val EXTRA_REPLY_TOPIC = "reply_topic"

        /**
         * 허용/거부를 누른 뒤 실제로 PC로 전송되기 전까지 앱에서 정정할 수 있는 시간.
         * 너무 길면 Claude Code가 그만큼 오래 기다리게 되고, 너무 짧으면 정정할
         * 틈이 없다 — 폰을 다시 꺼내 앱을 열고 고칠 정도의 여유(20초)로 잡았다.
         * PC 쪽 훅이 응답을 기다리는 최대 시간(설정상 170초)보다는 항상 짧아야
         * 정정이 실제로 반영된다 — 이 시간을 넘기면 PC 쪽은 이미 응답을 받아
         * 처리를 끝낸 뒤라 앱에서 더 이상 되돌릴 수 없다.
         */
        const val UNDO_WINDOW_MS = 20_000L
    }

    private lateinit var prefs: Prefs
    @Volatile private var running = false
    private var currentCall: Call? = null
    private var streamThread: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCommits = mutableMapOf<String, Runnable>()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfClean()
                return START_NOT_STICKY
            }
            ACTION_STAGE_DECISION -> {
                stageDecision(intent)
                return START_NOT_STICKY
            }
            ACTION_UNDO_DECISION -> {
                undoDecision(intent)
                return START_NOT_STICKY
            }
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
        pendingCommits.values.forEach { mainHandler.removeCallbacks(it) }
        pendingCommits.clear()
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

    // 허용/거부를 누르면 바로 전송하지 않고 UNDO_WINDOW_MS만큼 기다렸다가 커밋한다.
    // 그 사이에 앱의 "최근 요청" 화면에서 실행취소로 정정할 수 있다. 단, 폰 화면을
    // 가리는 별도 알림은 띄우지 않는다 — 원래 승인 알림은 누르는 즉시 조용히 닫는다.
    private fun stageDecision(intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val decision = intent.getStringExtra(EXTRA_DECISION) ?: return
        val replyTopic = intent.getStringExtra(EXTRA_REPLY_TOPIC) ?: return

        cancelPending(requestId)
        getSystemService(NotificationManager::class.java).cancel(requestId.hashCode())
        broadcastHistoryUpdated()

        val commitRunnable = Runnable {
            pendingCommits.remove(requestId)
            ResponseHelper.respond(this, requestId, replyTopic, decision)
        }
        pendingCommits[requestId] = commitRunnable
        mainHandler.postDelayed(commitRunnable, UNDO_WINDOW_MS)
    }

    // 정정은 앱의 "최근 요청" 화면에서만 이뤄지므로, 굳이 알림을 다시 띄우지 않고
    // 대기 상태를 되돌리기만 한다 — 화면에 알림이 다시 뜨는 걸 원치 않기 때문.
    private fun undoDecision(intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        cancelPending(requestId)
        broadcastHistoryUpdated()
    }

    private fun cancelPending(requestId: String) {
        pendingCommits.remove(requestId)?.let { mainHandler.removeCallbacks(it) }
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

            when (inner.optString("type", "approval")) {
                "status" -> {
                    postStatusNotification(inner.optString("title"), inner.optString("body"))
                    return // 승인 이력이 아니라서 기록하지 않음
                }
                "info" -> {
                    // 자동 승인된 항목은 알림 없이 "최근 요청" 목록에만 조용히 남긴다.
                    val item = RequestItem(
                        id = inner.getString("id"),
                        tool = inner.optString("tool"),
                        title = inner.optString("title"),
                        body = inner.optString("body"),
                        cwd = inner.optString("cwd").ifEmpty { null },
                        timestamp = System.currentTimeMillis(),
                        status = RequestStatus.ALLOWED,
                        auto = true
                    )
                    prefs.addOrUpdate(item)
                }
                "attention" -> {
                    val item = RequestItem(
                        id = inner.getString("id"),
                        tool = inner.optString("tool"),
                        title = inner.optString("title"),
                        body = inner.optString("body"),
                        cwd = inner.optString("cwd").ifEmpty { null },
                        timestamp = System.currentTimeMillis(),
                        status = RequestStatus.ATTENTION
                    )
                    prefs.addOrUpdate(item)
                    postAttentionNotification(item)
                }
                else -> { // "approval"
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
                }
            }
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
                CHANNEL_STATUS,
                getString(R.string.channel_status_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = getString(R.string.channel_status_description) }
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

        val allowIntent = stagePendingIntent(item.id, replyTopic, "allow", notifId * 2)
        val denyIntent = stagePendingIntent(item.id, replyTopic, "deny", notifId * 2 + 1)

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

    // 휴대폰으로는 제대로 판단할 수 없거나(계획 승인 등), 응답 시간이 지나 이미
    // 터미널로 넘어간 요청 — "컴퓨터를 확인하라"는 것만 알려준다.
    private fun postAttentionNotification(item: RequestItem) {
        val openAppIntent = PendingIntent.getActivity(
            this, item.id.hashCode(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(item.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.body))
            .setContentText(item.body)
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(item.id.hashCode(), notification)
    }

    // 한 턴의 작업이 끝났을 때. 이력에는 남기지 않고 알림만 띄운다(계속 쌓이지 않게 같은 ID 재사용).
    private fun postStatusNotification(title: String, body: String) {
        val openAppIntent = PendingIntent.getActivity(
            this, STATUS_NOTIF_ID,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title.ifEmpty { "작업 완료" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentText(body)
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java).notify(STATUS_NOTIF_ID, notification)
    }

    private fun stagePendingIntent(requestId: String, replyTopic: String, decision: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, RelayService::class.java).apply {
            action = ACTION_STAGE_DECISION
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_DECISION, decision)
            putExtra(EXTRA_REPLY_TOPIC, replyTopic)
        }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
