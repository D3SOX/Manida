#!/usr/bin/env python3
"""Run every Android test class and retain separate, verified results."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
# One unit per test plus one per class for Gradle/device setup. Keep shards small as the suite grows.
SHARD_BUDGET = 28
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--classes", nargs="*")
parser.add_argument("--shard", default="1/1", help="Run one balanced shard, as INDEX/COUNT")
parser.add_argument("--matrix", action="store_true", help="Print the dynamic CI shard matrix without running tests")
parser.add_argument("--class-timeout", type=int, default=300, help="Maximum seconds for one class, including Gradle setup")
arguments = parser.parse_args()
if arguments.class_timeout <= 0:
    parser.error("--class-timeout must be positive")
selected = arguments.classes
shard = re.fullmatch(r"([1-9][0-9]*)/([1-9][0-9]*)", arguments.shard)
if shard is None or int(shard[1]) > int(shard[2]):
    raise SystemExit("Use a shard such as 2/4")
sources = ROOT / "androidApp/src/androidTest/kotlin/me/d3sox/manida"
classes = [path.stem for path in sorted(sources.glob("Android*Test.kt"))
           if "@ColdStartupCheck" not in path.read_text()]
if selected:
    if not set(selected) <= set(classes):
        raise SystemExit("Unknown test class")
    classes = selected
SUPPRESS = re.compile(r"@SdkSuppress\(minSdkVersion = (\d+)\)")


def expected_cases(name, api=float("inf")):
    """Count the tests a class should run on this API, so a run that silently executes nothing still fails."""
    header, _, body = (sources / f"{name}.kt").read_text().partition("class " + name)
    if max(map(int, SUPPRESS.findall(header)), default=0) > api:
        return 0
    annotated = re.findall(r"((?:@\w+(?:\([^)]*\))?\s+)+)fun ", body)
    return sum(1 for annotations in annotated if "@Test" in annotations
               and max(map(int, SUPPRESS.findall(annotations)), default=0) <= api)


weights = {name: 1 + expected_cases(name) for name in classes}


def distribute(count):
    """Place the largest classes first, breaking ties consistently on every runner."""
    groups = [[] for _ in range(count)]
    loads = [0] * count
    for name in sorted(classes, key=lambda name: (-weights[name], name)):
        index = min(range(count), key=lambda index: loads[index])
        groups[index].append(name)
        loads[index] += weights[name]
    return groups, loads


if not classes:
    raise SystemExit("No Android test classes found")
if arguments.matrix:
    count = min(len(classes), (sum(weights.values()) + SHARD_BUDGET - 1) // SHARD_BUDGET)
    # A class cannot be split, but it can occupy a shard on its own.
    while count < len(classes) and any(
            load > SHARD_BUDGET and len(group) > 1 for group, load in zip(*distribute(count))):
        count += 1
    if count > 128:
        raise SystemExit("Android suite exceeds GitHub's 256-job matrix limit across two API levels")
    print(json.dumps([{"index": index, "total": count} for index in range(1, count + 1)]))
    raise SystemExit(0)

classes = distribute(int(shard[2]))[0][int(shard[1]) - 1]
serial = os.environ["ANDROID_SERIAL"]
if not re.fullmatch(r"emulator-[0-9]{4}", serial):
    raise SystemExit("Run this suite on an owned emulator")
reports = ROOT / "androidApp/build/outputs/androidTest-results/connected/debug"
output = ROOT / ".test-environment/android-class-results"
shutil.rmtree(output, ignore_errors=True)
output.mkdir(parents=True)
api = int(subprocess.run(["adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk"],
                         capture_output=True, text=True, check=True, timeout=30).stdout.strip())


failed = []
flaky = []
executed = 0


def run(name, attempt):
    """Run one class and keep its reports; return the executed cases, or None when it failed."""
    print(f"::group::{name} (attempt {attempt})", flush=True)
    shutil.rmtree(reports, ignore_errors=True)
    started = time.monotonic()
    timed_out = False
    with subprocess.Popen([str(ROOT / "gradlew"), ":androidApp:connectedDebugAndroidTest",
                             "-Dorg.gradle.jvmargs=-Xmx1g", "--max-workers=2",
                             "-Pkotlin.compiler.execution.strategy=in-process",
                             "-Pandroid.injected.device.serial=" + serial,
                             "-Pandroid.testInstrumentationRunnerArguments.class=me.d3sox.manida." + name,
                             "--console=plain"], cwd=ROOT, start_new_session=True) as process:
        try:
            process.wait(timeout=arguments.class_timeout)
        except subprocess.TimeoutExpired:
            # Stop the client and its children. Do not reuse a stuck Gradle/device session for retries.
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            timed_out = True
    if reports.exists():
        shutil.copytree(reports, output / (name if attempt == 1 else f"{name}-attempt{attempt}"))
    print(f"{name}: {time.monotonic() - started:.1f}s", flush=True)
    print("::endgroup::", flush=True)
    if timed_out:
        raise SystemExit(f"{name} timed out after {arguments.class_timeout} seconds; aborting shard")
    cases = [case for report in reports.glob("TEST-*.xml")
             for case in ET.parse(report).getroot().findall(".//testcase")]
    ok = (process.returncode == 0 and (cases or expected_cases(name, api) == 0)
          and all(case.get("classname") == "me.d3sox.manida." + name for case in cases))
    return cases if ok else None


retry = []
for name in classes:
    cases = run(name, 1)
    if cases is None:
        retry.append(name)
    else:
        executed += len(cases)
# Hosted emulators share two slow cores with Gradle and keep settling for minutes after boot, so retry failures
# once after the other classes have run; a second failure is a real failure.
for name in retry:
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
