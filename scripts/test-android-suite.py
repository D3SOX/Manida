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
parser.add_argument("--shard", default="1/1", help="Run every Nth class, as INDEX/COUNT, so CI can split the suite")
arguments = parser.parse_args()
selected = arguments.classes
shard = re.fullmatch(r"([1-9][0-9]*)/([1-9][0-9]*)", arguments.shard)
if shard is None or int(shard[1]) > int(shard[2]):
    raise SystemExit("Use a shard such as 2/4")
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
classes = classes[int(shard[1]) - 1::int(shard[2])]
reports = ROOT / "androidApp/build/outputs/androidTest-results/connected/debug"
output = ROOT / ".test-environment/android-class-results"
shutil.rmtree(output, ignore_errors=True)
output.mkdir(parents=True)
api = int(subprocess.run(["adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk"],
                         capture_output=True, text=True, check=True).stdout.strip())
SUPPRESS = re.compile(r"@SdkSuppress\(minSdkVersion = (\d+)\)")


def expected_cases(name):
    """Count the tests a class should run on this API, so a run that silently executes nothing still fails."""
    header, _, body = (sources / f"{name}.kt").read_text().partition("class " + name)
    if max(map(int, SUPPRESS.findall(header)), default=0) > api:
        return 0
    annotated = re.findall(r"((?:@\w+(?:\([^)]*\))?\s+)+)fun ", body)
    return sum(1 for annotations in annotated if "@Test" in annotations
               and max(map(int, SUPPRESS.findall(annotations)), default=0) <= api)


failed = []
flaky = []
executed = 0


def run(name, attempt):
    """Run one class and keep its reports; return the executed cases, or None when it failed."""
    print(f"::group::{name} (attempt {attempt})", flush=True)
    shutil.rmtree(reports, ignore_errors=True)
    result = subprocess.run([str(ROOT / "gradlew"), ":androidApp:connectedDebugAndroidTest",
                             "-Dorg.gradle.jvmargs=-Xmx1g", "--max-workers=2",
                             "-Pkotlin.compiler.execution.strategy=in-process",
                             "-Pandroid.injected.device.serial=" + serial,
                             "-Pandroid.testInstrumentationRunnerArguments.class=me.d3sox.manida." + name,
                             "--console=plain"], cwd=ROOT)
    cases = [case for report in reports.glob("TEST-*.xml")
             for case in ET.parse(report).getroot().findall(".//testcase")]
    if reports.exists():
        shutil.copytree(reports, output / (name if attempt == 1 else f"{name}-attempt{attempt}"))
    print("::endgroup::", flush=True)
    ok = (result.returncode == 0 and (cases or expected_cases(name) == 0)
          and all(case.get("classname") == "me.d3sox.manida." + name for case in cases))
    return cases if ok else None


for name in classes:
    cases = run(name, 1)
    if cases is None:
        # Hosted emulators share two slow cores with Gradle, so retry once; a second failure is a real failure.
        cases = run(name, 2)
        if cases is None:
            failed.append(name)
            continue
        flaky.append(name)
        print(f"::warning title=Flaky Android test::{name} failed once and passed on retry", flush=True)
    executed += len(cases)
if failed or executed < len(classes):
    raise SystemExit(f"Android suite failed: {failed}; passed on retry: {flaky}; {executed} cases across {len(classes)} classes")
print(f"Passed {executed} Android checks across {len(classes)} classes; passed on retry: {flaky or 'none'}")
