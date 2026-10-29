"""Independently inspect the downloaded native IPA without an Apple toolchain."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import plistlib
import shutil
import struct
import zipfile


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def macho(raw):
    if raw[:4] in [b'\xca\xfe\xba\xbe', b'\xca\xfe\xba\xbf']:
        magic, count = struct.unpack_from('>II', raw)
        assert count == 1, 'Expected an arm64-only universal container'
        if magic == 0xcafebabe:
            cpu, _, offset, length, _ = struct.unpack_from('>iiIII', raw, 8)
        else:
            cpu, _, offset, length, _, _ = struct.unpack_from('>iiQQII', raw, 8)
        assert cpu == 0x100000c and offset + length <= len(raw)
        raw = raw[offset:offset + length]
    magic, cpu, subtype, filetype, count, size, flags, reserved = struct.unpack_from('<IiiIIIII', raw)
    assert magic == 0xfeedfacf and cpu == 0x100000c, 'Not arm64 Mach-O'
    offset, platform, encrypted = 32, None, False
    for _ in range(count):
        command, length = struct.unpack_from('<II', raw, offset)
        assert length >= 8 and offset + length <= len(raw)
        if command == 0x32:
            platform = struct.unpack_from('<I', raw, offset + 8)[0]
        if command == 0x2c:
            encrypted = struct.unpack_from('<I', raw, offset + 16)[0] != 0
        offset += length
    assert platform == 2 and not encrypted, 'Simulator or encrypted executable'
    return {'platform': 'iOS device', 'architecture': 'arm64', 'encrypted': False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    parser.add_argument('artifact_sha256')
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    assert sha(args.archive) == args.artifact_sha256.removeprefix('sha256:').lower(), 'Artifact checksum mismatch'
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    base = 'Odify-iOS-1.1.0'
    expected = {base + suffix for suffix in ['.ipa', '.ipa.sha256', '.build.json']}
    found = set()
    with zipfile.ZipFile(args.archive) as archive:
        assert archive.testzip() is None
        for name in archive.namelist():
            filename = PurePosixPath(name).name
            if filename in expected:
                assert filename not in found, 'Duplicate artifact member'
                with archive.open(name) as source, (output / filename).open('wb') as target:
                    shutil.copyfileobj(source, target)
                found.add(filename)
    assert found == expected, found
    ipa = output / (base + '.ipa')
    report = json.loads((output / (base + '.build.json')).read_text())
    digest = sha(ipa)
    assert digest == report['sha256']
    assert (output / (base + '.ipa.sha256')).read_text().split()[0] == digest
    assert report['nativeRelease'] is True and report['runtime'] == 'native'
    prefix = 'Payload/Odify.app/'
    with zipfile.ZipFile(ipa) as archive:
        assert archive.testzip() is None
        names = archive.namelist()
        info = plistlib.loads(archive.read(prefix + 'Info.plist'))
        assert info['CFBundleIdentifier'] == 'com.odify.music.ios'
        assert set(info['UIDeviceFamily']) == {1, 2}
        assert info['CFBundleSupportedPlatforms'] == ['iPhoneOS']
        assert 'audio' in info['UIBackgroundModes']
        assert not any('/PlugIns/' in name for name in names)
        assert prefix + 'embedded.mobileprovision' not in names
        license_text = archive.read(prefix + 'YouTubeKit-LICENSE.txt').decode()
        assert 'MIT License' in license_text and 'Copyright (c) 2022 Alexander Eichhorn' in license_text
        notices = archive.read(prefix + 'ThirdPartyNotices.txt').decode()
        assert 'googlesignin' in notices.lower() and 'Apache License' in notices
        client_id = info['GIDClientID']
        assert client_id == '816869113913-ndlnd0ankpg2kj6ldn9igmsjfc2kdobm.apps.googleusercontent.com'
        schemes = [scheme for entry in info['CFBundleURLTypes'] for scheme in entry['CFBundleURLSchemes']]
        assert '.'.join(reversed(client_id.split('.'))) in schemes
        binaries = [prefix + info['CFBundleExecutable']]
        binaries += [name for name in names if '.framework/' in name and PurePosixPath(name).name == PurePosixPath(name).parent.stem]
        proofs = []
        for binary in binaries:
            raw = archive.read(binary)
            assert len(raw) > 100_000
            assert archive.getinfo(binary).external_attr >> 16 & 0o100
            proofs.append(dict(name=binary.removeprefix(prefix), bytes=len(raw), **macho(raw)))
    proof = {'ipa': str(ipa), 'sha256': digest, 'bytes': ipa.stat().st_size,
             'version': info['CFBundleShortVersionString'], 'build': info['CFBundleVersion'],
             'minimumOS': info['MinimumOSVersion'], 'deviceFamily': info['UIDeviceFamily'],
             'bundle': info['CFBundleIdentifier'], 'nativeRelease': True, 'originalDependencyLicenseIncluded': True,
             'binaries': proofs, 'extensions': 0}
    (output / 'independent-native-verification.json').write_text(json.dumps(proof, indent=2))
    print(json.dumps(proof))


if __name__ == '__main__':
    main()
