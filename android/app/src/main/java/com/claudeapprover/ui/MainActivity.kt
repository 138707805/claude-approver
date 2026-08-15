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
import android.os.CountDownTimer
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.claudeapprover.R
import com.claudeapprover.data.Prefs
import com.claudeapprover.data.RequestItem
import com.claudeapprover.data.RequestStatus
import com.claudeapprover.databinding.ActivityMainBinding
import com.claudeapprover.databinding.ItemHistoryBinding
import com.claudeapprover.service.RelayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val notifPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startMonitoring() else startMonitoring() }

    private val historyUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = renderHistory()
    }

    // 방금 앱 안에서 허용/거부를 눌러 "취소 가능" 상태로 보여주고 있는 요청 하나.
    // 실제 커밋 타이밍은 RelayService가 관리하고, 여기 카운트다운은 화면 표시용이다.
    private var stagedRequestId: String? = null
    private var stagedCountdown: CountDownTimer? = null

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

        updateStatusUi()
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

        stagedCountdown?.cancel()
        stagedRequestId = requestId
        renderHistory()
        stagedCountdown = object : CountDownTimer(RelayService.UNDO_WINDOW_MS, 1000) {
            override fun onTick(msLeft: Long) = updateUndoCountdownText(requestId, decision, msLeft)
            override fun onFinish() {
                if (stagedRequestId == requestId) stagedRequestId = null
                renderHistory()
            }
        }.start()
        updateUndoCountdownText(requestId, decision, RelayService.UNDO_WINDOW_MS)
    }

    private fun undoStaged(requestId: String) {
        val intent = Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_UNDO_DECISION
            putExtra(RelayService.EXTRA_REQUEST_ID, requestId)
        }
        startService(intent)
        stagedCountdown?.cancel()
        if (stagedRequestId == requestId) stagedRequestId = null
        renderHistory()
    }

    private fun updateUndoCountdownText(requestId: String, decision: String, msLeft: Long) {
        if (stagedRequestId != requestId) return
        val label = if (decision == "allow") getString(R.string.allow) else getString(R.string.deny)
        val secondsLeft = (msLeft / 1000).coerceAtLeast(1)
        val row = (0 until binding.historyContainer.childCount)
            .map { binding.historyContainer.getChildAt(it) }
            .firstOrNull { it.tag == requestId } ?: return
        row.findViewById<TextView>(R.id.itemUndoText)?.text = "${secondsLeft}초 후 $label 확정"
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
