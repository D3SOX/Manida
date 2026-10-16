#!/usr/bin/env python3
"""Require release tags to match the shared app version."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parent.parent
match = re.fullmatch(r"v([0-9]+\.[0-9]+\.[0-9]+)", sys.argv[1])
if match is None:
    raise SystemExit("Use a release tag such as v0.1.0")
version = re.search(r"(?m)^appVersion=(.+)$", (root / "gradle.properties").read_text())
if version is None or version[1] != match[1]:
    raise SystemExit("The tag must match appVersion in gradle.properties")
