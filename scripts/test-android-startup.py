#!/usr/bin/env python3
"""Check a real cold restart on an owned emulator, retaining its seeded app data."""
import os
from pathlib import Path
import re
import shutil
import subprocess

APP = "me.d3sox.manida"
ROOT = Path(__file__).resolve().parent.parent
SERIAL = os.environ["MANIDA_TEST_SERIAL"]
if not re.fullmatch(r"emulator-[0-9]{4}", SERIAL):
    raise SystemExit("This check clears synthetic data and requires an owned emulator")
ADB = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb") if os.environ.get("ANDROID_HOME") else shutil.which("adb")


def adb(*arguments, timeout=60):
    result = subprocess.run([ADB, "-s", SERIAL, *arguments], capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


if os.environ.get("GITHUB_ACTIONS") != "true" and not adb("emu", "avd", "name").startswith("manida_"):
    raise SystemExit("Use a dedicated Manida test emulator")
assert adb("shell", "am", "get-current-user").strip() == "0"
# The connected-test runner removes its installed APKs when it finishes.
for apk in ["androidApp/build/outputs/apk/debug/androidApp-debug.apk",
            "androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk"]:
    adb("install", "--user", "0", "-r", str(ROOT / apk), timeout=120)
assert "Success" in adb("shell", "pm", "clear", "--user", "0", APP)
api = int(adb("shell", "getprop", "ro.build.version.sdk").strip())
permission = "android.permission.READ_MEDIA_AUDIO" if api >= 33 else "android.permission.READ_EXTERNAL_STORAGE"
adb("shell", "pm", "grant", APP, permission)
try:
    for method in ["seedCachedLibraryAndQueueForColdRestart", "coldOpenShowsTheCachedArtistsPageWithoutASongsFrame"]:
        result = adb("shell", "am", "instrument", "--user", "0", "-w", "-r", "-e", "class",
                     "me.d3sox.manida.AndroidStartupTest#" + method,
                     APP + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=300)
        if not re.search(r"OK\s*\(1 test\)", result):
            raise RuntimeError(result)
        print("Passed " + method)
        for line in result.splitlines():
            if "STARTUP_CACHE_VISIBLE_MS=" in line:
                print(line)
        adb("shell", "am", "force-stop", "--user", "0", APP)
finally:
    adb("shell", "pm", "clear", "--user", "0", APP)
