#!/usr/bin/env python3
"""Lightweight static checks without invoking Flutter, Android, or Actions.

These checks are not equivalent to a successful Android build or device test.
"""
from pathlib import Path
import ast

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'overlay'
assembly = ROOT / 'scripts' / 'assemble.py'
ast.parse(assembly.read_text(encoding='utf-8'))
files = {p.relative_to(SOURCE).as_posix(): p for p in SOURCE.rglob('*') if p.is_file()}
required = {
    'lib/services/odify_likes.dart',
    'lib/services/odify_taste.dart',
    'lib/services/odify_device_catalog.dart',
    'lib/widgets/odify_device_library_card.dart',
    'lib/widgets/odify_device_music_browser.dart',
    'android/app/src/main/kotlin/com/zarz/spotiflac/OdifyMediaStorePlugin.kt',
}
assert required <= files.keys(), sorted(required - files.keys())
for name, path in files.items():
    source = path.read_text(encoding='utf-8')
    assert '\x00' not in source, name
    assert source.endswith('\n'), name
    assert '<<<<<<<' not in source and '>>>>>>>' not in source, name
kt = files['android/app/src/main/kotlin/com/zarz/spotiflac/OdifyMediaStorePlugin.kt'].read_text()
assert 'MediaStore.Audio.Media.EXTERNAL_CONTENT_URI' in kt
assert 'READ_MEDIA_AUDIO' in kt
assert 'READ_EXTERNAL_STORAGE' in kt
assert 'com.odify.music/media_store' in kt
assert 'MediaStore query returned null' in kt
taste = files['lib/services/odify_taste.dart'].read_text()
assert 'likedArtistCounts' in taste and '(liked ? 7.0 : 0.0)' in taste
assert 'OdifyLikeStore.read()' in taste
app = assembly.read_text()
assert 'OdifyMediaStorePlugin' in app and 'odify_likes.dart' in app
assert 'git clone' not in app  # subprocess call uses argv list
assert 'subprocess.run' in app
print(f'Odify overlay static checks passed: {len(files)} files')

# v0.4: yt-dlp is a native plug-in, invoked from Flutter and reused by Home.
assert {
    'lib/services/odify_youtube.dart',
    'lib/widgets/odify_youtube_explore.dart',
    'android/app/src/main/kotlin/com/zarz/spotiflac/OdifyYtDlpPlugin.kt',
} <= files.keys()
assert 'OdifyYtDlpPlugin' in app
assert 'OdifyYouTubeExploreCard' in app
assert '0.16.0' in app and 'android:extractNativeLibs' in app
assert 'YoutubeDL.getInstance().getInfo(request)' in files['android/app/src/main/kotlin/com/zarz/spotiflac/OdifyYtDlpPlugin.kt'].read_text()
assert "playSingle(media)" in files['lib/widgets/odify_youtube_explore.dart'].read_text()
