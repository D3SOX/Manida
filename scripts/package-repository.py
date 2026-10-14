#!/usr/bin/env python3
"""Create a signed pacman repository ready for GitHub Pages."""
import argparse
import html
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
    links = "".join(f'<li><a href="arch/x86_64/{html.escape(p.name)}">{html.escape(p.name)}</a></li>' for p in copies)
    (output / "index.html").write_text(f'''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Manida for Arch Linux</title>
<style>body{{font:18px system-ui;line-height:1.6;max-width:48rem;margin:3rem auto;padding:0 1rem;background:#19151e;color:#f4edf9}}a{{color:#d8baff}}pre{{overflow:auto;padding:1rem;background:#29232f;border-radius:1rem}}code{{font-size:.9em}}</style>
<h1>Manida for Arch Linux</h1>
<p>Manida is in alpha. Local music and Navidrome/Subsonic in one library. Download the portable app or Android APK from
<a href="https://github.com/D3SOX/Manida/releases">GitHub releases</a>.</p>
<h2>Install with pacman</h2>
<p>Download the <a href="manida.asc">release signing key</a> and check its fingerprint:</p>
<pre>gpg --show-keys --fingerprint manida.asc</pre>
<pre>{signing_key.upper()}</pre>
<p>Import and trust this key:</p>
<pre>sudo pacman-key --add manida.asc
sudo pacman-key --lsign-key {signing_key.upper()}</pre>
<p>Add this repository to <code>/etc/pacman.conf</code>:</p>
<pre>[manida]
SigLevel = Required DatabaseRequired
Server = https://manida.d3sox.me/arch/$arch</pre>
<pre>sudo pacman -Syu manida</pre>
<p>Packages and the repository database are signed with the key above. Manida uses mpv for playback
and your desktop’s Secret Service for credentials. The portable app needs mpv, libsecret, GLib tools and font libraries installed.</p>
<h2>Packages</h2><ul>{links}</ul>
<p>Licensed under AGPL-3.0-only. <a href="https://github.com/D3SOX/Manida">Source code</a>.</p>
</html>''')
    print(f"Created signed repository at {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--signing-key", required=True)
    arguments = parser.parse_args()
    repository(arguments.release.resolve(), arguments.output.resolve(), arguments.signing_key)
