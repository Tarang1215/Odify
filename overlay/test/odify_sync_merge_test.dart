import 'package:flutter_test/flutter_test.dart';
import 'package:spotiflac_android/services/odify_sync/sync_merge.dart';

OdifySyncEvent event(
  String id,
  int order,
  String op,
  Map<String, Object?> data, {
  String device = 'galaxy',
}) => OdifySyncEvent(
  eventId: id,
  deviceId: device,
  hlc: '2026-10-20T07:00:00.000Z#${order.toString().padLeft(4, '0')}',
  op: op,
  payload: data,
);

const youtubeRef = <String, Object?>{
  'kind': 'youtube',
  'videoId': 'aBcDeFgHiJ1',
  'title': 'Cover - Example',
  'artist': 'Artist',
};

void main() {
  test('offline adds from Galaxy and iPad converge without overwrite', () {
    final e = [
      event('1', 1, 'playlist.create', {
        'playlistId': 'p1', 'name': 'Covers',
      }),
      event('2', 2, 'playlist.entry.add', {
        'playlistId': 'p1', 'entryId': 'galaxy-entry',
        'positionKey': 'a', 'sourceRef': youtubeRef,
      }),
      event('3', 2, 'playlist.entry.add', {
        'playlistId': 'p1', 'entryId': 'ipad-entry',
        'positionKey': 'a', 'sourceRef': youtubeRef,
      }, device: 'ipad'),
    ];
    final a = OdifySyncState.reduce(e);
    final b = OdifySyncState.reduce(e.reversed);
    expect(a.toJson(), b.toJson());
    expect(a.playlists['p1']!.orderedEntries.map((x) => x.id),
      ['galaxy-entry', 'ipad-entry']);
  });

  test('playlist rename and independent entry move both survive', () {
    final e = [
      event('1', 1, 'playlist.create', {
        'playlistId': 'p1', 'name': 'Old',
      }),
      event('2', 2, 'playlist.entry.add', {
        'playlistId': 'p1', 'entryId': 'e1',
        'positionKey': 'a', 'sourceRef': youtubeRef,
      }),
      event('3', 3, 'playlist.rename', {
        'playlistId': 'p1', 'name': 'New',
      }, device: 'windows'),
      event('4', 3, 'playlist.entry.move', {
        'playlistId': 'p1', 'entryId': 'e1', 'positionKey': 'z',
      }, device: 'galaxy'),
    ];
    final state = OdifySyncState.reduce(e.reversed);
    expect(state.playlists['p1']!.name, 'New');
    expect(state.playlists['p1']!.orderedEntries.single.position, 'z');
  });

  test('entry tombstone wins over concurrent move or late re-add', () {
    final e = [
      event('1', 1, 'playlist.create', {
        'playlistId': 'p1', 'name': 'Old',
      }),
      event('2', 2, 'playlist.entry.add', {
        'playlistId': 'p1', 'entryId': 'e1',
        'positionKey': 'a', 'sourceRef': youtubeRef,
      }),
      event('3', 4, 'playlist.entry.move', {
        'playlistId': 'p1', 'entryId': 'e1', 'positionKey': 'z',
      }, device: 'ipad'),
      event('4', 3, 'playlist.entry.remove', {
        'playlistId': 'p1', 'entryId': 'e1',
      }, device: 'galaxy'),
    ];
    expect(OdifySyncState.reduce(e).playlists['p1']!.orderedEntries, isEmpty);
    expect(OdifySyncState.reduce(e.reversed).playlists['p1']!.orderedEntries,
      isEmpty);
  });

  test('like sets use deterministic clock order, retries are idempotent', () {
    final a = event('a', 2, 'like.set', {
      'sourceKey': 'yt:aBcDeFgHiJ1', 'liked': true,
    });
    final b = event('b', 3, 'like.set', {
      'sourceKey': 'yt:aBcDeFgHiJ1', 'liked': false,
    }, device: 'ipad');
    final state = OdifySyncState.reduce([b, a, a]);
    expect(state.likes['yt:aBcDeFgHiJ1'], false);
    expect(state.eventCount, 2);
    expect(
      () => OdifySyncState.reduce([
        a,
        event('a', 2, 'like.set', {
          'sourceKey': 'yt:aBcDeFgHiJ1', 'liked': false,
        }),
      ]),
      throwsFormatException,
    );
  });

  test('portable references preserve case and exclude device paths', () {
    final ref = OdifySourceRef.fromJson({
      ...youtubeRef,
      'source': 'content://media/external/audio/media/12',
      'token': 'do-not-sync',
    });
    expect(ref.videoId, 'aBcDeFgHiJ1');
    expect(ref.toJson().containsKey('source'), false);
    expect(ref.toJson().containsKey('token'), false);
    expect(
      () => OdifySourceRef.fromJson({
        'kind': 'youtube', 'title': 'broken',
        'videoId': 'not-valid',
      }),
      throwsFormatException,
    );
    final local = OdifySourceRef.fromJson({
      'kind': 'local', 'title': 'Saved Song',
      'fingerprint': 'sha256:123',
      'filePath': '/storage/emulated/0/Music/private.mp3',
    });
    expect(local.toJson().containsKey('filePath'), false);
  });
}
