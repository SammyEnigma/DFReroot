package com.polygraphene.df.reroot

import android.content.Context
import java.io.File

object KsudStage {
    const val TAG = "SysPersist"

    const val DEST = "/data/system/dfreroot-ksud"

    fun stageFromAssets(context: Context): String {
        val raw = try {
            context.assets.open("ksud").use { it.readBytes() }
        } catch (t: Throwable) {
            return "[x] assets/ksud unreadable: $t (rebuild with ./build.sh?)\n"
        }
        return stageBytes(raw)
    }

    fun stageBytes(raw: ByteArray): String {
        val s = StringBuilder()
        s.appendLine("[*] ksud asset ${raw.size} bytes (staged verbatim, no patch)")
        try {
            File(DEST).writeBytes(raw)
            android.system.Os.chmod(DEST, 448) // 0700
            s.appendLine("[+] staged $DEST (${raw.size} bytes)")
        } catch (t: Throwable) {
            s.appendLine("[!] $DEST not writable: ${t.javaClass.simpleName}: ${t.message}")
        }
        return s.toString()
    }
}
