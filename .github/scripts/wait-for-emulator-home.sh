#!/usr/bin/env bash
set -eu

launcher_restarted=false
for attempt in {1..30}; do
    launcher_activity=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | tr -d '\r')
    focus=$(adb shell dumpsys window displays | grep -m 1 'mCurrentFocus=' || true)
    if [[ $launcher_activity == */* && $launcher_activity != com.android.settings/*FallbackHome ]]; then
        launcher_package=${launcher_activity%/*}
        if [[ "$focus" == *" u0 $launcher_package/"* ]]; then break; fi
        if [[ $launcher_restarted == false && "$focus" == *"Application Not Responding: $launcher_package}"* ]]; then
            adb shell am start -S -W -a android.intent.action.MAIN -c android.intent.category.HOME -n "$launcher_activity"
            launcher_restarted=true
        fi
    fi
    if [[ $attempt == 30 ]]; then
        echo "::error::Emulator home did not gain focus. Resolved HOME: $launcher_activity. Last window: $focus"
        exit 1
    fi
    sleep 1
done
