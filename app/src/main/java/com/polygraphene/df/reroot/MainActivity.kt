package com.polygraphene.df.reroot

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var appTitle: TextView
    private lateinit var dmcState: TextView
    private lateinit var d2Check: CheckBox
    private lateinit var status: TextView
    private lateinit var statusChip: TextView
    private lateinit var btnRunAll: Button
    private lateinit var progress: ProgressBar
    private lateinit var log: TextView
    @Volatile private var controller: IBinder? = null
    private val controllerLock = Object()
    private val running = AtomicBoolean(false)
    private var evilReceiver: BroadcastReceiver? = null

    private var runDialogLog: TextView? = null
    private var runDialogScroll: ScrollView? = null
    private var runDialogStatus: TextView? = null
    private var runDialogSpinner: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appTitle = findViewById(R.id.appTitle)
        appTitle.text = "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}"
        dmcState = findViewById(R.id.dmcState)
        d2Check = findViewById(R.id.d2Check)
        d2Check.setOnClickListener {
            val checked = d2Check.isChecked
            runBg {
                val ok = DfrerootConfig.setD2Enabled(checked)
                val actual = DfrerootConfig.isD2Enabled()
                if (ok && actual) {
                    val w = DmcVault.writeAtFlag()
                    val msg = when (w) {
                        is DmcWriteResult.Done -> if (w.wrote) "[D2] AT flag set" else "[D2] AT flag already set"
                        is DmcWriteResult.Skipped -> "[D2] AT flag skipped: ${w.reason}"
                        is DmcWriteResult.Failed -> "[D2] AT flag write failed: ${w.reason}"
                    }
                    append("$msg\n")
                    refreshDmc()
                }
                runOnUiThread {
                    d2Check.isChecked = actual
                    if (ok) append("[D2] D2 fix ${if (actual) "enabled" else "disabled"}\n")
                    else append("[D2] failed to save setting\n")
                }
            }
        }
        runBg {
            val on = DfrerootConfig.isD2Enabled()
            runOnUiThread { d2Check.isChecked = on }
        }
        status = findViewById(R.id.status)
        statusChip = findViewById(R.id.statusChip)
        btnRunAll = findViewById(R.id.btnRunAll)
        progress = findViewById(R.id.progress)
        log = findViewById(R.id.log)

        status.text = myIdentity()
        updateChip()

        btnRunAll.setOnClickListener { runDfAll() }
        findViewById<Button>(R.id.btnTerminal).setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        evilReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    controller = intent.extras?.getBinder("CONTROLLER")
                    append("networkstack CONTROLLER binder received\n")
                } catch (t: Throwable) {
                    append("[x] resolve binder: $t\n")
                } finally {
                    synchronized(controllerLock) { controllerLock.notifyAll() }
                }
            }
        }
        registerReceiver(evilReceiver, IntentFilter(StageReceiver.EVIL_ACTION), Context.RECEIVER_EXPORTED)
        runBg { append(copyKsud()) }
        runBg { refreshDmc() }
    }

    override fun onDestroy() {
        evilReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) { }
        }
        super.onDestroy()
    }

    private fun runDfAll() {
        if (java.io.File("/dev/df").exists()) {
            append("[x] already hooked (/dev/df present). Refusing second run.\n" +
                "    Only hard reboot clears armed hooks.\n")
            return
        }
        if (!running.compareAndSet(false, true)) {
            append("already running\n")
            return
        }
        showRunDialog()
    }

    private fun updateChip() {
        if (java.io.File("/dev/df").exists()) {
            statusChip.text = getString(R.string.chip_hooked)
            statusChip.setBackgroundResource(R.drawable.chip_warn)
        } else {
            statusChip.text = getString(R.string.chip_ready)
            statusChip.setBackgroundResource(R.drawable.chip_ok)
        }
    }

    private fun showRunDialog() {
        btnRunAll.isEnabled = false
        progress.visibility = View.VISIBLE
        val view = layoutInflater.inflate(R.layout.dialog_run, null)
        runDialogStatus = view.findViewById(R.id.dialogStatus)
        runDialogLog = view.findViewById(R.id.dialogLog)
        runDialogScroll = view.findViewById(R.id.dialogScroll)
        runDialogSpinner = view.findViewById(R.id.dialogSpinner)
        setRunResult(active = true, success = false)
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.run_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.run_dialog_close, null)
            .create()
        dlg.setOnDismissListener {
            runDialogLog = null
            runDialogScroll = null
            runDialogStatus = null
            runDialogSpinner = null
        }
        dlg.show()
        runBg {
            var runResult = -1
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
                    if (c.transact(5, p, r, 0)) {
                        runResult = r.readInt()
                        append("\nrunAll done res=$runResult\n")
                    } else append("runAll failed: transact returned false\n")
                } catch (t: Throwable) {
                    append("runAll failed: ${t.message}\n")
                } finally {
                    p.recycle()
                    r.recycle()
                }
            } finally {
                running.set(false)
                val success = runResult == 0
                runOnUiThread {
                    setRunResult(active = false, success = success)
                    btnRunAll.isEnabled = true
                    progress.visibility = View.GONE
                    updateChip()
                }
            }
        }
    }

    private fun refreshDmc() {
        val state = DmcVault.read()
        if (state is DmcResult.Unsupported) append("[DMC] ${state.detail}\n")
        runOnUiThread {
            d2Check.visibility = if (state is DmcResult.Available) View.VISIBLE else View.GONE
            when (state) {
                is DmcResult.Unsupported -> {
                    dmcState.text = getString(R.string.dmc_unsupported)
                    dmcState.setTextColor(getColor(R.color.textSecondary))
                }
                is DmcResult.Available -> {
                    val res = if (state.odinAllowed) R.string.dmc_state_unlocked else R.string.dmc_state_locked
                    dmcState.text = getString(res, state.lock, state.maint, state.at)
                    dmcState.setTextColor(getColor(if (state.odinAllowed) R.color.accent else R.color.amber))
                }
            }
        }
    }

    private fun setRunResult(active: Boolean, success: Boolean) {
        val st = runDialogStatus ?: return
        runDialogSpinner?.visibility = if (active) View.VISIBLE else View.GONE
        when {
            active -> {
                st.text = getString(R.string.run_running)
                st.setTextColor(Color.DKGRAY)
            }
            success -> {
                st.text = getString(R.string.run_success)
                st.setTextColor(Color.parseColor("#1B8A2E"))
            }
            else -> {
                st.text = getString(R.string.run_failed)
                st.setTextColor(Color.parseColor("#C62828"))
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
        val line = s + if (s.endsWith("\n")) "" else "\n"
        runOnUiThread {
            log.append(line)
            runDialogLog?.append(line)
            runDialogScroll?.post { runDialogScroll?.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    companion object {
        const val TAG = "DFReroot"
    }
}
