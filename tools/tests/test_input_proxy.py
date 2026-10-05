import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock


class InputProxyTests(unittest.TestCase):
    def setUp(self):
        source = Path(__file__).resolve().parents[1] / 'input_proxy.py'
        self.assertTrue(source.is_file(), 'The input proxy installer is missing')
        spec = importlib.util.spec_from_file_location('input_proxy', source)
        self.proxy = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.proxy)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.game = self.root / 'game'
        self.game.mkdir()
        self.exe = self.game / 'SB-Win64-Shipping.exe'
        self.exe.write_bytes(b'test executable')
        self.proxy.SUPPORTED_HASH = hashlib.sha256(self.exe.read_bytes()).hexdigest()
        self.pad = self.root / 'xinput1_3.dll'
        pe = bytearray(256)
        pe[:2] = b'MZ'
        struct.pack_into('<I', pe, 0x3c, 128)
        pe[128:132] = b'PE\0\0'
        struct.pack_into('<H', pe, 132, 0x8664)
        struct.pack_into('<H', pe, 150, 0x2000)
        self.pad.write_bytes(pe)
        self.bridge = self.root / 'bridge'
        self.bridge.mkdir(mode=0o700)
        (self.game / 'dinput8.dll').write_bytes(b'preserve Xion')
        (self.game / 'dwmapi.dll').write_bytes(b'preserve UE4SS')
        closed = mock.patch.object(self.proxy, 'running_game', return_value=False)
        closed.start()
        self.addCleanup(closed.stop)

    def install(self):
        return self.proxy.install(self.exe, self.pad, self.bridge)

    def test_round_trip_preserves_other_loaders(self):
        self.install()
        self.assertEqual((self.game / 'xinput1_3.dll').read_bytes(), self.pad.read_bytes())
        self.assertEqual((self.game / 'stellar-input-directory.txt').read_text(), 'Z:' + str(self.bridge) + '\n')
        manifest = json.loads((self.game / self.proxy.MANIFEST).read_text())
        self.assertEqual(set(manifest['files']), {'xinput1_3.dll', 'stellar-input-directory.txt'})
        self.proxy.remove(self.exe)
        self.assertEqual({p.name for p in self.game.iterdir()}, {'SB-Win64-Shipping.exe', 'dinput8.dll', 'dwmapi.dll'})
        self.assertEqual((self.game / 'dinput8.dll').read_bytes(), b'preserve Xion')
        self.assertEqual((self.game / 'dwmapi.dll').read_bytes(), b'preserve UE4SS')

    def test_foreign_or_changed_files_are_never_overwritten_or_removed(self):
        foreign = self.game / 'xinput1_3.dll'
        foreign.write_bytes(b'foreign')
        with self.assertRaises(ValueError): self.install()
        self.assertEqual(foreign.read_bytes(), b'foreign')
        foreign.unlink()
        self.install()
        foreign.write_bytes(b'changed')
        with self.assertRaises(ValueError): self.proxy.remove(self.exe)
        self.assertEqual(foreign.read_bytes(), b'changed')

    def test_unsupported_game_or_dll_and_live_game_fail_before_writes(self):
        with mock.patch.object(self.proxy, 'running_game', return_value=True):
            with self.assertRaises(ValueError): self.install()
        with mock.patch.object(self.proxy, 'SUPPORTED_HASH', '0' * 64):
            with self.assertRaises(ValueError): self.install()
        self.pad.write_bytes(b'not a DLL')
        with self.assertRaises(ValueError): self.install()
        self.assertEqual(len(list(self.game.iterdir())), 3)

    def test_shared_bridge_and_symlinks_are_refused(self):
        self.bridge.chmod(0o755)
        with self.assertRaises(ValueError): self.install()
        self.bridge.chmod(0o700)
        self.exe.unlink()
        self.exe.symlink_to(self.pad)
        with self.assertRaises(ValueError): self.install()
        self.assertFalse((self.game / self.proxy.MANIFEST).exists())

    def test_fake_host_build_is_not_deployable_even_if_renamed(self):
        fixture = self.root / 'stellar_input_fixture.dll'
        fixture.write_bytes(self.pad.read_bytes())
        with self.assertRaises(ValueError): self.proxy.install(self.exe, fixture, self.bridge)
        self.pad.write_bytes(self.pad.read_bytes() + 'stellar_input_fake_host.exe'.encode('utf-16-le'))
        with self.assertRaises(ValueError): self.install()
        self.assertEqual(len(list(self.game.iterdir())), 3)

    def test_failed_copy_rolls_back_only_new_owned_files(self):
        original = self.proxy.write_new
        def fail_dll(path, data):
            if path.name == 'xinput1_3.dll': raise OSError('copy failed')
            return original(path, data)
        with mock.patch.object(self.proxy, 'write_new', side_effect=fail_dll):
            with self.assertRaises(OSError): self.install()
        self.assertEqual(len(list(self.game.iterdir())), 3)

    def test_failed_close_removes_the_created_dll(self):
        original = Path.open
        class FailedClose:
            def __init__(self, file): self.file = file
            def __enter__(self): return self.file.__enter__()
            def __exit__(self, *args):
                self.file.__exit__(*args)
                raise OSError('final flush failed')
        def open_with_failed_close(path, *args, **kwargs):
            result = original(path, *args, **kwargs)
            return FailedClose(result) if path.name == 'xinput1_3.dll' and args == ('xb',) else result
        with mock.patch.object(Path, 'open', new=open_with_failed_close):
            with self.assertRaises(OSError): self.install()
        self.assertEqual(len(list(self.game.iterdir())), 3)


if __name__ == '__main__': unittest.main()
