package com.claudeapprover.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReplyActionReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_DECISION = "decision"
        const val EXTRA_REPLY_TOPIC = "reply_topic"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val decision = intent.getStringExtra(EXTRA_DECISION) ?: return
        val replyTopic = intent.getStringExtra(EXTRA_REPLY_TOPIC) ?: return

        val pendingResult = goAsync()
        ResponseHelper.respond(context, requestId, replyTopic, decision) {
            pendingResult.finish()
        }
    }
}
