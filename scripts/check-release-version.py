#!/usr/bin/env python3
"""Require release tags to match both platform versions."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parent.parent
match = re.fullmatch(r"v([0-9]+\.[0-9]+\.[0-9]+)", sys.argv[1])
if match is None:
    raise SystemExit("Use a release tag such as v0.1.0")
for module, setting in [("androidApp", "versionName"), ("desktopApp", "packageVersion")]:
    version = re.search(rf'{setting}\s*=\s*"([^"]+)"', (root / module / "build.gradle.kts").read_text())
    if version is None or version[1] != match[1]:
        raise SystemExit(f"The tag must match {module}'s {setting}")
