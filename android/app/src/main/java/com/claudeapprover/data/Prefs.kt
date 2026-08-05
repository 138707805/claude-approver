package com.claudeapprover.data

import android.content.Context
import org.json.JSONArray

private const val PREFS_NAME = "claude_approver_prefs"
private const val KEY_PAIRING_CODE = "pairing_code"
private const val KEY_MONITORING = "monitoring_enabled"
private const val KEY_HISTORY = "history_json"
private const val MAX_HISTORY = 30

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
