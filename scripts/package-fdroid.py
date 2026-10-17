#!/usr/bin/env python3
"""Publish a signed F-Droid index and release APK, without publishing credentials."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from androguard.core.apk import APK
from cryptography import x509
from cryptography.hazmat.primitives import serialization
import yaml
from loguru import logger

ROOT = Path(__file__).resolve().parent.parent
REPO_URL = "https://nevumi.app/fdroid/repo"
REPO_DEEP_LINK = "fdroidrepos://nevumi.app/fdroid/repo"
APP_ID = "me.d3sox.nevumi"


def fingerprint():
    certificate = x509.load_pem_x509_certificate((ROOT / "packaging/nevumi-fdroid.pem").read_bytes())
    return hashlib.sha256(certificate.public_bytes(serialization.Encoding.DER)).hexdigest().upper()


def verify_repository(repository: Path):
    from fdroidserver import common, index
    common.config = {"jarsigner": shutil.which("jarsigner")}
    entry, _, _ = index.get_index_from_jar(str(repository / "entry.jar"), fingerprint())
    contents = (repository / entry["index"]["name"].lstrip("/")).read_bytes()
    if hashlib.sha256(contents).hexdigest() != entry["index"]["sha256"]:
        raise ValueError("The signed repository index has an invalid digest")
    versions = json.loads(contents)["packages"][APP_ID]["versions"]
    if not versions:
        raise ValueError("The repository contains no Nevumi release")
    for version in versions.values():
        package = repository / version["file"]["name"].lstrip("/")
        if hashlib.sha256(package.read_bytes()).hexdigest() != version["file"]["sha256"]:
            raise ValueError("The release APK has an invalid digest")


def repository(apk: Path, output: Path, keystore: Path):
    logger.disable("androguard")
    certificate = subprocess.run(
        ["keytool", "-exportcert", "-keystore", str(keystore), "-alias", "manida-fdroid",
         "-storepass:env", "NEVUMI_FDROID_KEYSTORE_PASSWORD"],
        check=True, capture_output=True, timeout=30,
    ).stdout
    expected = x509.load_pem_x509_certificate((ROOT / "packaging/nevumi-fdroid.pem").read_bytes())
    if certificate != expected.public_bytes(serialization.Encoding.DER):
        raise ValueError("The repository key does not match the published certificate")
    fingerprint = hashlib.sha256(certificate).hexdigest().upper()
    package = APK(str(apk))
    if package.get_package() != APP_ID:
        raise ValueError("Only the production Nevumi APK belongs in the release repository")
    if output.exists():
        raise ValueError("Choose a new repository output directory")
    with tempfile.TemporaryDirectory(prefix="nevumi-fdroid-") as temporary:
        directory = Path(temporary)
        (directory / "repo").mkdir()
        (directory / "metadata").mkdir()
        shutil.copyfile(apk, directory / "repo" / apk.name)
        screenshots = directory / "repo" / APP_ID / "en-US" / "phoneScreenshots"
        screenshots.mkdir(parents=True)
        for position, name in enumerate(["home", "now-playing", "queue", "audio-effects"], start=1):
            shutil.copyfile(ROOT / "packaging/screenshots" / f"{name}.png",
                            screenshots / f"{position}-{name}.png")
        shutil.copyfile(ROOT / "packaging/nevumi.png", directory / "icon.png")
        metadata = yaml.safe_load((ROOT / ".fdroid.yml").read_text())
        for field in ["Builds", "RepoType", "Repo", "AutoUpdateMode", "UpdateCheckMode"]:
            metadata.pop(field, None)
        metadata.update(Name="Nevumi", WebSite="https://nevumi.app",
                        Summary="Local music, Navidrome and Subsonic in one library",
                        CurrentVersion=package.get_androidversion_name(),
                        CurrentVersionCode=int(package.get_androidversion_code()))
        (directory / "metadata" / (APP_ID + ".yml")).write_text(yaml.safe_dump(metadata))
        config = {
            "repo_url": REPO_URL, "repo_name": "Nevumi",
            "repo_description": "Nevumi alpha releases for Android. Local music, Navidrome and Subsonic.",
            "repo_icon": "icon.png", "archive_older": 0,
            "keystore": str(keystore), "repo_keyalias": "manida-fdroid",
            "keystorepass": {"env": "NEVUMI_FDROID_KEYSTORE_PASSWORD"},
            "keypass": {"env": "NEVUMI_FDROID_KEYSTORE_PASSWORD"},
            "sdk_path": os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")),
        }
        (directory / "config.yml").write_text(yaml.safe_dump(config))
        (directory / "config.yml").chmod(0o600)
        subprocess.run(["fdroid", "update"], cwd=directory, check=True, timeout=180)
        verify_repository(directory / "repo")
        shutil.copytree(directory / "repo", output / "repo")
        # The repository tool stores these locally; they are not public repository files.
        for name in ["status", "tmp"]:
            shutil.rmtree(output / "repo" / name, ignore_errors=True)
        shutil.copyfile(ROOT / "packaging/fdroid-qr.png", output / "fdroid-qr.png")
        (output / "index.html").write_text(f'''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Nevumi F-Droid repository</title>
<style>body{{font:18px system-ui;line-height:1.6;max-width:48rem;margin:3rem auto;padding:0 1rem;background:#19151e;color:#f4edf9}}a{{color:#d8baff}}code{{overflow-wrap:anywhere}}</style>
<h1>Nevumi for Android</h1><p>Nevumi is in alpha. Add this repository to F-Droid for signed releases and updates.</p>
<p><a href="{REPO_DEEP_LINK}?fingerprint={fingerprint}">Add the Nevumi repository to F-Droid</a></p>
<a href="{REPO_DEEP_LINK}?fingerprint={fingerprint}"><img src="fdroid-qr.png" width="280" height="280" alt="Scan to add Nevumi to F-Droid"></a>
<p>Repository: <code>{REPO_URL}</code><br>SHA-256 fingerprint: <code>{fingerprint}</code></p>
<p><a href="https://github.com/D3SOX/Nevumi#android">APK downloads and Obtainium</a> · <a href="/">Linux installation</a></p>
</html>''')
    print(f"Created F-Droid repository at {output}; SHA-256 fingerprint {fingerprint}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    args = parser.parse_args()
    repository(args.apk.resolve(), args.output.resolve(), args.keystore.resolve())
