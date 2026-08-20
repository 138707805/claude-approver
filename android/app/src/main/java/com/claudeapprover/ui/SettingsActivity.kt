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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.claudeapprover.R
import com.claudeapprover.data.Prefs
import com.claudeapprover.databinding.ActivitySettingsBinding
import com.claudeapprover.service.RelayService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 페어링 코드 연결/해제와 사용량("평소 대비" 게이지 포함) 표시 — 메인 화면에서
// 자주 안 만지는 두 카드만 여기로 옮겨왔다. 감시 시작/중지 로직 자체는 원래
// MainActivity에 있던 것과 동일하게 그대로 옮김.
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val notifPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startMonitoring() else startMonitoring() }

    private val historyUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderUsage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.backButton.setOnClickListener { finish() }

        binding.pairingCodeInput.setText(prefs.pairingCode)
        binding.connectButton.setOnClickListener { onConnectClicked() }
        binding.pairingCodeInput.setOnLongClickListener {
            copyCode()
            true
        }

        updateStatusUi()
        renderUsage()
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
        renderUsage()
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
        binding.settingsStatusText.text = if (prefs.monitoringEnabled) {
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

    // ---- 사용량 / 평소 대비 게이지 (MainActivity에 있던 것과 동일한 로직) ----

    private fun renderUsage() {
        binding.usageText.text = formatUsage(prefs.usageJson)
        binding.usageDisclaimer.visibility =
            if (prefs.usageJson.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        renderBaseline(prefs.usageJson)
    }

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
}
