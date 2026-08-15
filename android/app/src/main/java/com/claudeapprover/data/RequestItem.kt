package com.claudeapprover.data

import org.json.JSONObject

enum class RequestStatus { PENDING, ALLOWED, DENIED, ATTENTION, EXPIRED }

data class RequestItem(
    val id: String,
    val tool: String,
    val title: String,
    val body: String,
    val cwd: String?,
    val timestamp: Long,
    var status: RequestStatus,
    val auto: Boolean = false // 사람이 누른 게 아니라 자동으로 결정된 요청인지
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("tool", tool)
        .put("title", title)
        .put("body", body)
        .put("cwd", cwd ?: "")
        .put("timestamp", timestamp)
        .put("status", status.name)
        .put("auto", auto)

    companion object {
        fun fromJson(o: JSONObject): RequestItem = RequestItem(
            id = o.getString("id"),
            tool = o.optString("tool"),
            title = o.optString("title"),
            body = o.optString("body"),
            cwd = o.optString("cwd").ifEmpty { null },
            timestamp = o.optLong("timestamp"),
            status = try {
                RequestStatus.valueOf(o.optString("status"))
            } catch (e: Exception) {
                RequestStatus.EXPIRED
            },
            auto = o.optBoolean("auto", false)
        )
    }
}
