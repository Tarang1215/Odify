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
from scripts import prepare_ios_sideload as preparation


class IosPackagingTests(unittest.TestCase):
    def test_prepare_rebrands_widget_override_and_bundles_the_original_license(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            files = {
                'ODIFY_VENDOR_BASE.txt': '8e4504e72ddcda6bdf6df5e1275396f46e9dfb9f',
                'LICENSE': 'MIT License fixture',
                'pubspec.yaml': 'flutter:\n  assets:\n    - assets/existing.txt\n',
                'ios/Runner.xcodeproj/project.pbxproj': 'PRODUCT_BUNDLE_IDENTIFIER = com.zarz.mornye;',
                'ios/PlayerWidget/PlayerWidget.xcconfig': 'PRODUCT_BUNDLE_IDENTIFIER = com.zarz.mornye.PlayerWidget\nPLAYER_WIDGET_APP_GROUP = group.com.zarz.mornye\n',
                'lib/constants/app_info.dart': "static const String version = '0.9.0';\nstatic const String buildNumber = '170';\n",
                'lib/widgets/odify_quick_access.dart': '  Widget build(BuildContext context) { return something; }\n',
                'lib/screens/settings/profile_settings_page.dart': 'builder: (_) => const OdifySyncSettingsScreen(),\n',
            }
            for name, value in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(value, encoding='utf-8')
            info = root / 'ios/Runner/Info.plist'
            info.parent.mkdir(parents=True)
            info.write_bytes(plistlib.dumps({'CFBundleDisplayName': 'Mornye'}))
            preparation.prepare(root)
            self.assertIn('com.odify.music.ios.PlayerWidget', (root / 'ios/PlayerWidget/PlayerWidget.xcconfig').read_text())
            self.assertNotIn('com.zarz.mornye', (root / 'ios/PlayerWidget/PlayerWidget.xcconfig').read_text())
            self.assertIn('    - LICENSE\n', (root / 'pubspec.yaml').read_text())
            self.assertEqual(['audio'], plistlib.loads(info.read_bytes())['UIBackgroundModes'])

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

    def test_native_release_requires_ipad_and_real_device_binary_without_flutter(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            app, info = self.fixture(root)
            info['UIDeviceFamily'] = [1, 2]
            (app / 'Info.plist').write_bytes(plistlib.dumps(info))
            (app / 'Frameworks/App.framework/App').unlink()
            (app / 'Frameworks/Flutter.framework/Flutter').unlink()
            with patch.object(ipa.subprocess, 'check_output', side_effect=self.tool):
                output = root / 'dist/Odify-native.ipa'
                ipa.package(app, output, runtime='native')
                report = json.loads(output.with_suffix('.build.json').read_text())
                self.assertTrue(report['nativeRelease'])
                self.assertFalse(report['releaseAOT'])
                info['UIDeviceFamily'] = [1]
                (app / 'Info.plist').write_bytes(plistlib.dumps(info))
                with self.assertRaisesRegex(RuntimeError, 'support iPad'):
                    ipa.validate(app, runtime='native')

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
