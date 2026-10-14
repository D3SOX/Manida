#!/usr/bin/env python3
"""Prepare or serve the owned, loopback-only OpenSSH integration fixture."""

import argparse
import getpass
import os
from pathlib import Path
import subprocess
import shutil

ROOT = Path(__file__).resolve().parent.parent
DIRECTORY = ROOT / ".test-environment" / "sftp-fixture"
SSHD = shutil.which("sshd") or "/usr/bin/sshd"


def prepare():
    if os.getuid() == 0:
        raise RuntimeError("Run this fixture as your regular user")
    DIRECTORY.mkdir(parents=True, exist_ok=True, mode=0o700)
    DIRECTORY.chmod(0o700)
    for name in ("host", "client"):
        key = DIRECTORY / name
        if not key.exists():
            subprocess.run(["ssh-keygen", "-q", "-t", "rsa", "-b", "3072", "-N", "", "-f", str(key)], check=True)
        key.chmod(0o600)
    fingerprint = subprocess.check_output(["ssh-keygen", "-lf", str(DIRECTORY / "host.pub"), "-E", "sha256"], text=True).split()[1]
    (DIRECTORY / "fingerprint").write_text(fingerprint)
    (DIRECTORY / "android-fixture.properties").write_text("\n".join([
        f"username={getpass.getuser()}", f"musicDirectory={ROOT}/.test-environment/music",
        f"editableMusicDirectory={ROOT}/.test-environment/sftp-music", f"fingerprint={fingerprint}", "",
    ]))
    (DIRECTORY / "known_hosts").write_text("[127.0.0.1]:14534 " + (DIRECTORY / "host.pub").read_text())
    config = "\n".join([
        "Port 14534", "ListenAddress 127.0.0.1", f"HostKey {DIRECTORY}/host", f"PidFile {DIRECTORY}/sshd.pid",
        f"AuthorizedKeysFile {DIRECTORY}/client.pub", "StrictModes yes", "PasswordAuthentication no",
        "KbdInteractiveAuthentication no", "UsePAM no", "PermitRootLogin no", f"AllowUsers {getpass.getuser()}",
        "AllowTcpForwarding no", "X11Forwarding no", "PermitTTY no", "ForceCommand internal-sftp",
        "Subsystem sftp internal-sftp", "LogLevel ERROR", "",
    ])
    (DIRECTORY / "sshd_config").write_text(config)
    subprocess.run([SSHD, "-t", "-f", str(DIRECTORY / "sshd_config")], check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "serve"))
    action = parser.parse_args().action
    prepare()
    if action == "serve":
        print("Owned SFTP fixture listening on 127.0.0.1:14534", flush=True)
        os.execv(SSHD, [SSHD, "-D", "-e", "-f", str(DIRECTORY / "sshd_config")])
    print("SFTP fixture keys and configuration prepared")
