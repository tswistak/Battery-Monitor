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

package codes.swistak.batterymonitor;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import androidx.annotation.Keep;

public class AdvancedStatsUserService extends Binder {
    private static final String DESCRIPTOR = "codes.swistak.batterymonitor.AdvancedStatsUserService";
    private static final int TRANSACTION_GET_SNAPSHOT = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TRANSACTION_DESTROY = 16777115;
    private final Context context;

    public AdvancedStatsUserService() {
        this.context = null;
    }

    @Keep
    public AdvancedStatsUserService(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
    }

    private Bundle getSnapshot() {
        return AdvancedBatteryStatsCollector.collect(
                new AdvancedBatteryStatsCollector.PrivilegedShellExecutor(),
                AdvancedBatterySnapshot.ACCESS_SHIZUKU,
                Process.myUid(),
                context,
                true
        ).toBundle();
    }

    private void destroy() {
        System.exit(0);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }

        if (code == TRANSACTION_GET_SNAPSHOT || code == TRANSACTION_DESTROY)
            data.enforceInterface(DESCRIPTOR);

        if (code == TRANSACTION_GET_SNAPSHOT) {
            reply.writeNoException();
            reply.writeBundle(getSnapshot());
            return true;
        }

        if (code == TRANSACTION_DESTROY) {
            destroy();
            return true;
        }

        return super.onTransact(code, data, reply, flags);
    }

    static Bundle requestSnapshot(IBinder binder) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();

        try {
            data.writeInterfaceToken(DESCRIPTOR);
            binder.transact(TRANSACTION_GET_SNAPSHOT, data, reply, 0);
            reply.readException();
            return reply.readBundle(AdvancedBatterySnapshot.class.getClassLoader());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
