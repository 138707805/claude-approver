package com.claudeapprover.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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

    private val notifPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startMonitoring() else startMonitoring() }

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

        binding.pairingCodeInput.setText(prefs.pairingCode)
        binding.connectButton.setOnClickListener { onConnectClicked() }
        binding.pairingCodeInput.setOnLongClickListener {
            copyCode()
            true
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

    private fun onConnectClicked() {
        if (prefs.monitoringEnabled) {
            stopMonitoring()
            return
        }
        val code = binding.pairingCodeInput.text.toString().trim()
        if (code.isBlank()) {
            Toast.makeText(this, getString(R.string.pairing_code_hint), Toast.LENGTH_SHORT).show()
            return
        }
        prefs.pairingCode = code

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startMonitoring()
    }

    private fun startMonitoring() {
        prefs.monitoringEnabled = true
        ContextCompat.startForegroundService(this, Intent(this, RelayService::class.java))
        updateStatusUi()
        requestBatteryOptimizationExemption()
    }

    private fun stopMonitoring() {
        startService(Intent(this, RelayService::class.java).apply { action = RelayService.ACTION_STOP })
        prefs.monitoringEnabled = false
        updateStatusUi()
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {
                // 일부 기기에는 이 화면이 없을 수 있음 — 무시
            }
        }
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

    // 입력창에서 보낸 지시. PC가 마침 기다리는 중이면 바로 이어지고, 아니면
    // ntfy 캐시에 남아 있다가 다음번에 기다리기 시작할 때 전달된다.
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

    private fun copyCode() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("pairing_code", binding.pairingCodeInput.text.toString()))
        Toast.makeText(this, getString(R.string.code_copied), Toast.LENGTH_SHORT).show()
    }

    private fun updateStatusUi() {
        binding.statusText.text = if (prefs.monitoringEnabled) {
            getString(R.string.status_connected)
        } else {
            getString(R.string.status_disconnected)
        }
        binding.connectButton.text = if (prefs.monitoringEnabled) {
            getString(R.string.disconnect_button)
        } else {
            getString(R.string.connect_button)
        }
    }

    // ---- Claude 상태 / 사용량 ----

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

        binding.usageText.text = formatUsage(prefs.usageJson)
        binding.usageDisclaimer.visibility =
            if (prefs.usageJson.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        renderBaseline(prefs.usageJson)
    }

    // "평소 대비" 게이지 — 실제 구독 한도가 아니라 이 폰이 쌓아온 이 컴퓨터의
    // 기록(최근 평균/최대치) 대비 값이다. 훅 쪽에서 표본이 부족하면
    // (최소 3일 · 3블록) null로 보내므로, 그 경우 "데이터 쌓는 중"으로 표시한다.
    private fun renderBaseline(usageJson: String) {
        var todayPct: Int? = null
        var blockPct: Int? = null
        if (usageJson.isNotEmpty()) {
            try {
                val baseline = JSONObject(usageJson).optJSONObject("baseline")
                if (baseline != null) {
                    if (!baseline.isNull("dailyAveragePct")) todayPct = baseline.optInt("dailyAveragePct")
                    if (!baseline.isNull("blockMaxPct")) blockPct = baseline.optInt("blockMaxPct")
                }
            } catch (e: Exception) {
                // 파싱 실패 시 그냥 "데이터 쌓는 중"으로 보여준다
            }
        }
        applyGauge(binding.todayBaselinePctText, binding.todayBaselineBar, todayPct)
        applyGauge(binding.blockBaselinePctText, binding.blockBaselineBar, blockPct)
    }

    private fun applyGauge(pctText: android.widget.TextView, bar: android.widget.ProgressBar, pct: Int?) {
        if (pct == null) {
            pctText.text = getString(R.string.baseline_collecting)
            pctText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            bar.progress = 0
            bar.progressTintList = ContextCompat.getColorStateList(this, R.color.text_secondary)
            return
        }
        val color = when {
            pct >= 130 -> R.color.brand_warning
            pct >= 100 -> R.color.brand_primary
            else -> R.color.brand_accent
        }
        pctText.text = getString(R.string.baseline_pct_format, pct)
        pctText.setTextColor(ContextCompat.getColor(this, color))
        bar.progress = pct.coerceIn(0, 100)
        bar.progressTintList = ContextCompat.getColorStateList(this, color)
    }

    private fun formatUsage(json: String): String {
        if (json.isEmpty()) return getString(R.string.usage_never)
        return try {
            val root = JSONObject(json)
            val lines = mutableListOf<String>()

            root.optJSONObject("today")?.let { today ->
                lines.add(
                    "오늘 · 응답 ${today.optLong("messages")}회 · " +
                        "출력 ${compact(today.optLong("output"))} · " +
                        "입력 ${compact(today.optLong("input") + today.optLong("cacheWrite") + today.optLong("cacheRead"))}"
                )
            }

            val block = root.optJSONObject("block")
            if (block != null) {
                val totals = block.optJSONObject("totals")
                val endsAt = block.optLong("endsAt")
                val remain = endsAt - System.currentTimeMillis()
                lines.add(
                    "현재 5시간 구간 (${clock(block.optLong("startedAt"))}~${clock(endsAt)}" +
                        (if (remain > 0) ", ${duration(remain)} 남음" else "") + ")"
                )
                if (totals != null) {
                    lines.add(
                        "   응답 ${totals.optLong("messages")}회 · 출력 ${compact(totals.optLong("output"))}"
                    )
                }
            } else {
                lines.add("현재 5시간 구간: 진행 중인 사용 없음")
            }

            root.optJSONObject("byModel")?.let { byModel ->
                val parts = byModel.keys().asSequence().map { key ->
                    "${modelLabel(key)} ${byModel.getJSONObject(key).optLong("messages")}회"
                }.toList()
                if (parts.isNotEmpty()) lines.add("모델별 · " + parts.joinToString(" / "))
            }

            val updatedAt = root.optLong("updatedAt")
            if (updatedAt > 0L) lines.add("기준 시각 · ${relativeTime(updatedAt)}")

            lines.joinToString("\n")
        } catch (e: Exception) {
            getString(R.string.usage_never)
        }
    }

    private fun modelLabel(raw: String): String = when {
        raw.contains("opus") -> "Opus"
        raw.contains("sonnet") -> "Sonnet"
        raw.contains("haiku") -> "Haiku"
        raw.contains("fable") -> "Fable"
        else -> raw
    }

    private fun compact(tokens: Long): String = when {
        tokens >= 1_000_000 -> String.format(Locale.KOREA, "%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format(Locale.KOREA, "%.1fK", tokens / 1_000.0)
        else -> tokens.toString()
    }

    private fun clock(epochMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(epochMs))

    private fun duration(ms: Long): String {
        val minutes = ms / 60_000
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}시간 ${m}분" else "${m}분"
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
