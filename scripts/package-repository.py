#!/usr/bin/env python3
"""Create a signed pacman repository for the GitHub Pages site."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess


def repository(release: Path, output: Path, signing_key: str):
    if not re.fullmatch(r"[0-9A-Fa-f]{40,64}", signing_key):
        raise ValueError("Use the full fingerprint of the release signing key")
    packages = sorted(release.glob("nevumi-*-x86_64.pkg.tar.zst"))
    if not packages:
        raise ValueError("Create the Arch release package first")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("Choose an empty output directory")
    target = output / "arch/x86_64"
    target.mkdir(parents=True)
    copies = []
    passphrase = os.environ.get("NEVUMI_ARCH_SIGNING_PASSPHRASE")
    for package in packages:
        copied = target / package.name
        shutil.copyfile(package, copied)
        signing = ["gpg", "--batch", "--pinentry-mode", "loopback", "--detach-sign", "--local-user", signing_key]
        if passphrase is not None:
            signing += ["--passphrase-fd", "0"]
        subprocess.run([*signing, "--output", str(copied) + ".sig", str(copied)],
                       input=(passphrase + "\n").encode() if passphrase is not None else None, check=True, timeout=30)
        copies.append(copied)
    subprocess.run(["repo-add", "--sign", "--key", signing_key, "--include-sigs",
                    str(target / "nevumi.db.tar.gz"), *map(str, copies)], check=True)
    # upload-pages-artifact does not accept symlinks, including repo-add's aliases.
    for path in target.iterdir():
        if path.is_symlink():
            content = path.read_bytes()
            path.unlink()
            path.write_bytes(content)
    exported = subprocess.run(["gpg", "--batch", "--armor", "--export", signing_key],
                              check=True, capture_output=True)
    if not exported.stdout:
        raise ValueError("The release signing key is unavailable")
    (output / "nevumi.asc").write_bytes(exported.stdout)
    (output / ".nojekyll").touch()
    print(f"Created signed repository at {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--signing-key", required=True)
    arguments = parser.parse_args()
    repository(arguments.release.resolve(), arguments.output.resolve(), arguments.signing_key)
