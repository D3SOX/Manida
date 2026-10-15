#!/usr/bin/env python3
"""Exercise the Android suite driver with local substitutes for Gradle and adb."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SOURCES = Path("androidApp/src/androidTest/kotlin/me/d3sox/manida")


class AndroidSuiteRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="manida-suite-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        shutil.copy(ROOT / "scripts/test-android-suite.py", self.root / "scripts")
        (self.root / SOURCES).mkdir(parents=True)
        self.executable("adb", "print('36')\n")
        self.executable("gradlew", """
import os
from pathlib import Path
import sys
import time
import xml.etree.ElementTree as ET
name = next(arg.split('=')[-1] for arg in sys.argv if 'RunnerArguments.class=' in arg)
with open('invocations', 'a') as log:
    log.write(name + '\\n')
reports = Path('androidApp/build/outputs/androidTest-results/connected/debug')
reports.mkdir(parents=True, exist_ok=True)
(reports / 'device.log').write_text('Retain diagnostics even on timeout')
if os.environ.get('STALL') == 'true':
    Path('gradle.pid').write_text(str(os.getpid()))
    time.sleep(60)
attempt = Path('invocations').read_text().splitlines().count(name)
retry = os.environ.get('RETRY') == 'true' and attempt == 1
suite = ET.Element('testsuite')
if not retry:
    source = Path('androidApp/src/androidTest/kotlin/me/d3sox/manida') / (name.split('.')[-1] + '.kt')
    count = 1 if os.environ.get('PARTIAL') == 'true' else source.read_text().count('@Test')
    for index in range(count):
        case = ET.SubElement(suite, 'testcase', classname=name, name=f'example{index}')
        if os.environ.get('SKIPPED') == 'true':
            ET.SubElement(case, 'skipped')
ET.ElementTree(suite).write(reports / 'TEST-example.xml')
""")
        self.environment = dict(os.environ, ANDROID_SERIAL="emulator-5554",
                                PATH=str(self.root) + os.pathsep + os.environ["PATH"])

    def executable(self, name, source):
        path = self.root / name
        path.write_text(f"#!{sys.executable}\n" + source)
        path.chmod(0o755)

    def source(self, name, annotation="", tests=1):
        (self.root / SOURCES / f"{name}.kt").write_text(
            f"{annotation}\nclass {name} {{\n"
            + "\n".join(f"@Test\nfun example{index}() {{}}" for index in range(tests)) + "\n}\n")

    def run_suite(self, *arguments, **environment):
        return subprocess.run([sys.executable, "scripts/test-android-suite.py", *arguments],
                              cwd=self.root, env=dict(self.environment, **environment),
                              capture_output=True, text=True, timeout=10)

    def test_hung_class_fails_promptly_and_retains_diagnostics(self):
        self.source("AndroidExampleTest")
        result = self.run_suite("--class-timeout", "1", STALL="true")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("timed out after 1 seconds", result.stdout + result.stderr)
        self.assertTrue((self.root / ".test-environment/android-class-results/AndroidExampleTest/device.log").is_file())
        pid = int((self.root / "gradle.pid").read_text())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)
        self.assertEqual(1, len((self.root / "invocations").read_text().splitlines()))

    def matrix(self):
        # Planning runs on a fresh runner with neither an Android SDK nor a device.
        self.environment.pop("ANDROID_SERIAL", None)
        result = self.run_suite("--matrix")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return json.loads(result.stdout)

    def test_dynamic_shards_cover_each_class_once_and_exclude_cold_start(self):
        shutil.copytree(ROOT / SOURCES, self.root / SOURCES, dirs_exist_ok=True)
        expected = {"me.d3sox.manida." + path.stem for path in (ROOT / SOURCES).glob("Android*Test.kt")
                    if "@ColdStartupCheck" not in path.read_text()}
        for name in expected:
            self.source(name.split(".")[-1])
        matrix = self.matrix()
        self.assertEqual(matrix, self.matrix(), "Planning must be deterministic")
        for shard in matrix:
            result = self.run_suite("--shard", f"{shard['index']}/{shard['total']}", ANDROID_SERIAL="emulator-5554")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        actual = (self.root / "invocations").read_text().splitlines()
        self.assertCountEqual(sorted(expected), actual)

    def test_new_classes_and_methods_increase_the_shard_count(self):
        self.source("AndroidExampleTest")
        initial = len(self.matrix())
        for index in range(20):
            self.source(f"AndroidExample{index}Test")
        more_classes = len(self.matrix())
        self.assertGreater(more_classes, initial)
        for index in range(20):
            self.source(f"AndroidExample{index}Test", tests=4)
        self.assertGreater(len(self.matrix()), more_classes)

    def test_large_classes_are_spread_between_shards(self):
        for index in range(3):
            self.source(f"AndroidLarge{index}Test", tests=20)
            self.source(f"AndroidSmall{index}Test")
        matrix = self.matrix()
        self.assertEqual(3, len(matrix))
        for shard in matrix:
            (self.root / "invocations").unlink(missing_ok=True)
            result = self.run_suite("--shard", f"{shard['index']}/{shard['total']}", ANDROID_SERIAL="emulator-5554")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            calls = (self.root / "invocations").read_text()
            self.assertEqual(1, calls.count("AndroidLarge"))
            self.assertEqual(1, calls.count("AndroidSmall"))

    def test_oversized_class_gets_its_own_shard(self):
        self.source("AndroidLargeTest", tests=50)
        self.source("AndroidSmallTest")
        self.assertEqual(2, len(self.matrix()))

    def test_empty_suite_cannot_produce_a_green_matrix(self):
        result = self.run_suite("--matrix")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("No Android test classes found", result.stderr)

    def test_empty_report_is_retried_and_both_attempts_are_retained(self):
        self.source("AndroidExampleTest")
        result = self.run_suite(RETRY="true")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("failed once and passed on retry", result.stdout)
        output = self.root / ".test-environment/android-class-results"
        self.assertTrue((output / "AndroidExampleTest/TEST-example.xml").is_file())
        self.assertTrue((output / "AndroidExampleTest-attempt2/TEST-example.xml").is_file())

    def test_partial_report_is_not_accepted_as_a_passing_class(self):
        self.source("AndroidExampleTest", tests=2)
        result = self.run_suite(PARTIAL="true")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(2, len((self.root / "invocations").read_text().splitlines()))

    def test_api_suppressed_class_is_not_a_failure_or_an_executed_test(self):
        self.source("AndroidExampleTest", annotation="@SdkSuppress(minSdkVersion = 37)")
        result = self.run_suite(SKIPPED="true")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Passed 0 Android checks", result.stdout)


if __name__ == "__main__":
    unittest.main()
