import 'dart:async';

import 'sync_merge.dart';
import 'sync_repository.dart';

/// A cursor comes from the remote transport, not from the local clock.
/// The Drive adapter will translate Drive changes.list pages into this type.
class OdifyRemoteDelta {
  const OdifyRemoteDelta({required this.cursor, required this.events});

  final String cursor;
  final List<OdifySyncEvent> events;
}

/// Write immutable event batches only. NEVER replace a common playlist.json.
/// The Drive implementation must reconcile ambiguous upload timeouts by
/// searching the batch filename AND comparing contents before creating it.
abstract class OdifySyncTransport {
  Future<OdifyRemoteDelta> pull({String? cursor});

  Future<void> putIfAbsent(OdifyPendingBatch batch);
}

/// Serializes sync attempts, preserves the persistent outbox on failures,
/// and imports a final change page after writing local operations.
/// No browser auth, Google account, or networking is bundled in this module.
class OdifySyncCoordinator {
  OdifySyncCoordinator({
    required this.ledger,
    required this.transport,
  });

  final OdifySyncLedger ledger;
  final OdifySyncTransport transport;
  Future<OdifySyncState>? _running;

  Future<OdifySyncState> syncOnce({int maxBatches = 4}) {
    if (maxBatches < 1 || maxBatches > 20) {
      throw RangeError.range(maxBatches, 1, 20, 'maxBatches');
    }
    return _running ??= _sync(maxBatches).whenComplete(() {
      _running = null;
    });
  }

  Future<OdifySyncState> _sync(int maxBatches) async {
    // Import first; the cursor is committed atomically with events.
    await _pullAndApply();
    for (var index = 0; index < maxBatches; index++) {
      final batch = await ledger.pendingBatch();
      if (batch == null) break;
      // A lost acknowledgment still leaves its stable batch ID on disk.
      // A future invocation checks remote and retries the same batch.
      await transport.putIfAbsent(batch);
      await ledger.markUploaded(batch);
    }
    // Pick up concurrent edits uploaded by another device while we pushed.
    await _pullAndApply();
    return ledger.state();
  }

  Future<void> _pullAndApply() async {
    final cursor = await ledger.getCursor();
    final delta = await transport.pull(cursor: cursor);
    await ledger.importRemote(delta.events, nextCursor: delta.cursor);
  }
}

/// Deterministic in-memory remote for unit tests and pre-OAuth development.
/// Production code must use Google Drive appDataFolder behind the same
/// interface. No Supabase or Trove infrastructure is accessed.
class OdifyMemorySyncTransport implements OdifySyncTransport {
  final _batches = <String, List<OdifySyncEvent>>{};
  final _versions = <String, int>{};
  int _version = 0;

  int get storedBatchCount => _batches.length;

  @override
  Future<OdifyRemoteDelta> pull({String? cursor}) async {
    final since = cursor == null ? 0 : int.parse(cursor);
    final events = <OdifySyncEvent>[];
    for (final entry in _batches.entries) {
      if ((_versions[entry.key] ?? 0) > since) {
        events.addAll(entry.value);
      }
    }
    return OdifyRemoteDelta(cursor: '$_version', events: events);
  }

  @override
  Future<void> putIfAbsent(OdifyPendingBatch batch) async {
    final existing = _batches[batch.batchId];
    if (existing != null) {
      final a = [for (final item in existing) item.toJson()];
      final b = [for (final item in batch.events) item.toJson()];
      if (a.length != b.length) {
        throw const FormatException('Remote batch filename collision');
      }
      for (var index = 0; index < a.length; index++) {
        if (!OdifySyncState.deepEventEquals(a[index], b[index])) {
          throw const FormatException('Remote batch contents disagree');
        }
      }
      return;
    }
    _batches[batch.batchId] = List.unmodifiable(batch.events);
    _versions[batch.batchId] = ++_version;
  }
}
