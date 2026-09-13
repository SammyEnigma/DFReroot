package com.polygraphene.df.reroot

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    @Volatile private var controller: IBinder? = null
    private val controllerLock = Object()
    private val running = AtomicBoolean(false)
    private var evilReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        log = findViewById(R.id.log)

        status.text = myIdentity()

        findViewById<Button>(R.id.btnRunAll).setOnClickListener { runDfAll() }
        evilReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    controller = intent.extras?.getBinder("CONTROLLER")
                    append("networkstack CONTROLLER binder received\n")
                } catch (t: Throwable) {
                    append("[x] resolve binder: $t\n")
                } finally {
                    // Wake the background waiter in runDfAll (if any).
                    synchronized(controllerLock) { controllerLock.notifyAll() }
                }
            }
        }
        registerReceiver(evilReceiver, IntentFilter(StageReceiver.EVIL_ACTION), Context.RECEIVER_EXPORTED)
        runBg { append(copyKsud()) }
    }

    override fun onDestroy() {
        evilReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { }
        }
        super.onDestroy()
    }

    private fun runDfAll() {
        // Check if already hooked
        if (java.io.File("/dev/df").exists()) {
            append("[x] already hooked (/dev/df present). Refusing second run.\n" +
                "    Only hard reboot clears armed hooks.\n")
            return
        }
        if (!running.compareAndSet(false, true)) {
            append("already running\n")
            return
        }
        runBg {
            try {
                append(StageHop.hopToNetworkStack(this))
                val c = awaitController(timeoutMs = 30_000) ?: run {
                    append("[x] no CONTROLLER within 30s " +
                        "(hop failed or network_stack too slow; see logcat)\n")
                    return@runBg
                }
                val p = Parcel.obtain()
                val r = Parcel.obtain()
                val reporter = object : Binder() {
                    override fun onTransact(
                        code: Int, data: Parcel, reply: Parcel?, flags: Int
                    ): Boolean {
                        try {
                            append(data.readString() ?: "")
                        } catch (t: Throwable) {
                            Log.e(TAG, "reporter recv failed", t)
                        }
                        return true
                    }
                }
                p.writeStrongBinder(reporter)
                try {
                    if (c.transact(5, p, r, 0)) append("\nrunAll done res=${r.readInt()}\n")
                    else append("runAll failed: transact returned false\n")
                } catch (t: Throwable) {
                    append("runAll failed: ${t.message}\n")
                } finally {
                    p.recycle()
                    r.recycle()
                }
            } finally {
                running.set(false)
            }
        }
    }

    private fun awaitController(timeoutMs: Long): IBinder? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        synchronized(controllerLock) {
            var c = controller
            while (c == null) {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) break
                append("[*] waiting for CONTROLLER... (${left / 1000}s left)\n")
                try {
                    controllerLock.wait(minOf(left, 5_000))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                c = controller
            }
            return c
        }
    }

    private fun copyKsud(): String {
        val fromAssets = try {
            KsudStage.stageFromAssets(this)
        } catch (t: Throwable) {
            "[x] asset staging failed: $t\n"
        }
        if (!fromAssets.contains("[+] staged")) {
            try {
                val app = packageManager.getApplicationInfo("me.weishu.kernelsu", 0)
                val raw = java.io.File(app.nativeLibraryDir, "libksud.so").readBytes()
                return fromAssets + "[*] trying manager lib as source\n" +
                    KsudStage.stageBytes(raw)
            } catch (t: Throwable) {
                return fromAssets + "[x] manager fallback failed: ${t.message}\n"
            }
        }
        return fromAssets
    }

    private fun myIdentity(): String {
        val pid = Process.myPid()
        val uid = Process.myUid()
        val procName = try {
            applicationInfo.processName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        val ctx = try {
            java.io.File("/proc/self/attr/current").readText().trim().trim('\u0000')
        } catch (e: Exception) {
            "?"
        }
        return "pid=$pid uid=$uid\nproc=$procName\nctx=$ctx"
    }

    private fun runBg(block: () -> Unit) {
        Thread {
            try { block() } catch (e: Exception) { append("[x] $e\n") }
        }.start()
    }

    private fun append(s: String) {
        runOnUiThread { log.append(s + if (s.endsWith("\n")) "" else "\n") }
    }

    companion object {
        const val TAG = "DFReroot"
    }
}
