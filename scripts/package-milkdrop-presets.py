#!/usr/bin/env python3
"""Reproduce the bundled, offline MilkDrop preset and texture library."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PACKS = [
    ("presets-milkdrop-original", "e03b83e3338d8f1ed6cbcf908c719f249ef24288",
     "ed245a460871371de83b9a1cedcd8e0eb1dc99653b0b1864d4826911731a9b99", "Original MilkDrop"),
    ("presets-cream-of-the-crop", "0180df21f5e0bd39b9060cc5de420ed2f1f9e509",
     "b1f7d45b1ba36ba9881d6b6f18e12e1c8f355d8daa0fef60fe3ce0b0b046e746", "Cream of the Crop"),
    ("presets-milkdrop-texture-pack", "6368812f27bc747b517218fbf89d21d59afce4d9",
     "799ee35ec957ba9b4940b6d11e822a79b92ef29bb79cfa7219927f1d1265d034", None),
]


def package(output: Path, cache: Path):
    cache.mkdir(parents=True, exist_ok=True)
    contents = {}
    presets = []
    sources = []
    for repository, revision, checksum, collection in PACKS:
        archive = cache / f"{repository}-{revision}.tar.gz"
        if not archive.exists():
            url = f"https://codeload.github.com/projectM-visualizer/{repository}/legacy.tar.gz/{revision}"
            with urllib.request.urlopen(url, timeout=60) as response:
                archive.write_bytes(response.read())
        if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
            raise RuntimeError(f"Source checksum mismatch: {archive}")
        sources.append(f"https://github.com/projectM-visualizer/{repository}/tree/{revision}")
        with tarfile.open(archive) as source:
            for member in source.getmembers():
                if not member.isfile():
                    continue
                relative = PurePosixPath(*PurePosixPath(member.name).parts[1:])
                if relative.is_absolute() or ".." in relative.parts:
                    raise RuntimeError(f"Unexpected source path: {member.name}")
                data = source.extractfile(member).read()
                if collection and relative.suffix.lower() == ".milk":
                    path = f"presets/{collection}/{relative}"
                    contents[path] = data
                    category = str(relative.parent)
                    presets.append(dict(path=path, name=relative.stem, collection=collection,
                                        category="" if category == "." else category))
                elif not collection and relative.parts[0] == "textures":
                    contents[str(relative)] = data
                else:
                    contents[f"notices/{repository}/{relative}"] = data
    if len(presets) != 10347:
        raise RuntimeError(f"Expected the complete 552 + 9,795 preset collections, got {len(presets)}")
    for preset in sorted((ROOT / "native/presets").glob("*.milk")):
        path = f"presets/Nevumi/{preset.name}"
        contents[path] = preset.read_bytes()
        presets.append(dict(path=path, name=preset.stem, collection="Nevumi", category="Website"))
    contents["catalog.json"] = json.dumps(presets, ensure_ascii=False, separators=(",", ":")).encode()
    contents["notices/SOURCES.txt"] = ("MilkDrop presets and textures retain their upstream authorship and notices.\n\n" +
                                       "\n".join(sources) + "\n").encode()
    output.mkdir(parents=True, exist_ok=True)
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundled:
        for path, data in sorted(contents.items()):
            entry = zipfile.ZipInfo(path, date_time=(2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            bundled.writestr(entry, data, compresslevel=9)
    data = result.getvalue()
    (output / "milkdrop.zip").write_bytes(data)
    (output / "milkdrop-version.txt").write_text(hashlib.sha256(data).hexdigest() + "\n")
    (output / "milkdrop-notices.txt").write_bytes(contents["notices/SOURCES.txt"] + b"\n" +
        contents["notices/presets-cream-of-the-crop/LICENSE.md"])
    print(f"Bundled {len(presets):,} presets and {sum(p.startswith('textures/') for p in contents)} textures, {len(data):,} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "shared/src/commonMain/composeResources/files/visualizer")
    parser.add_argument("--cache", type=Path, default=ROOT / ".test-environment/milkdrop-preset-sources")
    args = parser.parse_args()
    package(args.output.resolve(), args.cache.resolve())
