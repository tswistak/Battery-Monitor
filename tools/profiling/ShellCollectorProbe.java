/*
 * Copyright (c) 2026 Tomasz Świstak
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package codes.swistak.batterymonitor.profiling;

import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import codes.swistak.batterymonitor.advancedstats.AdvancedBatterySnapshot;
import codes.swistak.batterymonitor.privileged.PrivilegedAccess;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ShellCollectorProbe {
    private ShellCollectorProbe() {
    }

    private static JSONObject bundleJson(Bundle bundle) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            if (value instanceof Bundle) value = bundleJson((Bundle) value);
            else if (value instanceof List<?>) value = new JSONArray((List<?>) value);
            result.put(key, value == null ? JSONObject.NULL : value);
        }
        return result;
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1 || !arguments[0].matches("[1-9][0-9]?")) {
            throw new IllegalArgumentException("Expected an iteration count from 1 to 50");
        }
        int iterations = Integer.parseInt(arguments[0]);
        if (iterations > 50 || Process.myUid() != 0 || Build.VERSION.SDK_INT != 33) {
            throw new IllegalArgumentException("Requires UID 0 on the API 33 profiling phone");
        }
        System.out.println(new JSONObject().put("record", "start").put("context", "shell-root-app_process").put("uid", Process.myUid()).put("pid", Process.myPid()).put("api", Build.VERSION.SDK_INT).put("iterations", iterations));

        boolean complete = true;
        PrivilegedAccess access = PrivilegedAccess.INSTANCE;
        access.setEnabled(true);
        try {
            for (int iteration = 0; iteration < iterations; iteration++) {
                long start = SystemClock.elapsedRealtimeNanos();
                AdvancedBatterySnapshot snapshot = access.readBatterySnapshot();
                long end = SystemClock.elapsedRealtimeNanos();
                boolean successful = snapshot != null && snapshot.hasStats() && "root".equals(snapshot.getAccessMethod()) && snapshot.getRemoteUid() == 0;
                complete &= successful;
                System.out.println(new JSONObject().put("record", "measurement").put("iteration", iteration).put("elapsed_nanos", end - start).put("successful_root_snapshot", successful).put("snapshot", snapshot == null ? JSONObject.NULL : bundleJson(snapshot.toBundle())));
            }
        } finally {
            access.setEnabled(false);
        }
        System.out.println(new JSONObject().put("record", "end").put("complete", complete));
        if (!complete) System.exit(1);
    }
}
