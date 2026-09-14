package com.polygraphene.df.installer

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var rootState: TextView
    private lateinit var keyState: TextView
    private lateinit var log: TextView
    private lateinit var btnInject: Button
    private lateinit var btnUninstall: Button
    private lateinit var btnReboot: Button
    private lateinit var btnInstall: Button
    private lateinit var btnRoot: Button

    @Volatile private var rooted = false
    @Volatile private var injected = false

    private var dialogLog: TextView? = null
    private var dialogScroll: ScrollView? = null
    private var dialogStatus: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        rootState = findViewById(R.id.rootState)
        keyState = findViewById(R.id.keyState)
        log = findViewById(R.id.log)
        btnInject = findViewById(R.id.btnInject)
        btnUninstall = findViewById(R.id.btnUninstall)
        btnReboot = findViewById(R.id.btnReboot)
        btnInstall = findViewById(R.id.btnInstall)
        btnRoot = findViewById(R.id.btnRoot)
        status.text = myIdentity()

        btnRoot.setOnClickListener { runBg { refreshAll() } }
        findViewById<Button>(R.id.btnDump).setOnClickListener {
            runBg { append(runAppProcess("--dump")) }
        }
        findViewById<Button>(R.id.btnDryRun).setOnClickListener {
            runBg { append(runAppProcess("--dry-run")) }
        }
        btnInject.setOnClickListener { showInjectDialog() }
        btnUninstall.setOnClickListener { showUninstallDialog() }
        btnReboot.setOnClickListener { showRebootConfirm() }
        btnInstall.setOnClickListener { showInstallDialog() }

        runBg { refreshAll() }
    }

    private fun refreshAll() {
        val rootOut = execSu("id")
        rooted = rootOut.contains("uid=0")
        if (!rooted) {
            injected = false
            ui {
                rootState.text = getString(R.string.root_missing)
                keyState.text = getString(R.string.key_unknown)
                updateButtons()
            }
            append("$ su -c id")
            append(rootOut)
            append(getString(R.string.hint_need_root))
            return
        }
        ui { rootState.text = getString(R.string.root_ok) }
        val keyHex: String = try {
            resolveKeyHex()
        } catch (e: Exception) {
            ui {
                keyState.text = getString(R.string.key_unknown)
                updateButtons()
            }
            append("[x] ${e.message}")
            return
        }
        val checkOut = execSu(buildAppProcessCmd(keyHex, "--check"))
        append("$ su -c ...InjectMain --check")
        append(checkOut)
        injected = checkOut.lines().any { l ->
            l.contains("android.uid.system") && l.contains("injected=true")
        }
        ui {
            keyState.text = if (injected) getString(R.string.key_injected)
            else getString(R.string.key_clean)
            updateButtons()
        }
    }

    private fun updateButtons() {
        btnRoot.isEnabled = true
        btnInject.isEnabled = rooted && !injected
        btnUninstall.isEnabled = rooted && injected
        btnReboot.isEnabled = rooted
        btnInstall.isEnabled = rooted
    }

    private fun runAppProcess(mode: String): String {
        val keyHex: String = try {
            resolveKeyHex()
        } catch (e: Exception) {
            return "[x] ${e.message}"
        }
        val cmd = buildAppProcessCmd(keyHex, mode)
        append("$ su -c $cmd")
        return execSu(cmd)
    }

    private fun buildAppProcessCmd(keyHex: String, mode: String): String {
        return buildString {
            append("CLASSPATH='").append(applicationInfo.sourceDir).append("' ")
            append("app_process /system/bin --nice-name=df_inject ")
            append("com.polygraphene.df.installer.InjectMain ")
            append("--keyhex '").append(keyHex).append("' ")
            append("--targets android.uid.system ")
            if (mode.isNotEmpty()) append(mode)
        }
    }

    private fun resolveKeyHex(): String {
        val certApk = try {
            resolveCertApk()
        } catch (e: Exception) {
            throw RuntimeException("${e.message}")
        }
        try {
            return SigKey.fromApk(packageManager, certApk)
        } catch (e: Exception) {
            throw RuntimeException("failed to read DFReroot cert: ${e.message}")
        }
    }

    private fun resolveCertApk(): String {
        try {
            assets.open("df_reroot.apk").use { input ->
                val out = File(filesDir, "df_reroot.apk")
                out.outputStream().use { input.copyTo(it) }
                return out.absolutePath
            }
        } catch (e: Exception) {
            throw RuntimeException(
                "asset df_reroot.apk missing: rebuild with ./build.sh (${e.message})"
            )
        }
    }

    private fun installDfreroot(): String {
        val s = StringBuilder()
        val apk: File = try {
            File(resolveCertApk())
        } catch (e: Exception) {
            s.appendLine("[x] ${e.message}")
            return s.toString()
        }
        val targetUser = Process.myUid() / 100000
        s.appendLine("[*] installing ${apk.absolutePath} (${apk.length()} bytes) for user $targetUser")
        s.appendLine("$ su -c pm install -r --user $targetUser '${apk.absolutePath}'")
        s.append(execSu("pm install -r --user $targetUser '${apk.absolutePath}'"))
        return s.toString()
    }

    private fun execSu(script: String): String {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
            val rc = p.waitFor()
            return buildString {
                if (out.isNotBlank()) append(out.trim()).append("\n")
                if (err.isNotBlank()) append("[stderr] ").append(err.trim()).append("\n")
                append("[rc=$rc]")
            }
        } catch (t: Throwable) {
            return "[x] su exec failed: ${t.message}\n" +
                "    first obtain temporary root (e.g. ghostlock) so su works"
        }
    }

    private fun myIdentity(): String {
        val ctx = try {
            File("/proc/self/attr/current").readText().trim().trim('\u0000')
        } catch (e: Exception) {
            "?"
        }
        return "pid=${Process.myPid()} uid=${Process.myUid()}\n" +
            "pkg=$packageName\nctx=$ctx"
    }

    private fun runBg(block: () -> Unit) {
        Thread {
            try {
                block()
            } catch (e: Exception) {
                append("[x] $e")
            }
        }.start()
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread(block)
    }

    private fun showInjectDialog() {
        showOperationDialog(R.string.inject_dialog_title, { runAppProcess("") }, ::isInjectSuccess, true)
    }

    private fun showUninstallDialog() {
        showOperationDialog(R.string.uninstall_dialog_title, { runAppProcess("--uninstall") }, ::isUninstallSuccess, true)
    }

    private fun showInstallDialog() {
        showOperationDialog(R.string.install_dialog_title, { installDfreroot() }, ::isInstallSuccess, false)
    }

    private fun showRebootConfirm() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reboot_confirm_title)
            .setMessage(R.string.reboot_confirm_message)
            .setPositiveButton(R.string.reboot_confirm_ok) { _, _ ->
                runBg {
                    append("$ su -c 'kill $(pidof system_server)'")
                    append(execSu("kill $(pidof system_server)"))
                }
            }
            .setNegativeButton(R.string.reboot_confirm_cancel, null)
            .show()
    }

    private fun showOperationDialog(titleRes: Int, task: () -> String, isSuccess: (String) -> Boolean, refresh: Boolean) {
        val view = layoutInflater.inflate(R.layout.dialog_inject, null)
        dialogStatus = view.findViewById(R.id.dialogStatus)
        dialogLog = view.findViewById(R.id.dialogLog)
        dialogScroll = view.findViewById(R.id.dialogScroll)
        setDialogResult(running = true, success = false)
        val dlg = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(R.string.inject_dialog_close, null)
            .create()
        dlg.setOnDismissListener {
            dialogLog = null
            dialogScroll = null
            dialogStatus = null
        }
        dlg.show()
        runBg {
            val result = task()
            append(result)
            if (refresh) refreshAll()
            val success = isSuccess(result)
            ui { setDialogResult(running = false, success = success) }
        }
    }

    private fun isInjectSuccess(result: String): Boolean {
        return result.contains("[+] DONE") &&
            !result.contains("[x] FAILED")
    }

    private fun isUninstallSuccess(result: String): Boolean {
        return (result.contains("[+] DONE") || result.contains("nothing to write")) &&
            !result.contains("[x] FAILED")
    }

    private fun isInstallSuccess(result: String): Boolean {
        return result.contains("Success") &&
            !result.contains("Failure") &&
            result.contains("[rc=0]")
    }

    private fun setDialogResult(running: Boolean, success: Boolean) {
        val st = dialogStatus ?: return
        when {
            running -> {
                st.text = getString(R.string.inject_running)
                st.setTextColor(Color.DKGRAY)
            }
            success -> {
                st.text = getString(R.string.inject_success)
                st.setTextColor(Color.parseColor("#1B8A2E"))
            }
            else -> {
                st.text = getString(R.string.inject_failed)
                st.setTextColor(Color.parseColor("#C62828"))
            }
        }
    }

    private fun append(s: String) {
        val line = s + if (s.endsWith("\n")) "" else "\n"
        runOnUiThread {
            log.append(line)
            dialogLog?.append(line)
            dialogScroll?.post { dialogScroll?.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
