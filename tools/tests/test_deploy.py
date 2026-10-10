import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


class DeployTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.script = self.root / "tools/deploy.py"
        implementation = Path(__file__).resolve().parents[1] / "deploy.py"
        self.assertTrue(implementation.is_file(), "Deployment helper is not implemented")
        self.script.parent.mkdir()
        shutil.copyfile(implementation, self.script)
        self.source = self.root / "stellar/Mods/StellarCollisionProbe"
        (self.source / "Scripts").mkdir(parents=True)
        (self.source / "Scripts/main.lua").write_text("print('probe')\n")
        (self.source / "enabled.txt").write_text("")
        self.mods = self.root / "game/Mods"
        self.mods.mkdir(parents=True)
        (self.mods / "mods.txt").write_text("OtherMod : 1\n")
        self.destination = self.mods / "StellarCollisionProbe"

    def run_cli(self, command, succeeds=True, extra=()):
        result = subprocess.run(
            [sys.executable, str(self.script), command, "--mods-dir", str(self.mods), *extra],
            capture_output=True,
            text=True,
        )
        if succeeds:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertEqual((self.mods / "mods.txt").read_text(), "OtherMod : 1\n")
        return result

    def test_install_copies_files_and_records_their_hashes(self):
        self.run_cli("install")
        manifest = json.loads((self.destination / ".deployment.json").read_text())
        self.assertEqual(set(manifest["files"]), {"Scripts/main.lua", "enabled.txt"})
        for relative, digest in manifest["files"].items():
            content = (self.destination / relative).read_bytes()
            self.assertEqual(content, (self.source / relative).read_bytes())
            self.assertEqual(digest, hashlib.sha256(content).hexdigest())
        self.assertEqual(set(p.name for p in self.mods.iterdir()),
                         {"mods.txt", "StellarCollisionProbe"})

    def test_install_refuses_existing_foreign_directory(self):
        self.destination.mkdir()
        self.run_cli("install", succeeds=False)
        self.assertEqual(list(self.destination.iterdir()), [])
        (self.destination / "foreign.txt").write_text("keep")
        self.run_cli("install", succeeds=False)
        self.assertEqual((self.destination / "foreign.txt").read_text(), "keep")

    def test_install_refuses_overwriting_its_own_installation(self):
        self.run_cli("install")
        (self.source / "Scripts/main.lua").write_text("different")
        self.run_cli("install", succeeds=False)
        self.assertEqual((self.destination / "Scripts/main.lua").read_text(),
                         "print('probe')\n")

    def test_remove_deletes_only_the_owned_mod(self):
        self.run_cli("install")
        sibling = self.mods / "OtherMod"
        sibling.mkdir()
        (sibling / "keep.txt").write_text("keep")
        self.run_cli("remove")
        self.assertFalse(self.destination.exists())
        self.assertEqual((sibling / "keep.txt").read_text(), "keep")

    def test_remove_refuses_unowned_directory(self):
        self.destination.mkdir()
        (self.destination / "foreign.txt").write_text("keep")
        self.run_cli("remove", succeeds=False)
        self.assertEqual((self.destination / "foreign.txt").read_text(), "keep")

    def test_remove_refuses_modified_content(self):
        self.run_cli("install")
        (self.destination / "Scripts/main.lua").write_text("modified")
        self.run_cli("remove", succeeds=False)
        self.assertEqual((self.destination / "Scripts/main.lua").read_text(), "modified")
        self.assertTrue((self.destination / "enabled.txt").is_file())

    def test_remove_refuses_extra_content_including_empty_directories(self):
        self.run_cli("install")
        extra = self.destination / "extra.txt"
        extra.write_text("keep")
        self.run_cli("remove", succeeds=False)
        self.assertEqual(extra.read_text(), "keep")
        extra.unlink()
        extra.mkdir()
        self.run_cli("remove", succeeds=False)
        self.assertTrue(extra.is_dir())

    def test_remove_refuses_symlinked_files_and_directories(self):
        self.run_cli("install")
        script = self.destination / "Scripts/main.lua"
        script.unlink()
        script.symlink_to(self.source / "Scripts/main.lua")
        self.run_cli("remove", succeeds=False)
        self.assertTrue(script.is_symlink())
        script.unlink()
        script.parent.rmdir()
        script.parent.symlink_to(self.source / "Scripts", target_is_directory=True)
        self.run_cli("remove", succeeds=False)
        self.assertTrue(script.parent.is_symlink())
        self.assertEqual((self.source / "Scripts/main.lua").read_text(), "print('probe')\n")

    def test_remove_refuses_symlinked_mod_directory(self):
        self.destination.symlink_to(self.source, target_is_directory=True)
        self.run_cli("remove", succeeds=False)
        self.assertTrue(self.destination.is_symlink())
        self.assertTrue((self.source / "enabled.txt").exists())

    def test_install_refuses_symlinked_source_without_leaving_a_mod(self):
        script = self.source / "Scripts/main.lua"
        script.unlink()
        script.symlink_to(self.mods / "mods.txt")
        self.run_cli("install", succeeds=False)
        self.assertFalse(self.destination.exists())

    def test_failed_copy_rolls_back_the_new_directory(self):
        spec = importlib.util.spec_from_file_location("deployment_under_test", self.script)
        deployment = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(deployment)
        with mock.patch.object(deployment.shutil, "copyfile", side_effect=OSError("copy failed")):
            with self.assertRaises(OSError):
                deployment.install(self.mods)
        self.assertFalse(self.destination.exists())
        self.assertEqual((self.mods / "mods.txt").read_text(), "OtherMod : 1\n")

    def test_bridge_configuration_is_owned_and_lua_quoted(self):
        bridge = self.root / "bridge 'quoted'"
        bridge.mkdir(mode=0o700)
        self.run_cli("install", extra=("--bridge-dir", str(bridge)))
        config = self.destination / "Scripts/bridge_config.lua"
        result = subprocess.run(["lua", "-", str(config)],
                                 input="local c=dofile(arg[1]); io.write(c.directory)",
                                 capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "Z:" + str(bridge))
        manifest = json.loads((self.destination / ".deployment.json").read_text())
        self.assertEqual(manifest["files"]["Scripts/bridge_config.lua"],
                         hashlib.sha256(config.read_bytes()).hexdigest())
        self.run_cli("remove")
        self.assertTrue(bridge.is_dir())

    def test_running_game_refuses_both_commands_before_writes(self):
        spec = importlib.util.spec_from_file_location("deployment_under_test", self.script)
        deployment = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(deployment)
        for command in ("install", "remove"):
            with mock.patch.object(deployment, "running_game", return_value=True), \
                 mock.patch.object(sys, "argv", ["deploy.py", command, "--mods-dir", str(self.mods)]):
                with self.assertRaises(SystemExit) as stopped:
                    deployment.main()
            self.assertEqual(stopped.exception.code, 1)
            self.assertFalse(self.destination.exists())

    def test_bridge_configuration_refuses_symlink_or_shared_directory(self):
        bridge = self.root / "bridge"
        bridge.mkdir(mode=0o755)
        self.run_cli("install", succeeds=False, extra=("--bridge-dir", str(bridge)))
        self.assertFalse(self.destination.exists())
        bridge.chmod(0o700)
        alias = self.root / "alias"
        alias.symlink_to(bridge, target_is_directory=True)
        self.run_cli("install", succeeds=False, extra=("--bridge-dir", str(alias)))
        self.assertFalse(self.destination.exists())


if __name__ == "__main__":
    unittest.main()
