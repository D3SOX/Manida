#!/usr/bin/env python3
"""Verify the real signed repository and reject modified indexes and APKs."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest

from PIL import Image, ImageOps
from pyzbar.pyzbar import decode

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("repository", ROOT / "scripts/package-fdroid.py")
repository = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repository)
REPO = Path(os.environ.get("MANIDA_FDROID_REPO", ROOT / ".test-environment/pages/fdroid/repo"))


class FdroidRepositoryTest(unittest.TestCase):
    def test_the_release_and_its_index_are_authenticated(self):
        repository.verify_repository(REPO)

    def test_the_index_includes_the_release_screenshots_in_gallery_order(self):
        index = json.loads((REPO / "index-v2.json").read_text())
        screenshots = index["packages"][repository.APP_ID]["metadata"]["screenshots"]["phone"]["en-US"]
        names = ["home", "now-playing", "queue", "audio-effects"]
        self.assertEqual(len(names), len(screenshots))
        for name, screenshot in zip(names, screenshots):
            with self.subTest(name=name):
                path = REPO / screenshot["name"].lstrip("/")
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), screenshot["sha256"])
                with Image.open(path) as actual, Image.open(ROOT / "packaging/screenshots" / f"{name}.png") as expected:
                    self.assertEqual(actual.size, expected.size)
                    self.assertEqual(actual.convert("RGB").tobytes(), expected.convert("RGB").tobytes())

    def test_modified_index_and_apk_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="manida-fdroid-test-") as temporary:
            copy = Path(temporary) / "repo"
            shutil.copytree(REPO, copy)
            index = copy / "index-v2.json"
            original = index.read_bytes()
            index.write_bytes(original + b" ")
            with self.assertRaisesRegex(ValueError, "index has an invalid digest"):
                repository.verify_repository(copy)
            index.write_bytes(original)
            apk = next(copy.glob("*.apk"))
            with apk.open("ab") as modified:
                modified.write(b"modified")
            with self.assertRaisesRegex(ValueError, "APK has an invalid digest"):
                repository.verify_repository(copy)

    def test_the_branded_qr_pins_the_repository_key_at_readme_size(self):
        expected = repository.REPO_DEEP_LINK + "?fingerprint=" + repository.fingerprint()
        for name in ["fdroid-qr.png", "fdroid-qr-light.png", "fdroid-qr-dark.png"]:
            for size in [520, 280]:
                with self.subTest(name=name, size=size):
                    image = Image.open(ROOT / "packaging" / name).resize((size, size), Image.Resampling.NEAREST)
                    if name == "fdroid-qr-dark.png":
                        image = ImageOps.invert(image)
                    self.assertEqual([expected], [code.data.decode() for code in decode(image)])


if __name__ == "__main__":
    unittest.main()
