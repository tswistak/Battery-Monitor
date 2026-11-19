#!/usr/bin/env bash
set -eu

poll=0
fallback=com.android.settings/.FallbackHome
launcher=com.google.android.apps.nexuslauncher/.NexusLauncherActivity

sleep() { poll=$((poll + 1)); }
adb() {
    case "$*" in
        "shell cmd package resolve-activity "*)
            if [[ $poll == 0 ]]; then echo "$fallback"; else echo "$launcher"; fi
            ;;
        "shell dumpsys window displays")
            if [[ $poll == 0 ]]; then
                echo "mCurrentFocus=Window{123 u0 com.android.settings/com.android.settings.FallbackHome}"
            else
                echo "mCurrentFocus=Window{456 u0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity}"
            fi
            ;;
        *)
            echo "Unexpected adb command: $*" >&2
            return 1
            ;;
    esac
}

source "$(dirname "$0")/wait-for-emulator-home.sh"
if [[ $poll != 1 ]]; then
    echo "Expected one wait for the real launcher after unlock, observed $poll" >&2
    exit 1
fi
