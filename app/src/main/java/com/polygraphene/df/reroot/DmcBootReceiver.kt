package com.polygraphene.df.reroot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DmcBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Log.i(TAG, "[DMC] Received " + intent.action)
        Thread {
            try {
                if (!DfrerootConfig.isD2Enabled()) {
                    Log.i(TAG, "DMC at flag skipped: disabled")
                    return@Thread
                }
                when (val r = DmcVault.writeAtFlag()) {
                    is DmcWriteResult.Done -> Log.i(TAG, "DMC at flag wrote=${r.wrote}")
                    is DmcWriteResult.Skipped -> Log.i(TAG, "DMC at flag skipped: ${r.reason}")
                    is DmcWriteResult.Failed -> Log.w(TAG, "DMC at flag failed: ${r.reason}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "DMC at flag error: $t")
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val TAG = "DFReroot"
    }
}
