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
package codes.swistak.batterymonitor.privileged

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import androidx.annotation.Keep
import codes.swistak.batterymonitor.common.PrivilegedShellExecutor
import kotlin.system.exitProcess

@Keep
class PrivilegedCommandUserService : Binder {
    companion object {
        private const val DESCRIPTOR =
            "codes.swistak.batterymonitor.privileged.PrivilegedCommandUserService"
        private const val TRANSACTION_RUN_COMMAND = FIRST_CALL_TRANSACTION
        private const val TRANSACTION_DESTROY = 16777115

        @Throws(RemoteException::class)
        fun requestCommand(binder: IBinder, command: String): String? {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()

            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(command)
                binder.transact(TRANSACTION_RUN_COMMAND, data, reply, 0)
                reply.readException()
                return reply.readString()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    constructor()

    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context?)

    private fun runCommand(command: String): String? = PrivilegedShellExecutor().run(command)

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
        if (code !in setOf(TRANSACTION_RUN_COMMAND, TRANSACTION_DESTROY)) {
            return super.onTransact(code, data, reply, flags)
        }

        data.enforceInterface(DESCRIPTOR)
        if (code == TRANSACTION_DESTROY) {
            destroy()
            return true
        }

        response.writeNoException()
        response.writeString(runCommand(data.readString().orEmpty()))
        return true
    }
}
