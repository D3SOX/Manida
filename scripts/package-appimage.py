#!/usr/bin/env python3
"""Bundle the JVM and media backends in a verified AppImage."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
TOOL_SHA256 = "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
# Leave the host's libc, C++ runtime and graphics-driver libraries together.
SYSTEM = re.compile(r"^(ld-linux|lib(c|m|dl|pthread|rt|resolv|gcc_s|stdc\+\+)\.so|lib(GL|OpenGL|EGL|GLES|drm|gbm|vulkan|nvidia))")

def elf(path):
    with path.open("rb") as file: return file.read(4) == b"\x7fELF"

def package(image, output, version, tool, mpv_library=None):
    if not (image / "bin/nevumi").is_file(): raise ValueError("Run :desktopApp:createDistributable first")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-dev-[a-f0-9]+)?", version): raise ValueError("Invalid package version")
    if not tool.is_file():
        tool.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["gh", "release", "download", "1.9.1", "--repo", "AppImage/appimagetool",
                        "--pattern", "appimagetool-x86_64.AppImage", "--dir", str(tool.parent)], check=True)
    if hashlib.sha256(tool.read_bytes()).hexdigest() != TOOL_SHA256: raise RuntimeError("AppImage tool checksum mismatch")
    tool.chmod(0o755)
    output.mkdir(parents=True, exist_ok=True)
    destination = output / f"nevumi-{version}-x86_64.AppImage"
    if destination.exists(): raise FileExistsError(destination)
    with tempfile.TemporaryDirectory(prefix="nevumi-appimage-") as temporary:
        app = Path(temporary) / "Nevumi.AppDir"
        shutil.copytree(image, app / "opt/nevumi")
        binaries, libraries = app / "usr/bin", app / "usr/lib"
        binaries.mkdir(parents=True); libraries.mkdir(parents=True)
        for name in ["ffmpeg", "secret-tool", "pactl", "parec"]:
            source = shutil.which(name)
            if source is None: raise RuntimeError(f"Install {name} before packaging")
            shutil.copyfile(source, binaries / name); (binaries / name).chmod(0o755)
        mpv = mpv_library
        if mpv is None:
            listing = subprocess.check_output(["ldconfig", "-p"], text=True)
            mpv = next((Path(line.split("=>")[1].strip()) for line in listing.splitlines()
                        if line.strip().startswith("libmpv.so") and "x86-64" in line), None)
        if mpv is None: raise RuntimeError("Install libmpv before packaging")
        shutil.copyfile(mpv, libraries / mpv.name)
        (libraries / "libmpv.so").symlink_to(mpv.name)
        pending = [file for file in app.rglob("*") if file.is_file() and elf(file)]
        visited, origins = set(), {mpv}
        while pending:
            binary = pending.pop()
            if binary in visited: continue
            visited.add(binary)
            runtime = app / "opt/nevumi/lib/runtime/lib"
            paths = [libraries, runtime, runtime / "server"]
            result = subprocess.run(["ldd", str(binary)], capture_output=True, text=True,
                                    env=dict(os.environ, LD_LIBRARY_PATH=":".join(map(str, paths))))
            if "not found" in result.stdout: raise RuntimeError(f"Unresolved dependency for {binary.name}: {result.stdout}")
            for line in result.stdout.splitlines():
                match = re.match(r"\s*(\S+) => (/\S+) \(", line)
                if not match or SYSTEM.match(match[1]): continue
                source, target = Path(match[2]), libraries / match[1]
                if source.is_relative_to(app) or target.exists(): continue
                shutil.copyfile(source, target)
                pending.append(source); origins.add(source)
        licenses = app / "usr/share/licenses/nevumi"
        licenses.mkdir(parents=True)
        shutil.copyfile(ROOT / "LICENSE", licenses / "LICENSE")
        mpv_licenses = mpv.parent.parent / "share/licenses/mpv"
        if mpv_licenses.is_dir():
            shutil.copytree(mpv_licenses, licenses / "mpv")
        for source in origins:
            if shutil.which("dpkg-query"):
                result = subprocess.run(["dpkg-query", "-S", str(source)], capture_output=True, text=True)
                name = result.stdout.split(": ", 1)[0].split(":", 1)[0]
                document = Path("/usr/share/doc") / name / "copyright"
                if result.returncode == 0 and document.is_file(): shutil.copyfile(document, licenses / f"{name}.copyright")
            elif shutil.which("pacman"):
                result = subprocess.run(["pacman", "-Qqo", str(source)], capture_output=True, text=True)
                for name in result.stdout.splitlines() if result.returncode == 0 else []:
                    directory = Path("/usr/share/licenses") / name
                    if directory.is_dir(): shutil.copytree(directory, licenses / name, dirs_exist_ok=True)
        shutil.copyfile(ROOT / "packaging/nevumi.desktop", app / "nevumi.desktop")
        shutil.copyfile(ROOT / "packaging/nevumi.svg", app / "me.d3sox.nevumi.svg")
        (app / ".DirIcon").symlink_to("me.d3sox.nevumi.svg")
        launcher = app / "AppRun"
        launcher.write_text('''#!/bin/sh
nevumi_appdir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export PATH="$nevumi_appdir/usr/bin:$PATH"
export LD_LIBRARY_PATH="$nevumi_appdir/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$nevumi_appdir/opt/nevumi/bin/nevumi" "$@"
''')
        launcher.chmod(0o755)
        # Reuse the verified tool's embedded runtime rather than fetching a moving nightly runtime.
        offset = int(subprocess.check_output([str(tool), "--appimage-offset"], text=True).strip())
        if not 0 < offset < tool.stat().st_size: raise RuntimeError("Invalid AppImage runtime offset")
        runtime = Path(temporary) / "runtime-x86_64"
        with tool.open("rb") as source: runtime.write_bytes(source.read(offset))
        subprocess.run([str(tool), "--appimage-extract-and-run", "--runtime-file", str(runtime), "--no-appstream", str(app), str(destination)],
                       env=dict(os.environ, ARCH="x86_64", VERSION=version), check=True)
    print("Created " + destination.name)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--tool", type=Path, default=ROOT / ".test-environment/appimage-tools/appimagetool-x86_64.AppImage")
    parser.add_argument("--mpv-library", type=Path)
    args = parser.parse_args()
    package(args.image.resolve(), args.output.resolve(), args.version, args.tool.resolve(),
            args.mpv_library.resolve() if args.mpv_library else None)
