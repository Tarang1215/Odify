import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("odify_assemble_v521", ROOT / "scripts/assemble_v521.py")
assembler = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(assembler)


class UpstreamLockTests(unittest.TestCase):
    def test_base_ref_is_immutable_and_independent(self):
        lock = json.loads((ROOT / "config/upstream_lock.json").read_text())
        self.assertEqual(lock["base"]["version"], "5.2.1")
        self.assertEqual(
            lock["base"]["commit"],
            "8e4504e72ddcda6bdf6df5e1275396f46e9dfb9f",
        )
        self.assertFalse(lock["base"]["allowMovingTags"])
        self.assertEqual(lock["base"]["updatePolicy"], "manual-review-only")
        self.assertEqual(lock["app"]["id"], "com.odify.music")
        self.assertEqual(lock["app"]["updateRepo"], "Tarang1215/Odify")
        self.assertNotEqual(
            lock["base"]["repository"], lock["app"]["updateRepo"]
        )

    def test_method_replacement_removes_old_update_network_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            old_target = assembler.TARGET
            try:
                assembler.TARGET = pathlib.Path(tmp)
                snippet = assembler.TARGET / "lib/screens/main_shell_startup_prompts.dart"
                snippet.parent.mkdir(parents=True, exist_ok=True)
                snippet.write_text(
                    "extension Boot on State {\n"
                    "  Future<bool> _checkForUpdates() async {\n"
                    "    final checker = UpdateChecker();\n"
                    "    if (await checker.checkForUpdate() != null) { return true; }\n"
                    "    return false;\n"
                    "  }\n"
                    "}\n"
                )
                assembler.replace_method(
                    "lib/screens/main_shell_startup_prompts.dart",
                    "  Future<bool> _checkForUpdates() async ",
                    "  Future<bool> _checkForUpdates() async { return false; }",
                )
                result = snippet.read_text()
                self.assertNotIn("UpdateChecker()", result)
                self.assertIn("async { return false; }", result)
                self.assertTrue(result.endswith("}\n"))
            finally:
                assembler.TARGET = old_target


if __name__ == "__main__":
    unittest.main()
