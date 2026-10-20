import 'package:flutter_test/flutter_test.dart';
import 'package:spotiflac_android/services/odify_sync/sync_clock.dart';
import 'package:spotiflac_android/services/odify_sync/sync_coordinator.dart';
import 'package:spotiflac_android/services/odify_sync/sync_merge.dart';
import 'package:spotiflac_android/services/odify_sync/sync_repository.dart';

/// A pure in-memory ledger allows coordination tests without Android SQLite.
class FakeLedger implements OdifySyncLedger {
  final events = <String, OdifySyncEvent>{};
  final pending = <String>{};
  String? cursor;

  @override
  Future<void> appendLocal(OdifySyncEvent event) async {
    events[event.eventId] = event;
    pending.add(event.eventId);
  }

  @override
  Future<void> importRemote(
    List<OdifySyncEvent> incoming, {
    required String nextCursor,
  }) async {
    for (final event in incoming) {
      final prev = events[event.eventId];
      if (prev != null &&
          !OdifySyncState.deepEventEquals(prev.toJson(), event.toJson())) {
        throw const FormatException('Duplicate sync identity conflict');
      }
      events[event.eventId] = event;
    }
    cursor = nextCursor;
  }

  @override
  Future<String?> getCursor() async => cursor;

  @override
  Future<List<OdifySyncEvent>> readAll() async => events.values.toList();

  @override
  Future<OdifySyncState> state() async =>
      OdifySyncState.reduce(await readAll());

  @override
  Future<OdifyPendingBatch?> pendingBatch({int limit = 50}) async {
    if (pending.isEmpty) return null;
    // Stable identity represents a single-event immutable Drive file in the
    // fake store. Production SQLite batches up to 50 events per file.
    final eventId = pending.toList()..sort();
    final id = eventId.first;
    return OdifyPendingBatch('batch-$id', [events[id]!]);
  }

  @override
  Future<void> markUploaded(OdifyPendingBatch batch) async {
    for (final event in batch.events) {
      pending.remove(event.eventId);
    }
  }

  @override
  Future<int> pendingCount() async => pending.length;
}

OdifySyncEvent e(
  String id,
  String device,
  int logical,
  String op,
  Map<String, Object?> payload,
) => OdifySyncEvent(
  eventId: id,
  deviceId: device,
  hlc: '2026-10-20T07:00:00.000Z#${logical.toString().padLeft(4, '0')}',
  op: op,
  payload: payload,
);

const ref = <String, Object?>{
  'kind': 'youtube',
  'title': 'Cover A',
  'videoId': 'AaBbCcDdE01',
};

void main() {
  test('two offline devices add different tracks and converge', () async {
    final galaxy = FakeLedger();
    final windows = FakeLedger();
    final remote = OdifyMemorySyncTransport();
    final a = OdifySyncCoordinator(ledger: galaxy, transport: remote);
    final b = OdifySyncCoordinator(ledger: windows, transport: remote);

    await galaxy.appendLocal(e('create', 'galaxy', 1, 'playlist.create', {
      'playlistId': 'p1', 'name': 'J-Pop',
    }));
    await a.syncOnce();
    await b.syncOnce();

    await galaxy.appendLocal(e('galaxy-add', 'galaxy', 2,
      'playlist.entry.add', {
        'playlistId': 'p1', 'entryId': 'A', 'positionKey': 'a',
        'sourceRef': ref,
      },
    ));
    await windows.appendLocal(e('windows-add', 'windows', 2,
      'playlist.entry.add', {
        'playlistId': 'p1', 'entryId': 'B', 'positionKey': 'b',
        'sourceRef': ref,
      },
    ));

    await a.syncOnce();
    await b.syncOnce();
    await a.syncOnce();

    expect((await galaxy.state()).toJson(), (await windows.state()).toJson());
    expect(
      (await windows.state()).playlists['p1']!.orderedEntries.length,
      2,
    );
    expect(await galaxy.pendingCount(), 0);
    expect(await windows.pendingCount(), 0);
  });

  test('remote write ambiguity preserves outbox until successful retry',
      () async {
    final ledger = FakeLedger();
    final remote = _UncertainUploadTransport();
    final sync = OdifySyncCoordinator(ledger: ledger, transport: remote);
    await ledger.appendLocal(e('like-1', 'galaxy', 1, 'like.set', {
      'sourceKey': 'yt:AaBbCcDdE01', 'liked': true,
    }));

    await expectLater(sync.syncOnce(), throwsStateError);
    expect(await ledger.pendingCount(), 1);
    await sync.syncOnce();
    expect(await ledger.pendingCount(), 0);
    expect(remote.storedBatchCount, 1);
    expect((await ledger.state()).likes['yt:AaBbCcDdE01'], true);
  });

  test('invalid event and accidental private paths cannot upload', () {
    final unsafe = e('oops', 'galaxy', 1, 'playlist.entry.add', {
      'playlistId': 'p1', 'entryId': 'e1', 'positionKey': 'a',
      'sourceRef': {
        ...ref,
        'filePath': '/storage/emulated/0/Music/private.mp3',
      },
    });
    expect(() => OdifySyncEvent.fromJson(unsafe.toJson()),
        throwsFormatException);
    final unsupported = e('unknown', 'galaxy', 1, 'video.download', {
      'url': 'https://media.example/file',
    });
    expect(() => OdifySyncEvent.fromJson(unsupported.toJson()),
        throwsFormatException);
  });

  test('hybrid logical clock advances offline and preserves ID case', () {
    final instant = DateTime.utc(2026, 10, 20, 7);
    final first = OdifySyncClock.next(null, now: instant);
    final second = OdifySyncClock.next(first, now: instant);
    final third = OdifySyncClock.next(second,
      now: instant.subtract(const Duration(days: 1)));
    expect(first.endsWith('#0000'), true);
    expect(second.endsWith('#0001'), true);
    expect(third.endsWith('#0002'), true);
    expect(first.compareTo(second), lessThan(0));
    expect(second.compareTo(third), lessThan(0));
    expect(OdifySyncClock.newId(), isNot(OdifySyncClock.newId()));
  });
}

class _UncertainUploadTransport extends OdifyMemorySyncTransport {
  bool _first = true;

  @override
  Future<void> putIfAbsent(OdifyPendingBatch batch) async {
    await super.putIfAbsent(batch);
    if (_first) {
      _first = false;
      throw StateError('Simulated lost Drive response after upload');
    }
  }
}
