import hashlib
import json
from pathlib import Path
import plistlib
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts import package_ios_ipa as ipa


class IosPackagingTests(unittest.TestCase):
    def fixture(self, root):
        app = root / 'Runner.app'
        app.mkdir()
        info = {'CFBundleIdentifier': 'com.odify.music.ios', 'CFBundleExecutable': 'Runner',
                'CFBundleSupportedPlatforms': ['iPhoneOS'], 'UIBackgroundModes': ['audio'],
                'CFBundleShortVersionString': '1.0.0', 'CFBundleVersion': '1', 'MinimumOSVersion': '16.0'}
        (app / 'Info.plist').write_bytes(plistlib.dumps(info))
        for name in ['Runner', 'Frameworks/App.framework/App', 'Frameworks/Flutter.framework/Flutter']:
            path = app / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'fixture-not-a-real-compiled-app' * 4000)
            path.chmod(path.stat().st_mode | stat.S_IXUSR)
        return app, info

    @staticmethod
    def tool(command, **kwargs):
        return 'arm64\n' if command[1] == 'lipo' else 'Load command 0\n      platform IOS\n'

    def test_claimed_device_plist_cannot_hide_simulator_macho(self):
        with tempfile.TemporaryDirectory() as temp:
            app, _ = self.fixture(Path(temp))
            def simulator(command, **kwargs):
                return 'arm64\n' if command[1] == 'lipo' else 'platform IOSSIMULATOR\n'
            with patch.object(ipa.subprocess, 'check_output', side_effect=simulator):
                with self.assertRaisesRegex(RuntimeError, 'Wrong Apple platform'):
                    ipa.validate(app)

    def test_frameworks_and_aot_app_must_actually_exist(self):
        with tempfile.TemporaryDirectory() as temp:
            app, _ = self.fixture(Path(temp))
            (app / 'Frameworks/App.framework/App').unlink()
            with patch.object(ipa.subprocess, 'check_output', side_effect=self.tool):
                with self.assertRaisesRegex(RuntimeError, 'Missing compiled release binary'):
                    ipa.validate(app)

    def test_simulator_identity_is_rejected_before_invoking_apple_tools(self):
        with tempfile.TemporaryDirectory() as temp:
            app, info = self.fixture(Path(temp))
            info['CFBundleSupportedPlatforms'] = ['iPhoneSimulator']
            (app / 'Info.plist').write_bytes(plistlib.dumps(info))
            with self.assertRaisesRegex(RuntimeError, 'not simulator'):
                ipa.validate(app)

    def test_payload_keeps_runtime_and_permissions_but_excludes_extensions(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            app, _ = self.fixture(root)
            plugin = app / 'PlugIns/PlayerWidget.appex/Info.plist'
            plugin.parent.mkdir(parents=True)
            plugin.write_bytes(b'widget fixture')
            asset = app / 'Frameworks/App.framework/flutter_assets/sample.txt'
            asset.parent.mkdir(parents=True)
            asset.write_text('keep actual app resources')
            output = root / 'dist/Odify.ipa'
            with patch.object(ipa.subprocess, 'check_output', side_effect=self.tool):
                ipa.package(app, output)
            self.assertTrue(plugin.exists(), 'Packaging must not modify the compiled source app')
            with zipfile.ZipFile(output) as archive:
                self.assertEqual('keep actual app resources', archive.read('Payload/Odify.app/Frameworks/App.framework/flutter_assets/sample.txt').decode())
                self.assertFalse(any('/PlugIns/' in name for name in archive.namelist()))
                mode = archive.getinfo('Payload/Odify.app/Runner').external_attr >> 16
                self.assertTrue(mode & stat.S_IXUSR)
            report = json.loads(output.with_suffix('.build.json').read_text())
            self.assertEqual(hashlib.sha256(output.read_bytes()).hexdigest(), report['sha256'])


if __name__ == '__main__':
    unittest.main()
