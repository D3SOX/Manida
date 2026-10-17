#!/usr/bin/env python3
"""Ship the exact, verified source archives used by the projectM build, with Nevumi's patch to projectM."""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import urllib.request
from build_mpv import source_archive

ROOT = Path(__file__).resolve().parent.parent

def package(output):
    declarations = re.findall(r"FetchContent_Declare\((\w+)\s+URL (\S+)\s+URL_HASH SHA256=([a-f0-9]{64})", (ROOT / "native/CMakeLists.txt").read_text())
    if len(declarations) != 2: raise RuntimeError("Expected both pinned native source declarations")
    output.mkdir(parents=True, exist_ok=True)
    for name, url, checksum in declarations:
        destination = output / f"{name}-{url.rsplit('/', 1)[1][:12]}-source.tar.gz"
        cached = ROOT / f"shared/build/native/linux/_deps/{name}-subbuild/{name}-populate-prefix/src/archive.tar"
        if cached.is_file(): shutil.copyfile(cached, destination)
        else:
            with urllib.request.urlopen(url, timeout=60) as response, destination.open("wb") as archive:
                shutil.copyfileobj(response, archive)
        with destination.open("rb") as archive:
            if hashlib.file_digest(archive, "sha256").hexdigest() != checksum:
                destination.unlink()
                raise RuntimeError(f"Native source checksum mismatch for {name}")
        print("Verified " + destination.name)
    shutil.copyfile(ROOT / "native/projectm-crossfade.cmake", output / "projectm-crossfade.cmake")
    mpv = source_archive()
    shutil.copyfile(mpv, output / mpv.name)
    print("Verified " + mpv.name)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    package(parser.parse_args().output.resolve())
