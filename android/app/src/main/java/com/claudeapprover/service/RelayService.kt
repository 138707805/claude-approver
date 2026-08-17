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
import androidx.core.app.RemoteInput
import com.claudeapprover.R
import com.claudeapprover.data.ClaudeState
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
        const val ACTION_SEND_PROMPT = "com.claudeapprover.SEND_PROMPT"
        const val ACTION_CANCEL_WAIT = "com.claudeapprover.CANCEL_WAIT"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_DECISION = "decision"
        const val EXTRA_REPLY_TOPIC = "reply_topic"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PROMPT_TEXT = "prompt_text"
        const val KEY_REMOTE_INPUT_TEXT = "remote_input_text"

        /**
         * 허용/거부를 누른 뒤 실제로 PC로 전송되기 전까지 앱에서 정정할 수 있는 시간.
         * 너무 길면 Claude Code가 그만큼 오래 기다리게 되고, 너무 짧으면 정정할
         * 틈이 없다 — 폰을 다시 꺼내 앱을 열고 고칠 정도의 여유(20초)로 잡았다.
         * PC 쪽 훅이 응답을 기다리는 최대 시간(설정상 170초)보다는 항상 짧아야
         * 정정이 실제로 반영된다 — 이 시간을 넘기면 PC 쪽은 이미 응답을 받아
         * 처리를 끝낸 뒤라 앱에서 더 이상 되돌릴 수 없다.
         */
        const val UNDO_WINDOW_MS = 20_000L

        /**
         * 재연결할 때 얼마나 거슬러 올라가서 놓친 메시지를 받아올지의 상한.
         * ntfy.sh의 무료 캐시가 12시간이라 그보다 더 올라가봐야 의미가 없다.
         */
        const val MAX_BACKFILL_SEC = 12 * 60 * 60L
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
            ACTION_SEND_PROMPT -> {
                sendPrompt(intent)
                return START_NOT_STICKY
            }
            ACTION_CANCEL_WAIT -> {
                cancelWait()
                return START_NOT_STICKY
            }
        }
        startForeground(FG_NOTIF_ID, buildForegroundNotification())
        if (!running) {
            running = true
            streamThread = thread(start = true, name = "ntfy-stream") { streamLoop() }
        }
        // 앱이 켜져 있는 동안에는 PC가 읽어갈 설정 캐시를 계속 살려둔다. ntfy의
        // 캐시는 12시간이면 만료되는데, 스위치를 안 건드리면 아무도 갱신하지
        // 않아서 PC가 예전 값으로 되돌아가 버리기 때문이다.
        republishSettings()
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

    // ---- 폰에서 다음 지시 보내기 ----

    // 알림의 "답장" 칸에 입력했거나(RemoteInput), 앱 입력창에서 보낸 텍스트를
    // prompt 토픽에 올린다. PC의 Stop 훅이 기다리고 있으면 그대로 이어서 작업한다.
    private fun sendPrompt(intent: Intent) {
        val fromNotification = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REMOTE_INPUT_TEXT)?.toString()
        val text = (fromNotification ?: intent.getStringExtra(EXTRA_PROMPT_TEXT) ?: "").trim()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: prefs.awaitingSessionId
        if (text.isEmpty() || prefs.pairingCode.isBlank()) return

        val topic = prefs.promptTopic
        thread {
            val body = JSONObject()
                .put("prompt", text)
                .put("sessionId", sessionId)
                .put("ts", System.currentTimeMillis())
                .toString()
            val ok = NtfyClient.publish(topic, body)
            mainHandler.post {
                prefs.awaitingUntil = 0L
                // 답장을 보냈으면 Claude는 다시 일하기 시작한다.
                if (ok) prefs.setClaudeState(ClaudeState.WORKING, "폰에서 보낸 지시를 처리하는 중", null)
                postStatusNotification(
                    if (ok) "지시를 보냈어요" else "전송에 실패했어요",
                    if (ok) text else "$text\n\n(네트워크 오류 — 앱에서 다시 보내주세요)",
                    awaitingPrompt = false,
                    sessionId = sessionId
                )
                broadcastHistoryUpdated()
            }
        }
    }

    // PC가 폰 입력을 기다리느라 멈춰 있는 걸 그만두게 한다(터미널에서 이어서 입력).
    private fun cancelWait() {
        if (prefs.pairingCode.isBlank()) return
        val topic = prefs.promptTopic
        val sessionId = prefs.awaitingSessionId
        prefs.awaitingUntil = 0L
        thread {
            NtfyClient.publish(topic, JSONObject().put("cancel", true).put("sessionId", sessionId).toString())
        }
        getSystemService(NotificationManager::class.java).cancel(STATUS_NOTIF_ID)
        broadcastHistoryUpdated()
    }

    // 스위치 값을 settings 토픽에 다시 올려서 PC가 읽어갈 캐시를 신선하게 유지한다.
    private fun republishSettings() {
        if (prefs.pairingCode.isBlank()) return
        val topic = prefs.settingsTopic
        val auto = prefs.autoApproveEnabled
        val remote = prefs.remoteInputEnabled
        thread {
            NtfyClient.publish(
                topic,
                JSONObject().put("autoApproveMode", auto).put("remoteInputMode", remote).toString()
            )
        }
    }

    // ---- ntfy 스트림 ----

    private fun streamLoop() {
        var backoffMs = 2000L
        while (running) {
            val topic = prefs.askTopic
            if (topic.isBlank() || topic == "-ask") {
                Thread.sleep(3000)
                continue
            }
            // 마지막으로 받은 메시지 시각부터 이어서 받는다. 이렇게 해야 연결이
            // 끊겨 있던 동안 온 알림(작업 완료 등)을 놓치지 않는다.
            val nowSec = System.currentTimeMillis() / 1000
            val lastSeen = prefs.lastEventEpochSec
            val sinceSec = when {
                lastSeen <= 0L -> nowSec - 5
                nowSec - lastSeen > MAX_BACKFILL_SEC -> nowSec - MAX_BACKFILL_SEC
                else -> lastSeen
            }
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
                // 연결이 끊기거나(읽기 타임아웃 포함) 오류가 나면 재시도한다.
                // 읽기 타임아웃이 여기로 오는 게 중요하다 — 조용히 죽은 연결을
                // 붙잡고 영영 기다리는 대신 다시 붙게 해준다.
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
            // keepalive/open 같은 이벤트도 "살아 있다"는 신호이므로 시각은 갱신한다.
            val eventTime = envelope.optLong("time", 0L)
            if (eventTime > 0L && eventTime > prefs.lastEventEpochSec) {
                prefs.lastEventEpochSec = eventTime
            }
            if (envelope.optString("event") != "message") return

            // 재연결하면서 겹쳐 받은 메시지를 두 번 처리하지 않도록 거른다.
            if (!prefs.markEventSeen(envelope.optString("id"))) return

            val inner = JSONObject(envelope.getString("message"))
            val cwd = inner.optString("cwd").ifEmpty { null }

            // 사용량 스냅샷은 어떤 종류의 메시지에 실려 오든 최신 것으로 갱신한다.
            if (inner.has("usage") && !inner.isNull("usage")) {
                prefs.usageJson = inner.getJSONObject("usage").toString()
            }

            when (inner.optString("type", "approval")) {
                "status" -> {
                    handleStatusMessage(inner, cwd)
                    broadcastHistoryUpdated()
                    return // 승인 이력이 아니라서 기록하지 않음
                }
                "info" -> {
                    // 자동 승인된 항목은 알림 없이 "최근 요청" 목록에만 조용히 남긴다.
                    val item = RequestItem(
                        id = inner.getString("id"),
                        tool = inner.optString("tool"),
                        title = inner.optString("title"),
                        body = inner.optString("body"),
                        cwd = cwd,
                        timestamp = System.currentTimeMillis(),
                        status = RequestStatus.ALLOWED,
                        auto = true
                    )
                    prefs.addOrUpdate(item)
                    prefs.setClaudeState(ClaudeState.WORKING, workingDetail(item), cwd)
                }
                "attention" -> {
                    val item = RequestItem(
                        id = inner.getString("id"),
                        tool = inner.optString("tool"),
                        title = inner.optString("title"),
                        body = inner.optString("body"),
                        cwd = cwd,
                        timestamp = System.currentTimeMillis(),
                        status = RequestStatus.ATTENTION
                    )
                    prefs.addOrUpdate(item)
                    // 사용량 한도 등 오류로 멈춘 경우는 "작업 중"이 아니라 오류 상태다.
                    val errorType = inner.optString("errorType")
                    if (errorType.isNotEmpty()) {
                        prefs.awaitingUntil = 0L
                        prefs.setClaudeState(ClaudeState.ERROR, item.title, cwd)
                    } else {
                        prefs.setClaudeState(ClaudeState.IDLE, "컴퓨터에서 확인이 필요해요", cwd)
                    }
                    postAttentionNotification(item)
                }
                else -> { // "approval"
                    val item = RequestItem(
                        id = inner.getString("id"),
                        tool = inner.optString("tool"),
                        title = inner.optString("title"),
                        body = inner.optString("body"),
                        cwd = cwd,
                        timestamp = System.currentTimeMillis(),
                        status = RequestStatus.PENDING
                    )
                    prefs.addOrUpdate(item)
                    prefs.setClaudeState(ClaudeState.WORKING, "승인을 기다리는 중", cwd)
                    postApprovalNotification(item)
                }
            }
            broadcastHistoryUpdated()
        } catch (e: Exception) {
            // 잘못된 형식의 메시지는 무시
        }
    }

    private fun workingDetail(item: RequestItem): String {
        val tool = item.tool.ifEmpty { "작업" }
        val firstLine = item.body.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (firstLine.isEmpty()) tool else "$tool · $firstLine"
    }

    // 한 턴이 끝났다는 알림. PC가 폰 입력을 기다리는 중이면 알림에서 바로
    // 답장할 수 있게 입력 칸이 달린 알림으로 띄운다.
    private fun handleStatusMessage(inner: JSONObject, cwd: String?) {
        val awaiting = inner.optBoolean("awaitingPrompt", false)
        val sessionId = inner.optString("sessionId")
        val waitSeconds = inner.optInt("waitSeconds", 0)

        if (awaiting && waitSeconds > 0) {
            prefs.awaitingSessionId = sessionId
            prefs.awaitingUntil = System.currentTimeMillis() + waitSeconds * 1000L
        } else {
            prefs.awaitingUntil = 0L
        }
        prefs.setClaudeState(ClaudeState.IDLE, "응답을 마치고 대기 중", cwd)

        val remaining = inner.optInt("remainingContinuations", -1)
        val title = inner.optString("title").ifEmpty { "작업 완료" }
        val body = buildString {
            append(inner.optString("body"))
            if (awaiting && remaining in 0..3) {
                append("\n\n(폰으로 이어서 지시할 수 있는 횟수가 ${remaining}번 남았어요)")
            }
        }
        postStatusNotification(title, body, awaiting && waitSeconds > 0, sessionId)
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
    // awaitingPrompt면 알림 안에서 바로 다음 지시를 입력할 수 있는 칸을 단다 —
    // 앱을 열지 않고 누운 채로 알림창에서 답장만 하면 되도록.
    private fun postStatusNotification(
        title: String,
        body: String,
        awaitingPrompt: Boolean,
        sessionId: String
    ) {
        val openAppIntent = PendingIntent.getActivity(
            this, STATUS_NOTIF_ID,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title.ifEmpty { "작업 완료" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentText(body)
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (awaitingPrompt) {
            val remoteInput = RemoteInput.Builder(KEY_REMOTE_INPUT_TEXT)
                .setLabel(getString(R.string.remote_input_hint))
                .build()
            val replyIntent = Intent(this, RelayService::class.java).apply {
                action = ACTION_SEND_PROMPT
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            // RemoteInput으로 입력한 글자를 시스템이 넣어줘야 하므로 MUTABLE이어야 한다.
            val replyPendingIntent = PendingIntent.getService(
                this, STATUS_NOTIF_ID * 3, replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(0, getString(R.string.reply_action), replyPendingIntent)
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(false)
                    .build()
            )

            val cancelPendingIntent = PendingIntent.getService(
                this, STATUS_NOTIF_ID * 3 + 1,
                Intent(this, RelayService::class.java).apply { action = ACTION_CANCEL_WAIT },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.cancel_wait_action), cancelPendingIntent)
            // 기다리는 동안은 알림이 남아 있어야 답장할 수 있다.
            builder.setAutoCancel(false).setOngoing(false)
        }

        getSystemService(NotificationManager::class.java).notify(STATUS_NOTIF_ID, builder.build())
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
