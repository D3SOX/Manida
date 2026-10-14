#!/usr/bin/env python3
"""Sign a release APK using passwords supplied through the environment."""
import argparse
import os
from pathlib import Path
import subprocess


def sign(source: Path, output: Path, keys: Path, alias: str):
    if not source.is_file() or not keys.is_file():
        raise ValueError("Provide the release APK and signing keystore")
    if output.exists():
        raise FileExistsError(f"The signed APK already exists: {output}")
    if not alias or not os.environ.get("MANIDA_ANDROID_KEYSTORE_PASSWORD"):
        raise ValueError("Configure the key alias and MANIDA_ANDROID_KEYSTORE_PASSWORD")
    sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "")))
    signer = sdk / "build-tools/37.0.0/apksigner"
    if not signer.is_file():
        raise ValueError("Install Android SDK build-tools 37.0.0 and set ANDROID_HOME")
    output.parent.mkdir(parents=True, exist_ok=True)
    command = [str(signer), "sign", "--ks", str(keys), "--ks-key-alias", alias,
               "--ks-pass", "env:MANIDA_ANDROID_KEYSTORE_PASSWORD", "--out", str(output)]
    if os.environ.get("MANIDA_ANDROID_KEY_PASSWORD"):
        command += ["--key-pass", "env:MANIDA_ANDROID_KEY_PASSWORD"]
    subprocess.run([*command, str(source)], check=True, timeout=30)
    subprocess.run([str(signer), "verify", "--verbose", "--print-certs", str(output)], check=True, timeout=15)
    subprocess.run([str(signer.parent / "zipalign"), "-c", "-P", "16", "4", str(output)], check=True, timeout=15)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--alias", required=True)
    arguments = parser.parse_args()
    sign(arguments.input, arguments.output, arguments.keystore, arguments.alias)
