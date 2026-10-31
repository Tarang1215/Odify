"""Generate the existing Odify icon for the native iOS target, with attribution."""
import json
import argparse
import plistlib
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET
import wave
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
PIN = '89854c93207ad0d2b3d78d6f1aea24595ca66552'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dependency-notices', type=Path)
    args = parser.parse_args()
    resources = ROOT / 'native-ios/Resources'
    resources.mkdir(parents=True, exist_ok=True)
    # Finite, opt-in presentation/keyboard-test audio. Never used by normal
    # playback or to extend a background lifetime.
    if not args.dependency_notices:
        with wave.open(str(resources / 'preview-silence.wav'), 'wb') as preview:
            preview.setnchannels(1); preview.setsampwidth(2); preview.setframerate(8000)
            preview.writeframes(b'\0' * 8000 * 2 * 90)
    if args.dependency_notices:
        sections = []
        for dependency in sorted(args.dependency_notices.iterdir()):
            if not dependency.is_dir():
                continue
            for file in sorted(dependency.iterdir()):
                if file.is_file() and file.name.upper().split('.')[0] in {'LICENSE', 'LICENCE', 'COPYING', 'NOTICE'}:
                    text = file.read_text(encoding='utf-8', errors='replace')
                    sections.append(dependency.name + ' / ' + file.name + '\n' + '=' * 64 + '\n' + text)
        if not any('googlesignin' in section.lower() for section in sections):
            raise RuntimeError('Missing resolved Google SDK original license')
        (resources / 'ThirdPartyNotices.txt').write_text('\n\n'.join(sections), encoding='utf-8')
        print('Bundled original notices from', len(sections), 'resolved dependency files')
        return
    client = json.loads((ROOT / 'native-ios/GoogleOAuth.json').read_text())
    info = plistlib.loads((ROOT / 'native-ios/Info.plist').read_bytes())
    if info.get('GIDClientID') != client['clientID'] or client['bundleID'] != 'com.odify.music.ios':
        raise RuntimeError('iOS OAuth configuration does not match the app')
    reverse = '.'.join(reversed(client['clientID'].split('.')))
    schemes = [scheme for entry in info['CFBundleURLTypes'] for scheme in entry['CFBundleURLSchemes']]
    if reverse not in schemes:
        raise RuntimeError('Google iOS callback scheme is missing')
    (resources / 'ThirdPartyNotices.txt').write_text('Original dependency notices will be collected after package resolution.\n', encoding='utf-8')
    vector = ROOT / 'native-android/app/src/main/res/drawable/odify_launcher_foreground.xml'
    element = ET.fromstring(vector.read_text(encoding='utf-8'))
    path = next(iter(element)).get('{http://schemas.android.com/apk/res/android}pathData')
    if not path or re.search(r'[^MLZ0-9.,\s-]', path):
        raise RuntimeError('Unexpected Odify icon geometry')
    coordinates = list(map(float, re.findall(r'-?\d+(?:\.\d+)?', path)))
    image = Image.new('RGB', (1024, 1024), (13, 16, 22))
    draw = ImageDraw.Draw(image)
    for y in range(1024):
        value = y / 1023
        draw.line((0, y, 1024, y), fill=(int(35 - 22 * value), int(65 - 49 * value), int(62 - 40 * value)))
    draw.polygon([(coordinates[i] / 108 * 1024, coordinates[i + 1] / 108 * 1024) for i in range(0, len(coordinates), 2)], fill=(249, 250, 255))
    cx, cy, radius = 58.8 / 108 * 1024, 50.2 / 108 * 1024, 1.5 / 108 * 1024
    draw.ellipse((cx-radius, cy-radius, cx+radius, cy+radius), fill=(249, 250, 255))
    assets = resources / 'Assets.xcassets'
    folder = assets / 'AppIcon.appiconset'
    folder.mkdir(parents=True, exist_ok=True)
    image.save(folder / 'AppIcon.png')
    (folder / 'Contents.json').write_text(json.dumps({'images': [{'filename': 'AppIcon.png', 'idiom': 'universal', 'platform': 'ios', 'size': '1024x1024'}], 'info': {'author': 'xcode', 'version': 1}}, indent=2))
    (assets / 'Contents.json').write_text('{"info":{"author":"xcode","version":1}}\n')
    request = urllib.request.Request(f'https://raw.githubusercontent.com/alexeichhorn/YouTubeKit/{PIN}/LICENSE', headers={'User-Agent': 'Odify-build'})
    with urllib.request.urlopen(request, timeout=30) as response:
        license_text = response.read().decode('utf-8')
    if 'MIT License' not in license_text or 'Alexander Eichhorn' not in license_text:
        raise RuntimeError('Missing original dependency license')
    (resources / 'YouTubeKit-LICENSE.txt').write_text(license_text, encoding='utf-8')
    # This independent Swift target does not bundle the old Flutter/Mornye core.
    # Do not invent a repository-wide license when the repository has no LICENSE.
    print('Prepared native iPad/iPhone icon and original license notices')


if __name__ == '__main__':
    main()
