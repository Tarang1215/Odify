import 'package:flutter_test/flutter_test.dart';
import 'package:spotiflac_android/services/odify_device_artwork.dart';
import 'package:spotiflac_android/services/odify_device_catalog.dart';

void main() {
  setUp(OdifyDeviceArtwork.clearMemoryForTesting);

  test('only public Android MediaStore audio IDs can request art', () {
    expect(
      OdifyDeviceArtwork.mediaIdFromSource(
        'content://media/external/audio/media/4735',
      ),
      '4735',
    );
    expect(
      OdifyDeviceArtwork.mediaIdFromSource(
        'content://media/external_primary/audio/media/4735',
      ),
      '4735',
    );
    for (final unsafe in [
      'content://com.samsung.music/private/4735',
      'content://media/external/images/media/4735',
      'content://media/external/audio/media/../../1',
      'content://media/external/audio/media/abc',
      'file:///storage/emulated/0/Music/private.mp3',
      'https://example.com/artwork/4735',
    ]) {
      expect(OdifyDeviceArtwork.isMediaStoreAudioUri(unsafe), false);
    }
  });

  test('device track maps cached artwork into player without copying MP3', () {
    final track = OdifyDeviceTrack.fromPlatform({
      'id': '4735',
      'uri': 'content://media/external/audio/media/4735',
      'title': 'Favorite Track',
      'artist': 'Favorite Artist',
      'album': 'Album A',
      'durationMs': 219000,
      'modifiedSeconds': 1792150000,
      'albumId': 32,
      'cachedCoverPath': '/data/user/0/com.odify.music/files/art/4735.jpg',
    });
    expect(track.albumId, 32);
    expect(track.modifiedSeconds, 1792150000);
    expect(track.toPlayable().source, track.uri);
    expect(track.toPlayable().artUri, Uri.file(track.cachedCoverPath!).toString());
    expect(track.toPlayable().duration?.inSeconds, 219);
    expect(
      OdifyDeviceArtwork.peek('4735', modifiedSeconds: 1792150000),
      track.cachedCoverPath,
    );
  });

  test('missing cover is not fabricated or fetched from internet', () {
    final track = OdifyDeviceTrack.fromPlatform({
      'id': '42',
      'uri': 'content://media/external/audio/media/42',
      'title': 'Local MP3',
      'artist': 'Artist',
      'album': 'Album',
      'durationMs': 90000,
    });
    expect(track.cachedCoverPath, null);
    expect(track.toPlayable().artUri, null);
    expect(
      OdifyDeviceArtwork.peek(track.id, modifiedSeconds: track.modifiedSeconds),
      null,
    );
  });
}
