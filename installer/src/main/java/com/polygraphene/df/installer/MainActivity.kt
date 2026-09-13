package com.polygraphene.df.installer

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.io.File

/**
 * DFInstaller GUI.
 *
 * Prerequisite: temporary root (e.g. ghostlock) with working `su`.
 * Flow (mostly automatic):
 *  - On launch: root check, then key-injection status check.
 *  - [Inject] inserts the DFReroot signing key into android.uid.system
 *    pastSigs (disabled while already injected; the backend also refuses).
 *  - [Soft reboot] restarts system_server so PMS re-reads packages.xml;
 *    no kernel reboot needed.
 *  - [Install DFReroot] (passes as system sharedUserId after the reboot).
 *  - [Uninstall key] removes our key again (enabled only while injected).
 *  - From then on, use DFReroot's dirtyfrag for root.
 *
 * The DFReroot APK comes only from this APK's bundled asset (df_reroot.apk).
 * Build with `./build.sh` to bundle it.
 */
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
        // Hidden (kept wired for debugging): Dump / Dry-run.
        findViewById<Button>(R.id.btnDump).setOnClickListener {
            runBg { append(runAppProcess("--dump")) }
        }
        findViewById<Button>(R.id.btnDryRun).setOnClickListener {
            runBg { append(runAppProcess("--dry-run")) }
        }
        btnInject.setOnClickListener {
            runBg {
                append(runAppProcess(""))
                refreshAll()
            }
        }
        btnUninstall.setOnClickListener {
            runBg {
                append(runAppProcess("--uninstall"))
                refreshAll()
            }
        }
        btnReboot.setOnClickListener {
            runBg {
                // Soft reboot: killing system_server makes zygote restart
                // the whole framework (incl. PMS re-reading packages.xml)
                // without rebooting the kernel.
                append("$ su -c 'kill $(pidof system_server)'")
                append(execSu("kill $(pidof system_server)"))
            }
        }
        btnInstall.setOnClickListener {
            runBg { append(installDfreroot()) }
        }

        // Automatic on launch: root check, then injection status check.
        runBg { refreshAll() }
    }

    /**
     * Root check + injection-status check + button state update.
     * Runs off the UI thread; touches views only via runOnUiThread.
     */
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
        // Per-target lines look like "[check] android.uid.system injected=true".
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

    /**
     * Runs InjectMain (app_process) via su.
     * CLASSPATH is this APK (contains the InjectMain class); the DFReroot
     * signing key is read here via PackageManager (v1/v2/v3 agnostic) and
     * passed as --keyhex, so InjectMain never parses APK signatures itself.
     */
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

    /** DFReroot signing key hex from the bundled asset (no root needed). */
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

    /**
     * Extracts the DFReroot APK from the bundled asset. The only source.
     * Throws when the asset is not bundled
     * (bundle build with `./build.sh` is required).
     */
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

    /**
     * Installs DFReroot as system sharedUserId (call after soft reboot).
     * Uses only `pm install` via su: the PackageInstaller session API ends
     * up in "User action required" and its confirmation activity is
     * background-launch-blocked, so the session never completes.
     */
    private fun installDfreroot(): String {
        val s = StringBuilder()
        val apk: File = try {
            File(resolveCertApk())
        } catch (e: Exception) {
            s.appendLine("[x] ${e.message}")
            return s.toString()
        }
        s.appendLine("[*] installing ${apk.absolutePath} (${apk.length()} bytes)")
        s.appendLine("$ su -c pm install -r '${apk.absolutePath}'")
        s.append(execSu("pm install -r '${apk.absolutePath}'"))
        return s.toString()
    }

    /** Runs `su -c <script>`, returns stdout+stderr. Explains when su is missing. */
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

    private fun append(s: String) {
        runOnUiThread { log.append(s + if (s.endsWith("\n")) "" else "\n") }
    }
}
