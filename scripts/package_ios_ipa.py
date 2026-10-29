#!/usr/bin/env python3
"""Package a real iPhone release app; reject simulator/debug placeholders."""
import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import re
import shutil
import stat
import subprocess
import tempfile
import zipfile


def validate(app: Path):
    info = plistlib.loads((app / 'Info.plist').read_bytes())
    if info.get('CFBundleIdentifier') != 'com.odify.music.ios':
        raise RuntimeError('Unexpected iOS app identity')
    if 'iPhoneOS' not in info.get('CFBundleSupportedPlatforms', []):
        raise RuntimeError('Expected iPhone device build, not simulator')
    if 'audio' not in info.get('UIBackgroundModes', []):
        raise RuntimeError('Missing native background audio capability')
    for relative in [info['CFBundleExecutable'], 'Frameworks/App.framework/App', 'Frameworks/Flutter.framework/Flutter']:
        executable = app / relative
        if not executable.is_file() or executable.stat().st_size < 100_000:
            raise RuntimeError('Missing compiled release binary: ' + relative)
        arch = subprocess.check_output(['xcrun', 'lipo', '-archs', str(executable)], text=True).strip()
        if arch != 'arm64':
            raise RuntimeError('Expected device arm64: ' + relative + ' = ' + arch)
        build = subprocess.check_output(['xcrun', 'vtool', '-show-build', str(executable)], text=True)
        if not re.search(r'platform\s+IOS\b', build):
            raise RuntimeError('Wrong Apple platform in Mach-O: ' + relative)
    return info


def package(app: Path, output: Path):
    info = validate(app)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='odify-ipa-') as temp:
        stage = Path(temp)
        destination = stage / 'Payload/Odify.app'
        shutil.copytree(app, destination, symlinks=False)
        # Guests do not host the original WidgetKit extensions; this also
        # avoids extra free-account app IDs when installing with SideStore.
        plugins = destination / 'PlugIns'
        if plugins.exists():
            assert plugins.resolve().parent == destination.resolve()
            shutil.rmtree(plugins)
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for path in sorted(destination.rglob('*')):
                if path.is_file():
                    relative = path.relative_to(destination).as_posix()
                    member = zipfile.ZipInfo.from_file(path, path.relative_to(stage).as_posix())
                    # IPA entries require POSIX execution bits even when a
                    # packaging regression fixture runs on Windows.
                    if relative == info['CFBundleExecutable'] or (
                            path.parent.suffix == '.framework' and path.name == path.parent.stem):
                        member.create_system = 3
                        member.external_attr = (stat.S_IFREG | 0o755) << 16
                    member.compress_type = zipfile.ZIP_DEFLATED
                    with path.open('rb') as source, archive.open(member, 'w') as target:
                        shutil.copyfileobj(source, target, 1024 * 1024)
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise RuntimeError('IPA ZIP verification failed')
        executable = archive.getinfo('Payload/Odify.app/' + info['CFBundleExecutable'])
        if not (executable.external_attr >> 16) & stat.S_IXUSR:
            raise RuntimeError('Executable permission missing')
        if any(name.startswith('Payload/Odify.app/PlugIns/') for name in archive.namelist()):
            raise RuntimeError('Unexpected extension in LiveContainer payload')
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.ipa.sha256').write_text(digest + '  ' + output.name + '\n')
    output.with_suffix('.build.json').write_text(json.dumps({
        'bundleId': info['CFBundleIdentifier'], 'version': info['CFBundleShortVersionString'],
        'build': info['CFBundleVersion'], 'minimumOS': info.get('MinimumOSVersion'),
        'platform': 'iPhoneOS', 'architecture': 'arm64', 'releaseAOT': True,
        'signing': 'unsigned; user signs with SideStore/LiveContainer',
        'sha256': digest, 'bytes': output.stat().st_size,
    }, indent=2) + '\n')
    print('Verified iPhone release IPA:', output, digest)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('app', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    package(args.app.resolve(), args.output.resolve())
