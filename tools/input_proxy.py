#!/usr/bin/env python3
"""Install/remove the owned game-local XInput adapter; never change other loaders or launch options."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import struct

SUPPORTED_HASH = '573aaff1c9455f85ea6036ef6f6ccb0774de4164722a296276fbbfc7fa87545c'
MANIFEST = '.stellar-input-deployment.json'
FILES = {'xinput1_3.dll', 'stellar-input-directory.txt'}


def running_game():
    for path in Path('/proc').glob('[0-9]*/comm'):
        try:
            if path.read_text().strip().startswith('SB-Win64'): return True
        except (FileNotFoundError, ProcessLookupError): pass
    return False


def real(path, directory=False):
    path = path.absolute()
    if path.resolve() != path or not (path.is_dir() if directory else path.is_file()):
        raise ValueError(f'Expected a real {"directory" if directory else "file"} without symlinks: {path}')
    return path


def game_directory(exe):
    exe = real(exe)
    if exe.name != 'SB-Win64-Shipping.exe': raise ValueError('Expected SB-Win64-Shipping.exe')
    if running_game(): raise ValueError('Close Stellar Blade before changing the input adapter')
    return exe.parent


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''): value.update(block)
    return value.hexdigest()


def write_new(path, data):
    output = path.open('xb')
    try:
        with output: output.write(data)
    except BaseException:
        path.unlink()
        raise


def install(exe, dll, bridge):
    destination = game_directory(exe)
    if digest(exe) != SUPPORTED_HASH: raise ValueError('Unsupported Stellar Blade executable; input remains uninstalled')
    source = real(dll)
    if source.name != 'xinput1_3.dll': raise ValueError('Select the production xinput1_3.dll build')
    if source.stat().st_size > 4 * 1024 * 1024: raise ValueError('Input adapter exceeds size limit')
    data = source.read_bytes()
    if 'stellar_input_fake_host.exe'.encode('utf-16-le') in data:
        raise ValueError('The isolated fake-host adapter must never be installed in Stellar Blade')
    pe = struct.unpack_from('<I', data, 0x3c)[0] if len(data) >= 64 else len(data)
    if (data[:2] != b'MZ' or pe + 24 > len(data) or data[pe:pe + 4] != b'PE\0\0'
            or struct.unpack_from('<H', data, pe + 4)[0] != 0x8664
            or not struct.unpack_from('<H', data, pe + 22)[0] & 0x2000):
        raise ValueError('Input adapter must be an x64 Windows DLL')
    bridge = real(bridge, directory=True)
    attributes = bridge.stat()
    if attributes.st_uid != os.getuid() or attributes.st_mode & 0o077:
        raise ValueError('Bridge directory must belong to this user with mode 0700')
    if any(c in str(bridge) for c in '\n\r\0'): raise ValueError('Bridge path cannot contain control separators')
    for item in destination.iterdir():
        if item.name.lower() in FILES | {MANIFEST}:
            raise ValueError(f'Refusing existing input adapter content: {item}')
    outputs = {'stellar-input-directory.txt': ('Z:' + str(bridge) + '\n').encode('utf-8'), 'xinput1_3.dll': data}
    manifest = {'owner': 'stellar-input', 'version': 1, 'executable': SUPPORTED_HASH,
                'files': {name: hashlib.sha256(content).hexdigest() for name, content in outputs.items()}}
    created = []
    try:
        write_new(destination / MANIFEST, (json.dumps(manifest, indent=2) + '\n').encode('ascii'))
        created.append(destination / MANIFEST)
        for name, content in outputs.items():
            if running_game(): raise ValueError('Stellar Blade started during installation; input installation cancelled')
            path = destination / name
            write_new(path, content)
            created.append(path)
    except BaseException:
        for path in reversed(created): path.unlink()
        raise
    return destination / MANIFEST


def remove(exe):
    destination = game_directory(exe)
    manifest_path = real(destination / MANIFEST)
    with manifest_path.open('rb') as source: data = source.read(4097)
    if len(data) > 4096: raise ValueError('Invalid input ownership manifest')
    manifest = json.loads(data)
    if (not isinstance(manifest, dict) or set(manifest) != {'owner', 'version', 'executable', 'files'}
            or manifest['owner'] != 'stellar-input' or manifest['version'] != 1
            or manifest['executable'] != SUPPORTED_HASH or not isinstance(manifest['files'], dict)
            or set(manifest['files']) != FILES):
        raise ValueError('Input adapter ownership is not recognized')
    for name, expected in manifest['files'].items():
        if digest(real(destination / name)) != expected:
            raise ValueError(f'Refusing modified input adapter content: {name}')
    # Remove the loading entry first; configuration and ownership follow only after validation.
    for name in ('xinput1_3.dll', 'stellar-input-directory.txt', MANIFEST): (destination / name).unlink()
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('install', 'remove'))
    parser.add_argument('--game-exe', required=True, type=Path)
    parser.add_argument('--dll', type=Path, help='Built production xinput1_3.dll')
    parser.add_argument('--bridge-dir', type=Path)
    args = parser.parse_args()
    try:
        if args.command == 'install':
            if args.dll is None or args.bridge_dir is None: raise ValueError('Install requires --dll and --bridge-dir')
            result = install(args.game_exe, args.dll, args.bridge_dir)
        else:
            if args.dll is not None or args.bridge_dir is not None: raise ValueError('Remove accepts only --game-exe')
            result = remove(args.game_exe)
    except (OSError, ValueError) as error: parser.exit(1, f'Error: {error}\n')
    print(result)


if __name__ == '__main__': main()
