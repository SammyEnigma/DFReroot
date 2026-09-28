package com.polygraphene.df.reroot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class EvilReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StageReceiver.EVIL_ACTION) return
        try {
            AutoRoot.onEvilReceived(intent.extras?.getBinder("CONTROLLER"))
        } catch (_: Exception) {
        }
    }
}
