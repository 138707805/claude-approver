package com.claudeapprover.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.claudeapprover.R
import com.claudeapprover.data.ClaudeState
import com.claudeapprover.data.Prefs
import com.claudeapprover.data.RequestItem
import com.claudeapprover.data.RequestStatus
import com.claudeapprover.databinding.ActivityMainBinding
import com.claudeapprover.databinding.ItemHistoryBinding
import com.claudeapprover.net.NtfyClient
import com.claudeapprover.service.RelayService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val historyUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderHistory()
            renderStateAndUsage()
        }
    }

    // 방금 허용/거부를 눌러 아직 PC로 확정 전송되지 않은 요청 하나(정정 가능 상태).
    // 실제 커밋 타이밍은 RelayService가 관리한다. 상태가 바뀌면 ACTION_HISTORY_UPDATED
    // 브로드캐스트가 오므로 여기서 따로 타이머를 돌 필요는 없다.
    private var stagedRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.pairingBannerButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.autoApproveSwitch.isChecked = prefs.autoApproveEnabled
        binding.autoApproveSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoApproveEnabled = isChecked
            publishSettings()
        }

        binding.remoteInputSwitch.isChecked = prefs.remoteInputEnabled
        binding.remoteInputSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.remoteInputEnabled = isChecked
            publishSettings()
        }

        binding.promptSendButton.setOnClickListener { sendPrompt() }
        binding.viewFullStatusText.setOnClickListener { showStatusDetailDialog() }

        updateStatusUi()
        renderStateAndUsage()
        renderHistory()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RelayService.ACTION_HISTORY_UPDATED)
        ContextCompat.registerReceiver(this, historyUpdatedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(historyUpdatedReceiver)
    }

    override fun onResume() {
        super.onResume()
        updateStatusUi()
        renderStateAndUsage()
        renderHistory()
    }

    // PC 훅은 자기 혼자 계속 켜져 있는 게 아니라 매번 새로 실행되기 때문에, 폰이
    // 바꾼 설정을 알려면 ntfy의 settingsTopic에서 최신 값을 읽어야 한다. 여기서는
    // 그 값을 publish만 해두면 되고(스트리밍 서비스가 없어도 앱 안에서 바로 전송
    // 가능하도록 백그라운드 스레드에서 처리), PC 쪽이 요청이 들어올 때마다 가져간다.
    private fun publishSettings() {
        if (prefs.pairingCode.isBlank()) return
        val topic = prefs.settingsTopic
        val auto = prefs.autoApproveEnabled
        val remote = prefs.remoteInputEnabled
        thread {
            val body = JSONObject()
                .put("autoApproveMode", auto)
                .put("remoteInputMode", remote)
                .toString()
            NtfyClient.publish(topic, body)
        }
    }

    // 입력창에서 보낸 지시. PC가 마침 기다리는 중이면 바로 이어지고, 유휴 상태에서
    // 데몬이 켜져 있으면 새로 깨워서 시작하고, 둘 다 아니면 ntfy 캐시에 남아 있다가
    // 다음번에 기다리기 시작할 때 전달된다.
    private fun sendPrompt() {
        val text = binding.promptInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, getString(R.string.prompt_empty), Toast.LENGTH_SHORT).show()
            return
        }
        if (prefs.pairingCode.isBlank()) {
            Toast.makeText(this, getString(R.string.pairing_code_hint), Toast.LENGTH_SHORT).show()
            return
        }
        startService(Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_SEND_PROMPT
            putExtra(RelayService.EXTRA_PROMPT_TEXT, text)
            putExtra(RelayService.EXTRA_SESSION_ID, prefs.awaitingSessionId)
        })
        binding.promptInput.setText("")
        Toast.makeText(this, getString(R.string.prompt_sent), Toast.LENGTH_SHORT).show()
    }

    private fun updateStatusUi() {
        binding.statusText.text = if (prefs.monitoringEnabled) {
            getString(R.string.status_connected)
        } else {
            getString(R.string.status_disconnected)
        }
        binding.pairingBanner.visibility =
            if (prefs.pairingCode.isBlank()) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ---- Claude 상태 / 다음 지시 카드 ----

    private fun renderStateAndUsage() {
        val (label, color) = when (prefs.claudeState) {
            ClaudeState.WORKING -> getString(R.string.state_working) to R.color.brand_primary
            ClaudeState.IDLE -> getString(R.string.state_idle) to R.color.brand_accent
            ClaudeState.ERROR -> getString(R.string.state_error) to R.color.brand_deny
            else -> getString(R.string.state_unknown) to R.color.text_secondary
        }
        binding.stateBadge.text = label
        binding.stateBadge.setTextColor(ContextCompat.getColor(this, color))

        val detail = prefs.claudeStateDetail
        val at = prefs.claudeStateAt
        val cwd = prefs.claudeCwd
        binding.stateDetail.text = buildString {
            if (detail.isNotEmpty()) append(detail)
            if (at > 0L) {
                if (isNotEmpty()) append("\n")
                append(relativeTime(at))
            }
            if (cwd.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(cwd.substringAfterLast('/'))
            }
        }

        binding.promptWaitingText.text = if (prefs.isAwaitingPrompt) {
            getString(R.string.prompt_waiting)
        } else {
            getString(R.string.prompt_not_waiting)
        }
        binding.promptWaitingText.setTextColor(
            ContextCompat.getColor(this, if (prefs.isAwaitingPrompt) R.color.brand_accent else R.color.text_secondary)
        )
        renderDaemonStatus()

        binding.viewFullStatusText.visibility =
            if (prefs.lastStatusBody.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    // 데몬(상시 원격 실행 프로그램)이 살아있는지에 따라 "지금 지시를 보내면
    // 무슨 일이 일어나는지"를 안내한다 — isAwaitingPrompt가 우선.
    private fun renderDaemonStatus() {
        val text: String
        val colorRes: Int
        when {
            prefs.isAwaitingPrompt -> return // promptWaitingText가 이미 안내함, 중복 표시 안 함
            prefs.isDaemonAlive -> {
                text = getString(R.string.daemon_status_alive_idle)
                colorRes = R.color.brand_accent
            }
            else -> {
                text = getString(R.string.daemon_status_offline)
                colorRes = R.color.text_secondary
            }
        }
        binding.daemonStatusText.text = text
        binding.daemonStatusText.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    // ---- 알림 전체 내용 보기 ----

    // 알림/목록에서는 잘려 보이던 내용을 잘림 없이 볼 수 있게 하는 공용 다이얼로그.
    private fun showFullTextDialog(title: String, body: String) {
        val scroll = android.widget.ScrollView(this)
        val text = android.widget.TextView(this).apply {
            setText(body)
            setTextIsSelectable(true)
            textSize = 14f
            setPadding(48, 24, 48, 24)
        }
        scroll.addView(text)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(getString(R.string.detail_dialog_close), null)
            .show()
    }

    private fun showStatusDetailDialog() {
        if (prefs.lastStatusBody.isEmpty()) return
        showFullTextDialog(prefs.lastStatusTitle.ifEmpty { "작업 완료" }, prefs.lastStatusBody)
    }

    private fun showDetailDialog(item: RequestItem) {
        val meta = getString(
            R.string.detail_dialog_meta_format,
            item.tool.ifEmpty { "-" },
            item.cwd?.ifEmpty { "-" } ?: "-",
            SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(item.timestamp))
        )
        showFullTextDialog(item.title, "${item.body}\n\n$meta")
    }

    private fun relativeTime(epochMs: Long): String {
        val diff = System.currentTimeMillis() - epochMs
        return when {
            diff < 60_000 -> "방금"
            diff < 3_600_000 -> "${diff / 60_000}분 전"
            diff < 86_400_000 -> "${diff / 3_600_000}시간 전"
            else -> SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(epochMs))
        }
    }

    // ---- 최근 요청 목록 ----

    private fun renderHistory() {
        val items = prefs.loadHistory().sortedByDescending { it.timestamp }
        binding.historyContainer.removeAllViews()
        binding.emptyHistoryText.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        val inflater = LayoutInflater.from(this)
        val replyTopic = prefs.replyTopic
        items.forEach { item ->
            val itemBinding = ItemHistoryBinding.inflate(inflater, binding.historyContainer, false)
            itemBinding.root.tag = item.id
            itemBinding.root.setOnClickListener { showDetailDialog(item) }
            itemBinding.itemTitle.text = item.title
            itemBinding.itemBody.text = item.body
            itemBinding.itemStatus.text = statusLabel(item)
            itemBinding.itemStatus.setTextColor(statusColor(item.status))

            when {
                item.status == RequestStatus.PENDING && item.id == stagedRequestId -> {
                    itemBinding.itemActionRow.visibility = android.view.View.GONE
                    itemBinding.itemUndoRow.visibility = android.view.View.VISIBLE
                    itemBinding.itemUndoText.text = getString(R.string.staged_undo_hint)
                    itemBinding.itemUndoButton.setOnClickListener { undoStaged(item.id) }
                }
                item.status == RequestStatus.PENDING -> {
                    itemBinding.itemActionRow.visibility = android.view.View.VISIBLE
                    itemBinding.itemUndoRow.visibility = android.view.View.GONE
                    itemBinding.itemAllowButton.setOnClickListener {
                        stageDecision(item.id, replyTopic, "allow")
                    }
                    itemBinding.itemDenyButton.setOnClickListener {
                        stageDecision(item.id, replyTopic, "deny")
                    }
                }
                else -> {
                    itemBinding.itemActionRow.visibility = android.view.View.GONE
                    itemBinding.itemUndoRow.visibility = android.view.View.GONE
                }
            }

            binding.historyContainer.addView(itemBinding.root)
        }
    }

    private fun stageDecision(requestId: String, replyTopic: String, decision: String) {
        val intent = Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_STAGE_DECISION
            putExtra(RelayService.EXTRA_REQUEST_ID, requestId)
            putExtra(RelayService.EXTRA_DECISION, decision)
            putExtra(RelayService.EXTRA_REPLY_TOPIC, replyTopic)
        }
        startService(intent)
        stagedRequestId = requestId
        renderHistory()
    }

    private fun undoStaged(requestId: String) {
        val intent = Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_UNDO_DECISION
            putExtra(RelayService.EXTRA_REQUEST_ID, requestId)
        }
        startService(intent)
        if (stagedRequestId == requestId) stagedRequestId = null
        renderHistory()
    }

    private fun statusLabel(item: RequestItem): String = when (item.status) {
        RequestStatus.PENDING -> "대기 중"
        RequestStatus.ALLOWED -> if (item.auto) "자동 허용됨" else "허용됨"
        RequestStatus.DENIED -> "거부됨"
        RequestStatus.ATTENTION -> "컴퓨터에서 확인 필요"
        RequestStatus.EXPIRED -> "만료됨"
    }

    private fun statusColor(status: RequestStatus): Int = when (status) {
        RequestStatus.ALLOWED -> ContextCompat.getColor(this, R.color.brand_accent)
        RequestStatus.DENIED -> ContextCompat.getColor(this, R.color.brand_deny)
        RequestStatus.ATTENTION -> ContextCompat.getColor(this, R.color.brand_warning)
        else -> ContextCompat.getColor(this, R.color.text_secondary)
    }
}
