#!/usr/bin/env python3
"""Stage the installer's payload from this repository's build outputs: the Lua mod, the adapter DLL, the bridge JAR
and Fabric API with its licence, plus payload.json with every file's sha256. Build the installer with
SBMC_PAYLOAD=<folder> to embed it. Reads only; downloads nothing."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FABRIC_API = 'fabric-api-0.116.17+1.21.1.jar'
FABRIC_API_SHA512 = ('98c478217da19181f0e4df3ea6c2da6bdbda271c7544a4aea048513a49b444f1'
                     'ff5b71dc0df6303017c48e8ff69b7bb6af6179bdf0d0e09ab3c1ee08411857a8')
FABRIC_LICENSE = 'LICENSE-fabric-api'


def gradle_fabric_api():
    """Fabric API as Loom fetched it for the Minecraft build."""
    home = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
    found = sorted(home.glob(f'caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/0.116.17+1.21.1/*/{FABRIC_API}'))
    if not found:
        raise SystemExit(f'{FABRIC_API} is not in the Gradle cache: build minecraft/ first, or pass --fabric-api')
    return found[0]


def tracked(lua):
    """The Lua mod's files as git knows them: committed or staged, never untracked or ignored ones, so the payload
    holds what its release name points to."""
    listed = subprocess.run(['git', '-C', str(lua), 'ls-files', '-z', '--cached', '--', '.'],
                            capture_output=True, check=True).stdout
    return sorted(path for name in listed.decode().split('\0') if name and (path := lua / name).is_file())


def release():
    version = tomllib.loads((ROOT / 'installer/Cargo.toml').read_text())['package']['version']
    git = ['git', '-C', str(ROOT)]
    commit = subprocess.run([*git, 'rev-parse', '--short=7', 'HEAD'], capture_output=True, text=True, check=True).stdout.strip()
    dirty = subprocess.run([*git, 'status', '--porcelain', '--untracked-files=no'], capture_output=True, text=True, check=True).stdout
    return f'{version}+{commit}' + ('.dirty' if dirty else '')


def stage(out, *, lua, jar, dll, fabric_api, release):
    fabric = fabric_api.read_bytes()
    if hashlib.sha512(fabric).hexdigest() != FABRIC_API_SHA512:
        raise SystemExit(f'{fabric_api} is not Fabric API 0.116.17+1.21.1')
    with zipfile.ZipFile(fabric_api) as archive:
        license_text = archive.read(FABRIC_LICENSE)
    sources = tracked(lua)
    files = {f'lua/{path.relative_to(lua).as_posix()}': path.read_bytes() for path in sources}
    if any(path.is_symlink() for path in sources) or 'lua/Scripts/bridge_config.lua' in files:
        raise SystemExit('The Lua source must hold no links and no machine-specific bridge_config.lua')
    files.update({jar.name: jar.read_bytes(), 'xinput1_3.dll': dll.read_bytes(), FABRIC_API: fabric,
                  f'licenses/{FABRIC_LICENSE}': license_text})
    out.mkdir(parents=True)
    for name, data in files.items():
        (out / name).parent.mkdir(parents=True, exist_ok=True)
        (out / name).write_bytes(data)
    index = {'release': release, 'files': {name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())}}
    (out / 'payload.json').write_text(json.dumps(index, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path, help='New folder to stage into')
    parser.add_argument('--fabric-api', type=Path, help=f'{FABRIC_API} (default: the Gradle cache)')
    args = parser.parse_args()
    if args.out.exists():
        raise SystemExit(f'{args.out} exists: stage into a new folder')
    jars = [path for path in sorted((ROOT / 'minecraft/build/libs').glob('stellar-bridge-*.jar'))
            if not path.stem.endswith(('-sources', '-dev'))]
    dll = ROOT / 'build/native-windows/xinput1_3.dll'
    if len(jars) != 1 or not dll.is_file():
        raise SystemExit('Build the JAR and the Windows DLL first (python tools/check.py)')
    stage(args.out, lua=ROOT / 'stellar/Mods/StellarCollisionProbe', jar=jars[0], dll=dll,
          fabric_api=args.fabric_api or gradle_fabric_api(), release=release())
    print(f'Staged {args.out}')


if __name__ == '__main__':
    main()
