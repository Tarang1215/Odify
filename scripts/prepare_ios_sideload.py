#!/usr/bin/env python3
"""Prepare only the pinned Flutter assembly for an iPhone release build."""
from pathlib import Path
import argparse
import json
import plistlib
import re

BUNDLE = 'com.odify.music.ios'


def prepare(root: Path):
    root = root.resolve()
    if '8e4504e72ddcda6bdf6df5e1275396f46e9dfb9f' not in (root / 'ODIFY_VENDOR_BASE.txt').read_text(encoding='utf-8'):
        raise RuntimeError('Expected the pinned, attributed Odify vendor assembly')
    info_path = root / 'ios/Runner/Info.plist'
    info = plistlib.loads(info_path.read_bytes())
    info.update(CFBundleDisplayName='Odify', CFBundleName='Odify', UIFileSharingEnabled=True, LSSupportsOpeningDocumentsInPlace=True)
    info['UIBackgroundModes'] = list(dict.fromkeys(info.get('UIBackgroundModes', []) + ['audio']))
    for key, value in list(info.items()):
        if isinstance(value, str) and 'UsageDescription' in key:
            info[key] = value.replace('Mornye', 'Odify')
    for entry in info.get('CFBundleURLTypes', []):
        entry['CFBundleURLName'] = BUNDLE
        if 'mornye' in entry.get('CFBundleURLSchemes', []):
            entry['CFBundleURLSchemes'] = list(dict.fromkeys(entry['CFBundleURLSchemes'] + ['odify']))
    info_path.write_bytes(plistlib.dumps(info, sort_keys=False))
    project = root / 'ios/Runner.xcodeproj/project.pbxproj'
    source = project.read_text(encoding='utf-8')
    if 'com.zarz.mornye' not in source:
        raise RuntimeError('Unrecognized iOS bundle identity')
    project.write_text(source.replace('com.zarz.mornye', BUNDLE), encoding='utf-8')
    # The widget's target overrides the project identity in its own xcconfig.
    # Xcode validates it before we remove guest-inapplicable extensions from
    # the final IPA, so both target identities must share the same prefix.
    widget_config = root / 'ios/PlayerWidget/PlayerWidget.xcconfig'
    source = widget_config.read_text(encoding='utf-8')
    if 'PRODUCT_BUNDLE_IDENTIFIER = com.zarz.mornye.PlayerWidget' not in source:
        raise RuntimeError('Unrecognized embedded widget identity')
    widget_config.write_text(source.replace('com.zarz.mornye', BUNDLE), encoding='utf-8')
    identity = root / 'lib/constants/app_info.dart'
    source = identity.read_text(encoding='utf-8')
    for old, new in [("static const String version = '0.9.0';", "static const String version = '1.0.0';"),
                     ("static const String buildNumber = '170';", "static const String buildNumber = '1';")]:
        if source.count(old) != 1:
            raise RuntimeError('Unrecognized Odify version anchor')
        source = source.replace(old, new)
    identity.write_text(source, encoding='utf-8')
    pubspec = root / 'pubspec.yaml'
    source = pubspec.read_text(encoding='utf-8')
    if source.count('  assets:\n') != 1 or not (root / 'LICENSE').is_file():
        raise RuntimeError('Missing original license or Flutter assets anchor')
    if '    - LICENSE\n' not in source:
        source = source.replace('  assets:\n', '  assets:\n    - LICENSE\n', 1)
    pubspec.write_text(source, encoding='utf-8')
    # These overlay cards depend on Android MediaStore/yt-dlp channels.
    # Keep the real iOS library and search rather than dead Android buttons.
    quick = root / 'lib/widgets/odify_quick_access.dart'
    source = quick.read_text(encoding='utf-8')
    anchor = '  Widget build(BuildContext context) {'
    if anchor not in source:
        raise RuntimeError('Missing quick-access capability gate anchor')
    source = "import 'dart:io';\n" + source.replace(anchor, anchor + '\n    if (Platform.isIOS) return const SizedBox.shrink();', 1)
    seen, lines = set(), []
    for line in source.splitlines():
        if line.startswith('import '):
            if line in seen:
                continue
            seen.add(line)
        lines.append(line)
    quick.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    profile = root / 'lib/screens/settings/profile_settings_page.dart'
    source = profile.read_text(encoding='utf-8').replace("import 'package:mornye/widgets/odify_sync_settings.dart';", '')
    old = 'builder: (_) => const OdifySyncSettingsScreen(),'
    if source.count(old) != 1:
        raise RuntimeError('Missing Android-only Drive account screen anchor')
    source = source.replace(old, '''builder: (_) => Scaffold(
                                appBar: AppBar(title: const Text('Drive 음악 가져오기')),
                                body: const Padding(padding: EdgeInsets.all(24), child: Text(
                                  'iPhone의 파일 앱에서 Google Drive를 활성화한 뒤, Odify 보관함의 파일·폴더 가져오기에서 음악을 선택해 주세요.\\n\\n이 iOS 빌드에는 Android의 Google 계정 직접 동기화가 포함되어 있지 않습니다.'
                                )),
                              ),''')
    source = source.replace("title: const Text('Google 계정 · Drive 동기화'),", "title: const Text('Drive 음악 가져오기'),")
    source = source.replace("subtitle: const Text('재생목록과 좋아요 동기화 관리'),", "subtitle: const Text('iOS 파일 앱을 통해 음악 선택'),")
    profile.write_text(source, encoding='utf-8')
    (root / 'ODIFY_IOS_BUILD.json').write_text(json.dumps({
        'bundleId': BUNDLE, 'version': '1.0.0', 'build': '1',
        'basis': 'pinned Flutter/iOS common app; not the Kotlin native preview',
        'distribution': 'unsigned release AOT; resign with SideStore or LiveContainer',
        'androidOnlyBridges': 'not exposed on iOS',
    }, indent=2) + '\n', encoding='utf-8')


def icons(root: Path, vector: Path):
    from PIL import Image, ImageDraw
    import xml.etree.ElementTree as ET
    element = ET.fromstring(vector.read_text(encoding='utf-8'))
    path = next(iter(element)).get('{http://schemas.android.com/apk/res/android}pathData')
    if re.search(r'[^MLZ0-9.,\s-]', path):
        raise RuntimeError('Expected the existing polygonal Odify note outline')
    coordinates = list(map(float, re.findall(r'-?\d+(?:\.\d+)?', path)))
    points = [(coordinates[i] / 108 * 1024, coordinates[i + 1] / 108 * 1024) for i in range(0, len(coordinates), 2)]
    image = Image.new('RGB', (1024, 1024), (13, 16, 22))
    draw = ImageDraw.Draw(image)
    for y in range(1024):
        draw.line((0, y, 1024, y), fill=(13 + y // 150, 16 + y // 120, 22 + y // 80))
    draw.polygon(points, fill=(249, 250, 255))
    cx, cy, r = 58.8 / 108 * 1024, 50.2 / 108 * 1024, 1.5 / 108 * 1024
    draw.ellipse((cx-r, cy-r, cx+r, cy+r), fill=(249, 250, 255))
    folder = root / 'ios/Runner/Assets.xcassets/AppIcon.appiconset'
    content = json.loads((folder / 'Contents.json').read_text())
    for icon in content['images']:
        if icon.get('filename'):
            size = round(float(icon['size'].split('x')[0]) * float(icon['scale'].rstrip('x')))
            image.resize((size, size), Image.Resampling.LANCZOS).save(folder / icon['filename'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('project', type=Path)
    parser.add_argument('--icon-vector', type=Path)
    args = parser.parse_args()
    prepare(args.project)
    if args.icon_vector:
        icons(args.project, args.icon_vector)
    print('Prepared Odify iOS 1.0.0 (1), release identity and capability gates')
