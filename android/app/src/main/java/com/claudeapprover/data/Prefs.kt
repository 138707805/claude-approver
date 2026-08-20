package com.claudeapprover.data

import android.content.Context
import org.json.JSONArray

private const val PREFS_NAME = "claude_approver_prefs"
private const val KEY_PAIRING_CODE = "pairing_code"
private const val KEY_MONITORING = "monitoring_enabled"
private const val KEY_HISTORY = "history_json"
private const val KEY_AUTO_APPROVE = "auto_approve_when_unreachable"
private const val KEY_REMOTE_INPUT = "remote_input_enabled"
private const val KEY_LAST_EVENT_SEC = "last_event_epoch_sec"
private const val KEY_SEEN_IDS = "seen_event_ids"
private const val KEY_USAGE_JSON = "usage_json"
private const val KEY_USAGE_AT = "usage_updated_at"
private const val KEY_STATE = "claude_state"
private const val KEY_STATE_DETAIL = "claude_state_detail"
private const val KEY_STATE_AT = "claude_state_at"
private const val KEY_STATE_CWD = "claude_state_cwd"
private const val KEY_AWAITING_SESSION = "awaiting_session_id"
private const val KEY_AWAITING_UNTIL = "awaiting_until"
private const val KEY_LAST_STATUS_TITLE = "last_status_title"
private const val KEY_LAST_STATUS_BODY = "last_status_body"
private const val KEY_DAEMON_ALIVE_AT = "daemon_alive_at"

// 데몬 하트비트 간격(60초)보다 넉넉히 여유를 둔 값 — 이 시간 안에 하트비트가
// 안 갱신되면 "꺼짐"으로 판단한다.
private const val DAEMON_ALIVE_GRACE_MS = 150_000L
private const val MAX_HISTORY = 30

// 재연결할 때 놓친 메시지를 다시 받으면서 같은 걸 두 번 처리하지 않으려고
// 최근에 본 ntfy 메시지 ID를 이만큼 기억해둔다.
private const val MAX_SEEN_IDS = 200

/** Claude가 지금 어떤 상태인지 — 앱 첫 화면의 상태 카드에 쓴다. */
object ClaudeState {
    const val UNKNOWN = "unknown"
    const val WORKING = "working"
    const val IDLE = "idle"
    const val ERROR = "error"
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var pairingCode: String
        get() = sp.getString(KEY_PAIRING_CODE, "") ?: ""
        set(value) = sp.edit().putString(KEY_PAIRING_CODE, value.trim()).apply()

    var monitoringEnabled: Boolean
        get() = sp.getBoolean(KEY_MONITORING, false)
        set(value) = sp.edit().putBoolean(KEY_MONITORING, value).apply()

    val askTopic: String get() = "$pairingCode-ask"
    val replyTopic: String get() = "$pairingCode-reply"
    val settingsTopic: String get() = "$pairingCode-settings"
    val promptTopic: String get() = "$pairingCode-prompt"

    // 평범한 요청을 PC 훅이 사람 확인 없이 바로 허용할지 여부.
    // 기본값 true — PC 쪽 기본값과 맞춰둠(둘 다 안 건드리면 자동 허용 켜진 상태).
    var autoApproveEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_APPROVE, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_APPROVE, value).apply()

    // 한 턴이 끝났을 때 PC가 폰에서 다음 지시가 오기를 기다릴지 여부.
    // 기본값 false — 켜져 있으면 매 턴 끝마다 터미널이 그만큼 멈춰 있게 되므로,
    // 사용자가 직접 켤 때만 동작하는 게 맞다(PC 쪽 기본값과 동일).
    var remoteInputEnabled: Boolean
        get() = sp.getBoolean(KEY_REMOTE_INPUT, false)
        set(value) = sp.edit().putBoolean(KEY_REMOTE_INPUT, value).apply()

    // 연결이 끊겼다가 다시 붙을 때 "언제 이후"부터 받아올지. 이걸 안 쓰고 매번
    // 현재 시각으로 다시 붙으면 끊겨 있던 동안 온 알림이 통째로 사라진다.
    var lastEventEpochSec: Long
        get() = sp.getLong(KEY_LAST_EVENT_SEC, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_EVENT_SEC, value).apply()

    fun markEventSeen(eventId: String): Boolean {
        if (eventId.isBlank()) return true
        val raw = sp.getString(KEY_SEEN_IDS, "") ?: ""
        val ids = if (raw.isEmpty()) mutableListOf() else raw.split(",").toMutableList()
        if (ids.contains(eventId)) return false
        ids.add(eventId)
        while (ids.size > MAX_SEEN_IDS) ids.removeAt(0)
        sp.edit().putString(KEY_SEEN_IDS, ids.joinToString(",")).apply()
        return true
    }

    // ---- Claude 상태 / 사용량 ----

    var usageJson: String
        get() = sp.getString(KEY_USAGE_JSON, "") ?: ""
        set(value) = sp.edit().putString(KEY_USAGE_JSON, value).putLong(KEY_USAGE_AT, System.currentTimeMillis()).apply()

    val usageUpdatedAt: Long get() = sp.getLong(KEY_USAGE_AT, 0L)

    val claudeState: String get() = sp.getString(KEY_STATE, ClaudeState.UNKNOWN) ?: ClaudeState.UNKNOWN
    val claudeStateDetail: String get() = sp.getString(KEY_STATE_DETAIL, "") ?: ""
    val claudeStateAt: Long get() = sp.getLong(KEY_STATE_AT, 0L)
    val claudeCwd: String get() = sp.getString(KEY_STATE_CWD, "") ?: ""

    fun setClaudeState(state: String, detail: String, cwd: String?) {
        val editor = sp.edit()
            .putString(KEY_STATE, state)
            .putString(KEY_STATE_DETAIL, detail)
            .putLong(KEY_STATE_AT, System.currentTimeMillis())
        // 작업 폴더는 매 메시지에 실려 오지는 않아서, 값이 있을 때만 갱신한다.
        if (!cwd.isNullOrBlank()) editor.putString(KEY_STATE_CWD, cwd)
        editor.apply()
    }

    // PC가 지금 폰 입력을 기다리는 중인지. 기다리는 동안에만 앱 입력창이
    // "지금 보내면 바로 이어집니다"로 바뀐다.
    var awaitingSessionId: String
        get() = sp.getString(KEY_AWAITING_SESSION, "") ?: ""
        set(value) = sp.edit().putString(KEY_AWAITING_SESSION, value).apply()

    var awaitingUntil: Long
        get() = sp.getLong(KEY_AWAITING_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_AWAITING_UNTIL, value).apply()

    val isAwaitingPrompt: Boolean get() = System.currentTimeMillis() < awaitingUntil

    // 가장 최근 "작업 완료" 알림의 전체 제목/본문 — 상태 카드의 "전체 보기"에서 쓴다.
    var lastStatusTitle: String
        get() = sp.getString(KEY_LAST_STATUS_TITLE, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_STATUS_TITLE, value).apply()

    var lastStatusBody: String
        get() = sp.getString(KEY_LAST_STATUS_BODY, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_STATUS_BODY, value).apply()

    // PC에서 상시 실행 중인 데몬(hook/daemon.js)이 보내는 하트비트 시각.
    // 데몬은 유휴 상태일 때 폰이 보낸 지시를 새 세션으로 "깨워서" 실행해준다.
    var daemonAliveAt: Long
        get() = sp.getLong(KEY_DAEMON_ALIVE_AT, 0L)
        set(value) = sp.edit().putLong(KEY_DAEMON_ALIVE_AT, value).apply()

    val isDaemonAlive: Boolean get() = System.currentTimeMillis() - daemonAliveAt < DAEMON_ALIVE_GRACE_MS

    fun loadHistory(): List<RequestItem> {
        val raw = sp.getString(KEY_HISTORY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val result = mutableListOf<RequestItem>()
        for (i in 0 until arr.length()) {
            result.add(RequestItem.fromJson(arr.getJSONObject(i)))
        }
        return result
    }

    fun saveHistory(items: List<RequestItem>) {
        val trimmed = items.takeLast(MAX_HISTORY)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it.toJson()) }
        sp.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun addOrUpdate(item: RequestItem) {
        val items = loadHistory().toMutableList()
        val idx = items.indexOfFirst { it.id == item.id }
        if (idx >= 0) items[idx] = item else items.add(item)
        saveHistory(items)
    }
}
