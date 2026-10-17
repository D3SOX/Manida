#!/usr/bin/env python3
"""Add the landing page to a GitHub Pages directory that already holds the release repositories."""
import argparse
from pathlib import Path
import re
import shutil


def copy_website(output: Path, signing_key: str):
    root = Path(__file__).resolve().parent.parent
    shutil.copytree(root / "website", output, dirs_exist_ok=True)
    assets = output / "assets"
    assets.mkdir(exist_ok=True)
    for name in ["nevumi.svg", "fdroid-qr-dark.png", "fdroid-qr-light.png"]:
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
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--signing-key", required=True)
    arguments = parser.parse_args()
    if not re.fullmatch(r"[0-9A-Fa-f]{40,64}", arguments.signing_key):
        raise SystemExit("Use the full fingerprint of the Arch signing key")
    copy_website(arguments.output.resolve(), arguments.signing_key)
    print(f"Added the website to {arguments.output}")
