#!/usr/bin/env python3
"""Run the project's offline checks. Does not launch games or install artifacts."""

import argparse
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
# On Windows itself (the CI's Windows test job): Gradle's batch wrapper, and the Windows checks without Wine.
NATIVE_WINDOWS = os.name == 'nt'


def run(command, *, cwd=ROOT, quiet=False, env=None):
    command = [str(value) for value in command]
    print('$ ' + shlex.join(command), flush=True)
    result = subprocess.run(command, cwd=cwd, text=True, capture_output=quiet, env=env)
    if result.returncode:
        if quiet:
            print(result.stdout, end='')
            print(result.stderr, end='', file=sys.stderr)
        raise SystemExit(result.returncode)


def windows(toolchain, wine):
    """The Windows DLL and its isolated checks. On Linux: built with clang-cl/xwin, run under Wine or Proton in a
    throwaway prefix. On Windows: built with Visual Studio's ClangCL toolset (the release DLL's), run directly. Each
    check runs in a throwaway working directory (the checks write files there) with a copy of the protocol fixtures;
    the fake host runs beside both DLLs of the fixture build."""
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
        for name in ('stellar_input_fake_host.exe', 'xinput1_3.dll', 'stellar_input_fixture.dll'):
            shutil.copy2(outputs[1] / name, host)
        if NATIVE_WINDOWS:
            prefix, drive, env = [], '', None
        else:
            prefix, drive = [wine], 'Z:'
            env = {**os.environ, 'WINEPREFIX': str(scratch / 'prefix'), 'WINEDEBUG': '-all', 'DISPLAY': '', 'WAYLAND_DISPLAY': ''}
        tests = sorted(path for output in outputs for path in output.glob('stellar_*_tests.exe'))
        if not tests:
            raise SystemExit('Windows checks are missing')
        for path in tests:
            extra = [drive + str(scratch / 'raw')] if path.name == 'stellar_raw_hook_tests.exe' else []
            run([*prefix, path, *extra], cwd=scratch, env=env, quiet=True)
        run([*prefix, host / 'stellar_input_fake_host.exe'], cwd=host, env=env, quiet=True)


def windows_outputs(build):
    """Where a native Windows build put its programs: Visual Studio's multi-config Release folder, or Ninja's root."""
    return build / 'Release' if NATIVE_WINDOWS else build


def installer(crate):
    """Format, build, lint and test the installer. Its tests also run tools/deploy.py and tools/input_proxy.py against
    the Rust port on fake trees. When the JAR and the Windows DLL are built, the payload is staged from them and
    embedded, so the tests also check and install the real pieces (on fake trees)."""
    with tempfile.TemporaryDirectory(prefix='stellar-payload-') as scratch:
        env = dict(os.environ)
        staged = Path(scratch) / 'payload'
        result = subprocess.run([sys.executable, ROOT / 'tools/payload.py', staged], text=True, capture_output=True)
        if result.returncode == 0:
            env['SBMC_PAYLOAD'] = str(staged)
        else:
            print('installer: building without a payload: ' + (result.stderr or result.stdout).strip(), flush=True)
        run(['cargo', 'fmt', '--check'], cwd=crate)
        run(['cargo', 'build', '--locked'], cwd=crate, env=env)
        run(['cargo', 'clippy', '--locked', '--all-targets', '--', '-D', 'warnings'], cwd=crate, env=env)
        run(['cargo', 'test', '--locked'], cwd=crate, env=env)


def main():
    # The installer comes last: it embeds the JAR and DLL the steps before it build.
    components = ('lua', 'python', 'native', 'minecraft', 'windows', 'installer')
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
