package com.claudeapprover.ui

import android.Manifest
import android.app.Activity
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
        items.forEach { item ->
            val itemBinding = ItemHistoryBinding.inflate(inflater, binding.historyContainer, false)
            itemBinding.itemTitle.text = item.title
            itemBinding.itemBody.text = item.body
            itemBinding.itemStatus.text = statusLabel(item.status)
            itemBinding.itemStatus.setTextColor(statusColor(item.status))
            binding.historyContainer.addView(itemBinding.root)
        }
    }

    private fun statusLabel(status: RequestStatus): String = when (status) {
        RequestStatus.PENDING -> "대기 중"
        RequestStatus.ALLOWED -> "허용됨"
        RequestStatus.DENIED -> "거부됨"
        RequestStatus.EXPIRED -> "만료됨"
    }

    private fun statusColor(status: RequestStatus): Int = when (status) {
        RequestStatus.ALLOWED -> ContextCompat.getColor(this, R.color.brand_accent)
        RequestStatus.DENIED -> ContextCompat.getColor(this, R.color.brand_deny)
        else -> ContextCompat.getColor(this, R.color.text_secondary)
    }
}
