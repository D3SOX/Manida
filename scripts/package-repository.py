#!/usr/bin/env python3
"""Create a signed pacman repository ready for GitHub Pages."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess


def repository(release: Path, output: Path, signing_key: str):
    if not re.fullmatch(r"[0-9A-Fa-f]{40,64}", signing_key):
        raise ValueError("Use the full fingerprint of the release signing key")
    packages = sorted(release.glob("manida-*-x86_64.pkg.tar.zst"))
    if not packages:
        raise ValueError("Create the Arch release package first")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("Choose an empty output directory")
    target = output / "arch/x86_64"
    target.mkdir(parents=True)
    copies = []
    passphrase = os.environ.get("MANIDA_ARCH_SIGNING_PASSPHRASE")
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
                    str(target / "manida.db.tar.gz"), *map(str, copies)], check=True)
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
    (output / "manida.asc").write_bytes(exported.stdout)
    (output / ".nojekyll").touch()
    copy_website(output, signing_key)
    print(f"Created signed repository at {output}")


def copy_website(output: Path, signing_key: str):
    root = Path(__file__).resolve().parent.parent
    shutil.copytree(root / "website", output, dirs_exist_ok=True)
    assets = output / "assets"
    assets.mkdir(exist_ok=True)
    for name in ["manida.svg", "fdroid-qr-dark.png", "fdroid-qr-light.png"]:
        shutil.copyfile(root / "packaging" / name, assets / name)
    shutil.copytree(root / "packaging/screenshots", assets / "screenshots", dirs_exist_ok=True)
    index = output / "index.html"
    placeholder = "5948F17F1611CC2B995BC6121F0D542126D514AB"
    html = index.read_text()
    if placeholder not in html:
        raise ValueError("The website is missing the Arch signing-key fingerprint")
    index.write_text(html.replace(placeholder, signing_key.upper()))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--signing-key", required=True)
    arguments = parser.parse_args()
    repository(arguments.release.resolve(), arguments.output.resolve(), arguments.signing_key)
