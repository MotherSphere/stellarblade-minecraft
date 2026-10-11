#!/usr/bin/env python3
"""Run the project's offline checks. Does not launch games or install artifacts."""

import argparse
import os
import re
from pathlib import Path
import shlex
import hashlib
import shutil
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
# The pack builder's sidecars, the SHA256SUMS over them and NOTICES.txt (their licence texts); the installer build
# embeds its target's one with its digest and the notices (SBMC_EVE_EXTRACT names this folder). The keys are .NET runtime identifiers, the values the release asset names.
EVE_EXTRACT = ROOT / 'build/eve-extract'
SIDECARS = {'linux-x64': 'eve-extract-linux-x64', 'win-x64': 'eve-extract-win-x64.exe'}
# On Windows itself (the CI's Windows test job): Gradle's batch wrapper, and the Windows checks without Wine.
NATIVE_WINDOWS = os.name == 'nt'
# A path crate in cargo tree's output: (/home/...) on Linux, (C:\...) on Windows.
LOCAL_CRATE = re.compile(r'\((?:/|[A-Za-z]:[\\/])')


def run(command, *, cwd=ROOT, quiet=False, env=None):
    command = [str(value) for value in command]
    print('$ ' + shlex.join(command), flush=True)
    result = subprocess.run(command, cwd=cwd, encoding='utf-8', errors='replace', capture_output=quiet, env=env)
    if result.returncode:
        if quiet:
            print(result.stdout, end='')
            print(result.stderr, end='', file=sys.stderr)
        raise SystemExit(result.returncode)


def windows(toolchain, wine):
    """The Windows DLL and its isolated checks. On Linux: built with clang-cl/xwin, run under Wine or Proton in a
    throwaway prefix. On Windows: built with Visual Studio's ClangCL toolset (the release DLL's), run directly. Each
    check runs in a throwaway working directory (the checks write files there) with a copy of the protocol fixtures;
    the fake host runs beside both DLLs of the fixture build, then the overlay host, which imports the fixture DLL and
    hot-patches its exports as Steam's overlay does."""
    builds = ((ROOT / 'build/native-windows', []), (ROOT / 'build/native-windows-fixture', ['-DSTELLAR_INPUT_BUILD_FIXTURE=ON']))
    for build, extra in builds:
        if NATIVE_WINDOWS:
            run(['cmake', '-S', 'native', '-B', build, '-A', 'x64', '-T', 'ClangCL', *extra])
            run(['cmake', '--build', build, '--config', 'Release', '--parallel'])
        else:
            run(['cmake', '-S', 'native', '-B', build, '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release',
                 f'-DCMAKE_TOOLCHAIN_FILE={toolchain}', *extra])
            run(['cmake', '--build', build])
    outputs = [windows_outputs(build) for build, _ in builds]
    with tempfile.TemporaryDirectory(prefix='stellar-windows-check-') as scratch:
        scratch = Path(scratch)
        shutil.copytree(ROOT / 'protocol', scratch / 'protocol')
        (scratch / 'raw').mkdir()
        host = scratch / 'host'
        host.mkdir()
        for name in ('stellar_input_fake_host.exe', 'stellar_input_overlay_host.exe', 'xinput1_3.dll',
                     'stellar_input_fixture.dll'):
            shutil.copy2(outputs[1] / name, host)
        if NATIVE_WINDOWS:
            prefix, drive, env = [], '', None
        else:
            prefix, drive = [wine], 'Z:'
            env = {**os.environ, 'WINEPREFIX': str(scratch / 'prefix'), 'WINEDEBUG': '-all', 'DISPLAY': '', 'WAYLAND_DISPLAY': ''}
            # Without a display Wine refuses top-level windows, which the overlay host needs, unless the prefix names its
            # null display driver; the prefix's session then ends so the checks start one that reads it.
            run([wine, 'reg', 'add', r'HKCU\Software\Wine\Drivers', '/v', 'Graphics', '/d', 'null', '/f'], cwd=scratch,
                env=env, quiet=True)
            run([Path(wine).with_name('wineserver'), '-w'], cwd=scratch, env=env, quiet=True)
        tests = sorted(path for output in outputs for path in output.glob('stellar_*_tests.exe'))
        if not tests:
            raise SystemExit('Windows checks are missing')
        for path in tests:
            extra = [drive + str(scratch / 'raw')] if path.name == 'stellar_raw_hook_tests.exe' else []
            run([*prefix, path, *extra], cwd=scratch, env=env, quiet=True)
        run([*prefix, host / 'stellar_input_fake_host.exe'], cwd=host, env=env, quiet=True)
        run([*prefix, host / 'stellar_input_overlay_host.exe'], cwd=host, env=env, quiet=True)


def windows_outputs(build):
    """Where a native Windows build put its programs: Visual Studio's multi-config Release folder, or Ninja's root."""
    return build / 'Release' if NATIVE_WINDOWS else build


def eve_extract(wine):
    """EveExtract, the avatar pack builder's sidecar (tools/eve-extract): its input checks on synthetic trees, then one
    self-contained single-file build per OS into build/eve-extract with their SHA256SUMS, and a smoke test of each on
    synthetic inputs. The Windows build and the input checks run again as Windows programs under Wine when it is given
    (directly on Windows)."""
    dotnet = shutil.which('dotnet')
    project = ROOT / 'tools/eve-extract'
    if not (project / 'CUE4Parse/CUE4Parse/CUE4Parse.csproj').is_file():
        raise SystemExit('tools/eve-extract/CUE4Parse is empty: initialise the submodule first')
    # LC_ALL=C: git and MSBuild print as on CI, whose English messages MSBuild can read as errors.
    env = {**os.environ, 'DOTNET_CLI_TELEMETRY_OPTOUT': '1', 'DOTNET_NOLOGO': '1', 'LC_ALL': 'C'}
    run([dotnet, 'run', '--project', project / 'tests'], env=env, quiet=True)
    shutil.rmtree(EVE_EXTRACT, ignore_errors=True)
    sums = []
    for rid, name in SIDECARS.items():
        publish = EVE_EXTRACT / rid
        run([dotnet, 'publish', project / 'EveExtract.csproj', '-c', 'Release', '-r', rid, '-o', publish], env=env, quiet=True)
        (publish / ('eve-extract.exe' if rid == 'win-x64' else 'eve-extract')).replace(EVE_EXTRACT / name)
        shutil.rmtree(publish)
        sums.append(f'{hashlib.sha256((EVE_EXTRACT / name).read_bytes()).hexdigest()}  {name}\n')
    (EVE_EXTRACT / 'SHA256SUMS').write_text(''.join(sums))
    # What the sidecar's --licenses prints, the same for both: the installers embed it with their sidecar and print it.
    with tempfile.TemporaryDirectory(prefix='stellar-eve-extract-bundle-') as bundle:
        own = EVE_EXTRACT / SIDECARS['win-x64' if NATIVE_WINDOWS else 'linux-x64']
        notices = subprocess.run([own, '--licenses'], env={**env, 'DOTNET_BUNDLE_EXTRACT_BASE_DIR': bundle},
                                 capture_output=True, encoding='utf-8', check=True).stdout
    (EVE_EXTRACT / 'NOTICES.txt').write_text(notices, encoding='utf-8', newline='\n')
    if not NATIVE_WINDOWS:
        eve_extract_smoke([EVE_EXTRACT / SIDECARS['linux-x64']], env)
    if not NATIVE_WINDOWS and not wine:
        print('SKIP eve-extract as a Windows program (no --wine)', flush=True)
        return
    with tempfile.TemporaryDirectory(prefix='stellar-eve-extract-windows-') as scratch:
        scratch = Path(scratch)
        tests = scratch / 'tests'
        run([dotnet, 'publish', project / 'tests', '-c', 'Release', '-r', 'win-x64', '--self-contained', '-o', tests],
            env=env, quiet=True)
        prefix, drive, windows_env = [], '', env
        if not NATIVE_WINDOWS:
            prefix, drive = [wine], 'Z:'
            windows_env = {**env, 'WINEPREFIX': str(scratch / 'prefix'), 'WINEDEBUG': '-all', 'DISPLAY': '', 'WAYLAND_DISPLAY': ''}
        # Where the mod-container lookup meets Windows' '\' separators.
        run([*prefix, tests / 'EveExtract.Tests.exe'], env=windows_env, quiet=True)
        eve_extract_smoke([*prefix, EVE_EXTRACT / SIDECARS['win-x64']], windows_env, drive)


def eve_extract_smoke(command, env, drive=''):
    """The sidecar on synthetic inputs: help, licences, the exit codes of bad arguments and of missing or unusable
    inputs (2 and 3), and no output folder made. Paths reach a Windows build under Wine through drive Z:."""
    with tempfile.TemporaryDirectory(prefix='stellar-eve-extract-') as scratch:
        scratch = Path(scratch)
        # Where the bundle would unpack a native library (it carries none); the Windows host needs the folder to exist.
        (scratch / 'bundle').mkdir()
        env = {**env, 'DOTNET_BUNDLE_EXTRACT_BASE_DIR': drive + str(scratch / 'bundle')}
        paks = scratch / 'Paks'
        paks.mkdir()
        (paks / 'pakchunk0-WinGDK.utoc').write_bytes(b'')
        # A usmap v0 that parses (no compression, then no names, enums or structs), and one of an unknown version.
        (scratch / 'empty.usmap').write_bytes(b'\xc4\x30\x00\x00' + struct.pack('<II', 12, 12) + bytes(12))
        (scratch / 'future.usmap').write_bytes(b'\xc4\x30\xff')
        (scratch / 'oodle').write_text('not a library')
        work = scratch / 'work'

        def inputs(mode='json', paks=paks, usmap='empty.usmap'):
            return ['--paks', drive + str(paks), '--usmap', drive + str(scratch / usmap), '--oodle',
                    drive + str(scratch / 'oodle'), '--out', drive + str(work), mode, 'SB/Content/Data/ItemTable']

        for code, args, text in ((0, ['--help'], 'never downloaded'), (2, [], 'eve-extract --paks'),
                                 (0, ['--licenses'], 'Apache License'), (0, ['--licenses'], '==> dotnet.THIRD-PARTY-NOTICES'), (2, inputs(mode='inventory'), 'the mode must be'),
                                 (3, inputs(paks=scratch / 'absent'), 'no Paks folder'),
                                 (3, inputs(usmap='future.usmap'), 'not a readable usmap'),
                                 (3, inputs(), 'not a loadable Oodle library')):
            line = [str(value) for value in (*command, *args)]
            print('$ ' + shlex.join(line), flush=True)
            result = subprocess.run(line, env=env, encoding='utf-8', errors='replace', capture_output=True)
            if result.returncode != code or text not in result.stdout + result.stderr:
                print(result.stdout, end='')
                print(result.stderr, end='', file=sys.stderr)
                raise SystemExit(f'expected exit code {code} and "{text}", got exit code {result.returncode}')
        if work.exists():
            raise SystemExit('eve-extract made its output folder before its inputs passed')


def installer(crate):
    """Format, build, lint and test the installer. Its tests also run tools/deploy.py and tools/input_proxy.py against
    the Rust port on fake trees. When the JAR and the Windows DLL are built, the payload is staged from them and
    embedded, so the tests also check and install the real pieces (on fake trees)."""
    with tempfile.TemporaryDirectory(prefix='stellar-payload-') as scratch:
        env = dict(os.environ)
        staged = Path(scratch) / 'payload'
        result = subprocess.run([sys.executable, ROOT / 'tools/payload.py', staged], encoding='utf-8', errors='replace',
                                capture_output=True)
        if result.returncode == 0:
            env['SBMC_PAYLOAD'] = str(staged)
        else:
            print('installer: building without a payload: ' + (result.stderr or result.stdout).strip(), flush=True)
        # The sidecars the eve-extract step built: the installer embeds this OS's one.
        if (EVE_EXTRACT / 'SHA256SUMS').is_file():
            env['SBMC_EVE_EXTRACT'] = str(EVE_EXTRACT)
        run(['cargo', 'fmt', '--check'], cwd=crate)
        run(['cargo', 'build', '--locked'], cwd=crate, env=env)
        run(['cargo', 'clippy', '--locked', '--all-targets', '--', '-D', 'warnings'], cwd=crate, env=env)
        run(['cargo', 'test', '--locked'], cwd=crate, env=env)
        crate_notices(crate)


def crate_notices(crate):
    """Every crate compiled into either release build (procedural macros leave no code) is named, with its version,
    in THIRD-PARTY-NOTICES.md, which --licenses prints."""
    notices = (ROOT / 'THIRD-PARTY-NOTICES.md').read_text(encoding='utf-8')
    missing = set()
    for target in ('x86_64-unknown-linux-gnu', 'x86_64-pc-windows-msvc'):
        tree = subprocess.run(['cargo', 'tree', '--locked', '-e', 'normal', '--target', target, '--prefix', 'none',
                               '-f', '{p}'], cwd=crate, encoding='utf-8', errors='replace', capture_output=True,
                              check=True).stdout
        for line in tree.splitlines():
            name, version = line.split()[:2]
            if '(proc-macro)' not in line and not LOCAL_CRATE.search(line) and f'{name} {version[1:]}' not in notices:
                missing.add(f'{name} {version[1:]}')
    if missing:
        raise SystemExit('THIRD-PARTY-NOTICES.md does not list: ' + ', '.join(sorted(missing)))


def main():
    # The installer comes last: it embeds the JAR, the DLL and the sidecar the steps before it build.
    components = ('lua', 'python', 'native', 'minecraft', 'windows', 'eve-extract', 'installer')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--only', choices=components, help='Run one component instead of the complete check')
    parser.add_argument('--windows-toolchain', default=os.environ.get('STELLAR_WINDOWS_TOOLCHAIN'),
                        help='clang-cl/xwin CMake toolchain file (default: $STELLAR_WINDOWS_TOOLCHAIN)')
    parser.add_argument('--wine', default=os.environ.get('STELLAR_WINE'),
                        help='Wine or Proton wine binary for the Windows checks (default: $STELLAR_WINE)')
    args = parser.parse_args()
    for component in (args.only,) if args.only else components:
        if component == 'windows' and not NATIVE_WINDOWS and not (args.windows_toolchain and args.wine):
            if args.only:
                raise SystemExit('The Windows check needs --windows-toolchain and --wine')
            print('SKIP windows (no --windows-toolchain and --wine)', flush=True)
            continue
        if component == 'lua':
            tests = sorted((ROOT / 'stellar/tests').glob('*_test.lua'))
            sources = sorted((ROOT / 'stellar/Mods/StellarCollisionProbe/Scripts').glob('*.lua'))
            if not tests or not sources:
                raise SystemExit('Lua sources or checks are missing')
            for path in sources + tests:
                run(['luac', '-p', path.relative_to(ROOT)], quiet=True)
            for path in tests:
                run(['lua', path.relative_to(ROOT)], quiet=True)
        elif component == 'python':
            run([sys.executable, '-m', 'unittest', 'discover', '-s', 'tools/tests', '-p', 'test_*.py'])
        elif component == 'installer':
            installer(ROOT / 'installer')
        elif component == 'eve-extract':
            if not shutil.which('dotnet'):
                if args.only:
                    raise SystemExit('The eve-extract check needs the .NET 10 SDK (dotnet on PATH)')
                print('SKIP eve-extract (no dotnet on PATH)', flush=True)
                continue
            eve_extract(args.wine)
        elif component == 'native':
            build = ROOT / 'build/native-host'
            run(['cmake', '-S', 'native', '-B', build, '-DCMAKE_BUILD_TYPE=Release'])
            run(['cmake', '--build', build, '--parallel'])
            run(['ctest', '--test-dir', build, '--output-on-failure'])
        elif component == 'windows':
            windows(args.windows_toolchain, args.wine)
        else:
            gradlew = ROOT / ('minecraft/gradlew.bat' if NATIVE_WINDOWS else 'minecraft/gradlew')
            run([gradlew, 'build'], cwd=ROOT / 'minecraft')
            # The shared terrain and EVE text both ways: Java writes, Lua reads and answers, Java reads the answer. The
            # Windows runner has no Lua; the Linux job runs this exchange.
            if NATIVE_WINDOWS and not shutil.which('lua'):
                print('SKIP Java-Lua protocol exchange (no lua on PATH)', flush=True)
            else:
                with tempfile.TemporaryDirectory() as exchange:
                    java = [gradlew, '-q', 'protocolTest', f'--args=. {exchange}']
                    run(java, cwd=ROOT / 'minecraft')
                    run(['lua', 'stellar/tests/protocol_test.lua', exchange])
                    run(java, cwd=ROOT / 'minecraft')
        print(f'PASS {component}', flush=True)


if __name__ == '__main__':
    try:
        main()
    except OSError as error:
        raise SystemExit(f'Check could not run: {error}')
