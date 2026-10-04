#!/usr/bin/env python3
"""Install or remove only this prototype's UE4SS Lua mod."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil


MOD_NAME = "StellarCollisionProbe"
MANIFEST = ".deployment.json"
SOURCE = Path(__file__).resolve().parents[1] / "stellar" / "Mods" / MOD_NAME


def inventory(root):
    if root.is_symlink() or not root.is_dir():
        raise ValueError(f"Expected a real directory: {root}")
    files, directories = {}, []
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            raise ValueError(f"Refusing a symbolic link: {path}")
        if path.is_dir():
            directories.append(relative)
        elif path.is_file():
            files[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
        else:
            raise ValueError(f"Refusing a nonregular file: {path}")
    return files, directories


def ownership(files, directories):
    return {"owner": MOD_NAME, "version": 1, "files": files, "directories": directories}


def install(mods_dir):
    files, directories = inventory(SOURCE)
    if MANIFEST in files or not {"Scripts/main.lua", "enabled.txt"} <= files.keys():
        raise ValueError("Source must contain Scripts/main.lua and enabled.txt, without a deployment manifest")
    destination = mods_dir / MOD_NAME
    destination.mkdir(exist_ok=False)
    try:
        for relative in directories:
            (destination / relative).mkdir()
        for relative in files:
            if relative != "enabled.txt":
                shutil.copyfile(SOURCE / relative, destination / relative)
        (destination / MANIFEST).write_text(
            json.dumps(ownership(files, directories), indent=2) + "\n", encoding="utf-8"
        )
        # Activate only after every script and the ownership manifest are present.
        shutil.copyfile(SOURCE / "enabled.txt", destination / "enabled.txt")
    except BaseException:
        shutil.rmtree(destination)
        raise
    return destination


def remove(mods_dir):
    destination = mods_dir / MOD_NAME
    files, directories = inventory(destination)
    if MANIFEST not in files:
        raise ValueError(f"Refusing an unowned directory: {destination}")
    manifest = json.loads((destination / MANIFEST).read_text(encoding="utf-8"))
    del files[MANIFEST]
    if manifest != ownership(files, directories):
        raise ValueError(f"Refusing modified or extra content: {destination}")
    shutil.rmtree(destination)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("install", "remove"))
    parser.add_argument("--mods-dir", required=True, type=Path, help="Existing UE4SS Mods directory")
    args = parser.parse_args()
    try:
        if args.mods_dir.is_symlink() or not args.mods_dir.is_dir():
            raise ValueError(f"Expected an existing real Mods directory: {args.mods_dir}")
        destination = install(args.mods_dir) if args.command == "install" else remove(args.mods_dir)
    except (OSError, ValueError) as error:
        parser.exit(1, f"Error: {error}\n")
    print(f"{'Installed' if args.command == 'install' else 'Removed'} {destination}")


if __name__ == "__main__":
    main()
