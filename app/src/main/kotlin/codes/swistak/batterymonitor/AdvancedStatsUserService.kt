/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
*/
package codes.swistak.batterymonitor

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import androidx.annotation.Keep
import codes.swistak.batterymonitor.AdvancedBatteryStatsCollector.PrivilegedShellExecutor
import kotlin.system.exitProcess

class AdvancedStatsUserService : Binder {
    companion object {
        private const val DESCRIPTOR = "codes.swistak.batterymonitor.AdvancedStatsUserService"
        private const val TRANSACTION_GET_SNAPSHOT = FIRST_CALL_TRANSACTION
        private const val TRANSACTION_DESTROY = 16777115

        @Throws(RemoteException::class)
        fun requestSnapshot(binder: IBinder): Bundle? {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()

            try {
                data.writeInterfaceToken(DESCRIPTOR)
                binder.transact(TRANSACTION_GET_SNAPSHOT, data, reply, 0)
                reply.readException()
                return reply.readBundle(AdvancedBatterySnapshot::class.java.getClassLoader())
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    private val context: Context?

    constructor() {
        this.context = null
    }

    @Keep
    constructor(context: Context?) {
        this.context = context?.applicationContext
    }

    private val snapshot: Bundle
        get() = AdvancedBatteryStatsCollector.collect(
            PrivilegedShellExecutor(),
            AdvancedBatterySnapshot.ACCESS_SHIZUKU,
            Process.myUid(),
            context,
            true
        ).toBundle()

    private fun destroy() {
        exitProcess(0)
    }

    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val response = reply ?: return false
        if (code == INTERFACE_TRANSACTION) {
            response.writeString(DESCRIPTOR)
            return true
        }

        if (code == TRANSACTION_GET_SNAPSHOT || code == TRANSACTION_DESTROY) data.enforceInterface(
            DESCRIPTOR
        )

        if (code == TRANSACTION_GET_SNAPSHOT) {
            response.writeNoException()
            response.writeBundle(this.snapshot)
            return true
        }

        if (code == TRANSACTION_DESTROY) {
            destroy()
            return true
        }

        return super.onTransact(code, data, reply, flags)
    }
}
