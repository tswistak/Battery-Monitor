/*
    Copyright (c) 2026 Tomasz Świstak <tomasz@swistak.codes>
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <https://www.gnu.org/licenses/>.
*/
package codes.swistak.batterymonitor.e2ehelper;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

public abstract class Probe extends Instrumentation {
    protected Bundle arguments;
    protected final Bundle result = new Bundle();

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : arguments;
        start();
    }

    @Override
    public final void onStart() {
        String phase = arguments.getString("phase", "");
        try {
            execute(phase);
            result.putBoolean("probeSucceeded", true);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putBoolean("probeSucceeded", false);
            result.putString("errorStack", Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    protected final void report(String value) {
        Bundle status = new Bundle();
        status.putString("stream", value + "\n");
        sendStatus(2, status);
    }

    protected static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    protected abstract void execute(String phase) throws Exception;
}
