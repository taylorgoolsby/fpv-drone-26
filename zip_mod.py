#!/usr/bin/env python3
"""Zip the relevant source files of a Minecraft Forge mod.

Includes: everything under src/main, build.gradle, settings.gradle.
Usage:  python zip_mod.py [project_dir] [output.zip]
"""
import sys
import zipfile
from pathlib import Path

project = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
output = Path(sys.argv[2] if len(sys.argv) > 2 else f"{project.name}-source.zip").resolve()

files = [project / "build.gradle", project / "settings.gradle"]
src_main = project / "src" / "main"
if src_main.is_dir():
    files += sorted(p for p in src_main.rglob("*") if p.is_file())
else:
    print(f"Warning: {src_main} not found")

count = 0
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as zf:
    for f in files:
        if not f.is_file():
            print(f"Skipping missing file: {f.relative_to(project)}")
            continue
        zf.write(f, f.relative_to(project))  # keeps paths like src/main/java/...
        count += 1

print(f"Wrote {count} files to {output}")