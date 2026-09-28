package com.polygraphene.df.reroot

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel

class ControllerService : Service() {
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) {
                try {
                    AutoRoot.onEvilReceived(data.readStrongBinder())
                } catch (_: Exception) {
                }
                reply?.writeInt(1)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
}
