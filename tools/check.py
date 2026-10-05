#!/usr/bin/env python3
"""Run the project's offline checks. Does not launch games or install artifacts."""

import argparse
from pathlib import Path
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def run(command, *, cwd=ROOT, quiet=False):
    command = [str(value) for value in command]
    print('$ ' + shlex.join(command), flush=True)
    result = subprocess.run(command, cwd=cwd, text=True, capture_output=quiet)
    if result.returncode:
        if quiet:
            print(result.stdout, end='')
            print(result.stderr, end='', file=sys.stderr)
        raise SystemExit(result.returncode)


def main():
    components = ('lua', 'python', 'native', 'minecraft')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--only', choices=components, help='Run one component instead of the complete check')
    args = parser.parse_args()
    for component in (args.only,) if args.only else components:
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
        elif component == 'native':
            build = ROOT / 'build/native-host'
            run(['cmake', '-S', 'native', '-B', build, '-DCMAKE_BUILD_TYPE=Release'])
            run(['cmake', '--build', build, '--parallel'])
            run(['ctest', '--test-dir', build, '--output-on-failure'])
        else:
            run([ROOT / 'minecraft/gradlew', 'build'], cwd=ROOT / 'minecraft')
        print(f'PASS {component}', flush=True)


if __name__ == '__main__':
    try:
        main()
    except OSError as error:
        raise SystemExit(f'Check could not run: {error}')
