#!/usr/bin/env python3
"""Package the verified Compose distribution as a portable archive and Arch package."""
import argparse
import gzip
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def package(image: Path, output: Path, version: str, pkgrel: str = "1", release_tag: str | None = None):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Use a three-part release version")
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", pkgrel):
        raise ValueError("Use a numeric package release")
    tag = release_tag or f"v{version}"
    if not re.fullmatch(r"[A-Za-z0-9._-]+", tag):
        raise ValueError("Invalid release tag")
    if not (image / "bin/manida").is_file():
        raise ValueError("Run :desktopApp:createDistributable first")
    output.mkdir(parents=True, exist_ok=True)
    archive_name = f"manida-{version}-linux-x86_64.tar.gz"
    archive = output / archive_name
    if archive.exists():
        raise FileExistsError(f"Release archive already exists: {archive}")
    with tempfile.TemporaryDirectory(prefix="manida-release-") as temporary:
        work = Path(temporary)
        portable = work / "manida"
        shutil.copytree(image, portable)
        shutil.copyfile(ROOT / "LICENSE", portable / "LICENSE")
        for name in ["manida.desktop", "manida.svg"]:
            shutil.copyfile(ROOT / "packaging" / name, portable / name)
        epoch = int(os.environ.get("SOURCE_DATE_EPOCH", "0"))
        with archive.open("xb") as raw, gzip.GzipFile(fileobj=raw, filename="", mtime=epoch) as compressed:
            with tarfile.open(fileobj=compressed, mode="w|") as tar:
                def normalize(entry):
                    entry.uid = entry.gid = 0
                    entry.uname = entry.gname = "root"
                    entry.mtime = epoch
                    return entry
                tar.add(portable, arcname="manida", filter=normalize)
        checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
        build = work / "arch"
        build.mkdir()
        shutil.copyfile(archive, build / archive_name)
        recipe = f'''pkgname=manida
pkgver={version}
pkgrel={pkgrel}
pkgdesc='Local music and Subsonic servers in one library'
arch=('x86_64')
url='https://github.com/D3SOX/Manida'
license=('AGPL-3.0-only')
depends=('mpv>=0.40' 'ffmpeg' 'libsecret' 'libpulse' 'sdl2' 'glib2' 'fontconfig' 'libxrender' 'libxtst' 'xdg-desktop-portal' 'xdg-desktop-portal-impl')
optdepends=('networkmanager: automatic metered-network detection')
options=('!strip' '!debug')
source=('{archive_name}::https://github.com/D3SOX/Manida/releases/download/{tag}/{archive_name}')
sha256sums=('{checksum}')

package() {{
    install -d "$pkgdir/opt" "$pkgdir/usr/bin"
    cp -a "$srcdir/manida" "$pkgdir/opt/manida"
    ln -s /opt/manida/bin/manida "$pkgdir/usr/bin/manida"
    install -Dm644 "$srcdir/manida/manida.desktop" "$pkgdir/usr/share/applications/io.github.d3sox.musicplayer.desktop"
    install -Dm644 "$srcdir/manida/manida.svg" "$pkgdir/usr/share/icons/hicolor/scalable/apps/io.github.d3sox.musicplayer.svg"
    install -Dm644 "$srcdir/manida/LICENSE" "$pkgdir/usr/share/licenses/manida/LICENSE"
}}
'''
        (build / "PKGBUILD").write_text(recipe)
        configuration = build / "makepkg.conf"
        configuration.write_text("source /etc/makepkg.conf\nPKGEXT='.pkg.tar.zst'\nCOMPRESSZST=(zstd -c -T2 -6 -)\n")
        subprocess.run(["makepkg", "--config", str(configuration), "--nodeps", "--nocheck", "--nosign", "--noconfirm"],
                       cwd=build, check=True)
        packages = list(build.glob(f"manida-{version}-{pkgrel}-x86_64.pkg.tar.*"))
        if len(packages) != 1:
            raise RuntimeError("Expected one Arch package")
        shutil.copyfile(packages[0], output / packages[0].name)
        shutil.copyfile(build / "PKGBUILD", output / "PKGBUILD")
    print(f"Created {archive.name} and {packages[0].name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--pkgrel", default="1")
    parser.add_argument("--release-tag")
    arguments = parser.parse_args()
    package(arguments.image.resolve(), arguments.output.resolve(), arguments.version, arguments.pkgrel, arguments.release_tag)
