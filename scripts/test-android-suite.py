#!/usr/bin/env python3
"""Run every Android test class and retain separate, verified results."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--classes", nargs="*")
selected = parser.parse_args().classes
serial = os.environ["ANDROID_SERIAL"]
if not re.fullmatch(r"emulator-[0-9]{4}", serial):
    raise SystemExit("Run this suite on an owned emulator")
sources = ROOT / "androidApp/src/androidTest/kotlin/me/d3sox/manida"
classes = [path.stem for path in sorted(sources.glob("Android*Test.kt"))
           if "@ColdStartupCheck" not in path.read_text()]
if selected:
    if not set(selected) <= set(classes):
        raise SystemExit("Unknown test class")
    classes = selected
reports = ROOT / "androidApp/build/outputs/androidTest-results/connected/debug"
output = ROOT / ".test-environment/android-class-results"
shutil.rmtree(output, ignore_errors=True)
output.mkdir(parents=True)
failed = []
executed = 0
for name in classes:
    print("::group::" + name, flush=True)
    result = subprocess.run([str(ROOT / "gradlew"), ":androidApp:connectedDebugAndroidTest",
                             "-Pandroid.injected.device.serial=" + serial,
                             "-Pandroid.testInstrumentationRunnerArguments.class=me.d3sox.manida." + name,
                             "--console=plain"], cwd=ROOT)
    cases = [case for report in reports.glob("TEST-*.xml")
             for case in ET.parse(report).getroot().findall(".//testcase")]
    if result.returncode != 0 or any(case.get("classname") != "me.d3sox.manida." + name for case in cases):
        failed.append(name)
    executed += len(cases)
    shutil.copytree(reports, output / name)
    print("::endgroup::", flush=True)
if failed or executed < len(classes):
    raise SystemExit(f"Android suite failed: {failed}; {executed} cases across {len(classes)} classes")
print(f"Passed {executed} Android checks across {len(classes)} classes")
