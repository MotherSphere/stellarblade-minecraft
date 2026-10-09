import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('payload', Path(__file__).resolve().parents[1] / 'payload.py')
payload = importlib.util.module_from_spec(spec)
spec.loader.exec_module(payload)


class PayloadTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.lua = self.root / 'lua'
        (self.lua / 'Scripts').mkdir(parents=True)
        (self.lua / 'Scripts/main.lua').write_text("print('Loaded revision 57;')\n")
        (self.lua / 'enabled.txt').write_text('')
        subprocess.run(['git', 'init', '-q', str(self.lua)], check=True)
        self.git_add()
        self.jar = self.root / 'stellar-bridge-0.1.0.jar'
        self.jar.write_bytes(b'jar')
        self.dll = self.root / 'xinput1_3.dll'
        self.dll.write_bytes(b'MZ')
        self.fabric = self.root / payload.FABRIC_API
        with zipfile.ZipFile(self.fabric, 'w') as archive:
            archive.writestr(payload.FABRIC_LICENSE, 'Apache License')

    def git_add(self):
        subprocess.run(['git', '-C', str(self.lua), 'add', '-A'], check=True)

    def stage(self, out):
        payload.stage(out, lua=self.lua, jar=self.jar, dll=self.dll, fabric_api=self.fabric, release='0.1.0+test')

    def test_stages_every_piece_with_its_hash(self):
        original = payload.FABRIC_API_SHA512
        payload.FABRIC_API_SHA512 = hashlib.sha512(self.fabric.read_bytes()).hexdigest()
        self.addCleanup(setattr, payload, 'FABRIC_API_SHA512', original)
        (self.lua / '.gitignore').write_text('*.log\n')
        self.git_add()
        (self.lua / 'Scripts/debug.log').write_text('ignored')
        (self.lua / 'Scripts/notes.lua').write_text('untracked')
        out = self.root / 'out'
        self.stage(out)
        index = json.loads((out / 'payload.json').read_text())
        self.assertEqual(index['release'], '0.1.0+test')
        self.assertEqual(sorted(index['files']), [payload.FABRIC_API, 'licenses/LICENSE-fabric-api', 'lua/.gitignore',
                                                  'lua/Scripts/main.lua', 'lua/enabled.txt', 'stellar-bridge-0.1.0.jar',
                                                  'xinput1_3.dll'])
        for name, digest in index['files'].items():
            self.assertEqual(hashlib.sha256((out / name).read_bytes()).hexdigest(), digest)

    def test_refuses_another_fabric_api_or_a_machine_config(self):
        with self.assertRaises(SystemExit):
            self.stage(self.root / 'out')
        self.assertFalse((self.root / 'out').exists())
        payload.FABRIC_API_SHA512, original = hashlib.sha512(self.fabric.read_bytes()).hexdigest(), payload.FABRIC_API_SHA512
        self.addCleanup(setattr, payload, 'FABRIC_API_SHA512', original)
        (self.lua / 'Scripts/bridge_config.lua').write_text('return {}')
        self.git_add()
        with self.assertRaises(SystemExit):
            self.stage(self.root / 'out')
        self.assertFalse((self.root / 'out').exists())


if __name__ == '__main__':
    unittest.main()
