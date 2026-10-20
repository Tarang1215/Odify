"""Prevent startup crashes and bad Dart source in the owned Odify updater."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "odify_port_521", ROOT / "scripts" / "port_521.py"
)
port = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(port)


class StartupIsolationTests(unittest.TestCase):
    def test_vendor_tour_and_forced_updater_replaced_with_valid_dart(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / "lib/screens/main_shell_startup_prompts.dart"
            path.parent.mkdir(parents=True)
            path.write_text(
                "part of 'main_shell.dart';\n"
                "extension _MainShellStartupPrompts on _MainShellState {\n"
                "  Future<void> _checkUpgradeIntro() async {\n"
                "    await openVendorTour();\n"
                "  }\n"
                "  Future<bool> _checkForUpdates() async {\n"
                "    await UpdateChecker().checkForUpdate();\n"
                "    return true;\n"
                "  }\n"
                "  Future<void> _checkAppAnnouncement() async {\n"
                "    return;\n"
                "  }\n"
                "}\n",
                encoding="utf-8",
            )
            port.patch_startup_prompts(root)
            result = path.read_text(encoding="utf-8")
            self.assertNotIn("UpdateChecker()", result)
            self.assertNotIn("openVendorTour()", result)
            self.assertIn(
                "    if (_hasCheckedUpdate) return false;\n"
                "    _hasCheckedUpdate = true;\n",
                result,
            )
            self.assertIn(
                "    return false; // Never force installation of a vendor APK.\n",
                result,
            )
            self.assertNotIn(r";\\n", result)
            self.assertEqual(result.count("Future<bool> _checkForUpdates"), 1)
            self.assertIn("Future<void> _checkAppAnnouncement()", result)

    def test_vendor_updater_is_absent_even_when_release_channel_changes(self):
        source = (ROOT / "scripts/port_521.py").read_text(encoding="utf-8")
        self.assertIn("ODIFY_ENABLE_UPDATER", source)
        self.assertIn("static const String githubRepo = 'Tarang1215/Odify';", source)
        self.assertIn("Never force installation of a vendor APK", source)


if __name__ == "__main__":
    unittest.main()
