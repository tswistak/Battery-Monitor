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
package codes.swistak.batterymonitor.monitoring

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import androidx.annotation.Keep
import codes.swistak.batterymonitor.common.PrivilegedShellExecutor
import kotlin.system.exitProcess

@Keep
class BatteryCurrentUserService : Binder {
    companion object {
        private const val DESCRIPTOR =
            "codes.swistak.batterymonitor.monitoring.BatteryCurrentUserService"
        private const val TRANSACTION_GET_CURRENT = FIRST_CALL_TRANSACTION
        private const val TRANSACTION_DESTROY = 16777115

        @Throws(RemoteException::class)
        fun requestCurrent(binder: IBinder, average: Boolean): Long? {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()

            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(if (average) 1 else 0)
                binder.transact(TRANSACTION_GET_CURRENT, data, reply, 0)
                reply.readException()
                return if (reply.readInt() == 1) reply.readLong() else null
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    constructor()

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context?)

    private fun readCurrent(average: Boolean): Long? {
        val property = if (average) "current_average" else "current_now"
        return BatteryCurrent.readPrivilegedMicroAmps(
            property, PrivilegedShellExecutor()
        ) { null }
    }

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
        if (code != TRANSACTION_GET_CURRENT && code != TRANSACTION_DESTROY) {
            return super.onTransact(code, data, reply, flags)
        }

        data.enforceInterface(DESCRIPTOR)
        if (code == TRANSACTION_DESTROY) {
            destroy()
            return true
        }

        val current = readCurrent(data.readInt() == 1)
        response.writeNoException()
        if (current == null) {
            response.writeInt(0)
        } else {
            response.writeInt(1)
            response.writeLong(current)
        }
        return true
    }
}
