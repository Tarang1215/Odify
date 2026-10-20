import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "strip_release_test_plugin",
    ROOT / "scripts" / "strip_release_test_plugin.py",
)
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)

REGISTRANT = """package io.flutter.plugins;
public final class GeneratedPluginRegistrant {
  public static void registerWith(FlutterEngine flutterEngine) {
    try {
      flutterEngine.getPlugins().add(new foo.music.RealMusicPlugin());
    } catch (Exception e) {
      Log.e(TAG, "Error registering real plugin", e);
    }
    try {
      flutterEngine.getPlugins().add(new dev.flutter.plugins.integration_test.IntegrationTestPlugin());
    } catch (Exception e) {
      Log.e(TAG, "Error registering integration_test", e);
    }
    try {
      flutterEngine.getPlugins().add(new foo.artwork.EmbeddedAlbumPlugin());
    } catch (Exception e) {
      Log.e(TAG, "Error registering artwork plugin", e);
    }
  }
}
"""


class ReleaseRegistrantTests(unittest.TestCase):
    def test_removes_only_debug_plugin(self):
        code = tool.without_dev_plugin(REGISTRANT)
        self.assertNotIn(tool.PLUGIN, code)
        self.assertIn("RealMusicPlugin", code)
        self.assertIn("EmbeddedAlbumPlugin", code)
        self.assertIn("registerWith", code)
        self.assertEqual(code.count("catch (Exception e)"), 2)

    def test_fails_closed_if_missing(self):
        with self.assertRaisesRegex(ValueError, "Expected one"):
            tool.without_dev_plugin("class NoRegistrar {}")

    def test_fails_closed_on_ambiguous_multiple(self):
        with self.assertRaisesRegex(ValueError, "Expected one"):
            tool.without_dev_plugin(REGISTRANT.replace(
                "    try {\n      flutterEngine.getPlugins().add(new foo.artwork",
                "    try {\n      flutterEngine.getPlugins().add(new dev.flutter.plugins.integration_test.IntegrationTestPlugin());\n"
                "    } catch (Exception e) {\n      Log.e(TAG, \"extra\", e);\n"
                "    }\n    try {\n      flutterEngine.getPlugins().add(new foo.artwork",
            ))


if __name__ == "__main__":
    unittest.main()
