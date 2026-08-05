package com.claudeapprover.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.claudeapprover.data.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.monitoringEnabled && prefs.pairingCode.isNotBlank()) {
            ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
        }
    }
}
