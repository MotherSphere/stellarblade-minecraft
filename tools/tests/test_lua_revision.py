from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class LuaRevisionTests(unittest.TestCase):
    def test_hints_name_the_loaded_revision(self):
        main = (ROOT / "stellar/Mods/StellarCollisionProbe/Scripts/main.lua").read_text()
        loaded = re.search(r"Loaded revision (\d+);", main).group(1)
        files = [*ROOT.glob("minecraft/src/main/**/*.java"), *ROOT.glob("docs/*.md"), ROOT / "README.md"]
        stale = [f"{path.relative_to(ROOT)}: {number}" for path in files
                 for number in re.findall(r"Lua revision (\d+)", path.read_text()) if number != loaded]
        self.assertEqual(stale, [], "Every hint names the Lua revision main.lua loads")


if __name__ == "__main__":
    unittest.main()
