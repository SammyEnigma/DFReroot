package com.polygraphene.df.reroot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AutoRootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                if (!DfrerootConfig.isAutoRootEnabled()) return@Thread
                val bootId = AutoRoot.readBootId()
                if (bootId == null) {
                    Log.i(AutoRoot.TAG, "auto root skipped: no boot id")
                    return@Thread
                }
                val saved = DfrerootConfig.getLastBootId()
                DfrerootConfig.setLastBootId(bootId)
                if (AutoRoot.isFlagSet()) {
                    Log.i(AutoRoot.TAG, "auto root skipped: running flag present")
                    return@Thread
                }
                if (saved != null && saved == bootId) {
                    Log.i(AutoRoot.TAG, "auto root skipped: soft reboot")
                    return@Thread
                }
                if (!AutoRoot.rootRunning.compareAndSet(false, true)) {
                    Log.i(AutoRoot.TAG, "auto root skipped: already running")
                    return@Thread
                }
                try {
                    if (!AutoRoot.setFlag()) {
                        Log.w(AutoRoot.TAG, "auto root skipped: flag write failed")
                        return@Thread
                    }
                    AutoRoot.syncNow()
                    val res = AutoRoot.runRoot(context.applicationContext, {
                        Log.i(AutoRoot.TAG, "root: $it")
                    })
                    Thread.sleep(10_000)
                    AutoRoot.clearFlag()
                    Log.i(AutoRoot.TAG, "auto root done res=$res")
                } finally {
                    AutoRoot.rootRunning.set(false)
                }
            } catch (t: Throwable) {
                Log.w(AutoRoot.TAG, "auto root error: $t")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
