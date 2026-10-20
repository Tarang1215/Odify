import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as path;
import 'package:sqflite/sqflite.dart';

import 'sync_merge.dart';
import 'sync_clock.dart';

/// Contract shared by durable SQLite storage and zero-network test ledgers.
abstract class OdifySyncLedger {
  Future<void> appendLocal(OdifySyncEvent event);
  Future<void> importRemote(
    List<OdifySyncEvent> events, {
    required String nextCursor,
  });
  Future<String?> getCursor();
  Future<List<OdifySyncEvent>> readAll();
  Future<OdifySyncState> state();
  Future<OdifyPendingBatch?> pendingBatch({int limit = 50});
  Future<void> markUploaded(OdifyPendingBatch batch);
  Future<int> pendingCount();
}

/// Persistent local-first operation log, isolated from Mornye's media DB.
/// Each Google account has a separate database file; a device installation
/// may also use its own "local-only" ledger before cloud enrollment.
class OdifySyncRepository implements OdifySyncLedger {
  OdifySyncRepository({
    required String accountId,
    Future<Database> Function()? open,
  }) : _accountKey = _partitionKey(accountId),
       _openOverride = open;

  static String _partitionKey(String accountId) {
    if (accountId.trim().isEmpty) {
      throw ArgumentError.value(accountId, 'accountId', 'Cannot be empty');
    }
    return sha256.convert(utf8.encode(accountId)).toString().substring(0, 24);
  }

  /// Account IDs are never embedded verbatim in filesystem paths.
  static String storageNameFor(String accountId) =>
      'odify_sync_v1_${_partitionKey(accountId)}.db';

  final String _accountKey;
  final Future<Database> Function()? _openOverride;
  Database? _db;

  Future<Database> get database async {
    if (_db != null) return _db!;
    final override = _openOverride;
    if (override != null) return _db = await override();
    final root = await getDatabasesPath();
    return _db = await openDatabase(
      path.join(root, 'odify_sync_v1_$_accountKey.db'),
      version: 1,
      onCreate: (db, _) async {
        await db.execute('''
          CREATE TABLE sync_events (
            event_id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL,
            hlc TEXT NOT NULL,
            event_json TEXT NOT NULL,
            local_pending INTEGER NOT NULL DEFAULT 0,
            batch_id TEXT
          )
        ''');
        await db.execute('''
          CREATE INDEX idx_odify_sync_pending
          ON sync_events(local_pending, batch_id)
        ''');
        await db.execute('''
          CREATE TABLE sync_state (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
          )
        ''');
      },
    );
  }

  /// Record a local playlist/like edit in one durable transaction.
  /// This is the UI's future entry point, independent of Drive connectivity.
  Future<OdifySyncEvent> recordLocalChange({
    required String op,
    required Map<String, Object?> payload,
    DateTime? now,
  }) async {
    final db = await database;
    return db.transaction((txn) async {
      Future<String?> stateValue(String key) async {
        final rows = await txn.query(
          'sync_state',
          columns: ['value'],
          where: 'key = ?',
          whereArgs: [key],
          limit: 1,
        );
        return rows.isEmpty ? null : rows.first['value'] as String;
      }

      Future<void> setStateValue(String key, String value) async {
        await txn.insert(
          'sync_state',
          {'key': key, 'value': value},
          conflictAlgorithm: ConflictAlgorithm.replace,
        );
      }

      final deviceId = await stateValue('device_id') ?? OdifySyncClock.newId();
      final clock = OdifySyncClock.next(
        await stateValue('last_hlc'),
        now: now,
      );
      final event = OdifySyncEvent(
        eventId: OdifySyncClock.newId(),
        deviceId: deviceId,
        hlc: clock,
        op: op,
        payload: Map.unmodifiable(payload),
      );
      _validateEvent(event);
      await txn.insert('sync_events', {
        'event_id': event.eventId,
        'device_id': event.deviceId,
        'hlc': event.hlc,
        'event_json': jsonEncode(event.toJson()),
        'local_pending': 1,
        'batch_id': null,
      });
      await setStateValue('device_id', deviceId);
      await setStateValue('last_hlc', clock);
      return event;
    });
  }

  Future<void> close() async {
    final db = _db;
    _db = null;
    if (db != null) await db.close();
  }

  Future<void> appendLocal(OdifySyncEvent event) async {
    _validateEvent(event);
    final db = await database;
    await db.transaction((txn) async {
      final row = await txn.query(
        'sync_events',
        columns: ['event_json'],
        where: 'event_id = ?',
        whereArgs: [event.eventId],
        limit: 1,
      );
      if (row.isNotEmpty) {
        if (!_sameEvent(row.first['event_json']! as String, event)) {
          throw const FormatException('Sync event ID collision');
        }
        return;
      }
      await txn.insert('sync_events', {
        'event_id': event.eventId,
        'device_id': event.deviceId,
        'hlc': event.hlc,
        'event_json': jsonEncode(event.toJson()),
        'local_pending': 1,
        'batch_id': null,
      });
    });
  }

  /// Apply remote events AND advance the cursor in the same transaction.
  /// A failed event decoding/insert must never lose a Drive changes page.
  Future<void> importRemote(
    List<OdifySyncEvent> events, {
    required String nextCursor,
  }) async {
    final db = await database;
    for (final event in events) {
      _validateEvent(event);
    }
    await db.transaction((txn) async {
      for (final event in events) {
        final existing = await txn.query(
          'sync_events',
          columns: ['event_json'],
          where: 'event_id = ?',
          whereArgs: [event.eventId],
          limit: 1,
        );
        if (existing.isNotEmpty) {
          if (!_sameEvent(existing.first['event_json']! as String, event)) {
            throw const FormatException('Remote sync event ID collision');
          }
          continue;
        }
        await txn.insert('sync_events', {
          'event_id': event.eventId,
          'device_id': event.deviceId,
          'hlc': event.hlc,
          'event_json': jsonEncode(event.toJson()),
          'local_pending': 0,
          'batch_id': null,
        });
      }
      final lastRows = await txn.query(
        'sync_state',
        columns: ['value'],
        where: 'key = ?',
        whereArgs: ['last_hlc'],
        limit: 1,
      );
      var lastClock = lastRows.isEmpty ? '' : lastRows.first['value']! as String;
      for (final event in events) {
        if (event.hlc.compareTo(lastClock) > 0) lastClock = event.hlc;
      }
      if (lastClock.isNotEmpty) {
        await txn.insert(
          'sync_state',
          {'key': 'last_hlc', 'value': lastClock},
          conflictAlgorithm: ConflictAlgorithm.replace,
        );
      }
      await txn.insert(
        'sync_state',
        {'key': 'drive_cursor', 'value': nextCursor},
        conflictAlgorithm: ConflictAlgorithm.replace,
      );
    });
  }

  Future<String?> getCursor() async {
    final db = await database;
    final result = await db.query(
      'sync_state',
      columns: ['value'],
      where: 'key = ?',
      whereArgs: ['drive_cursor'],
      limit: 1,
    );
    return result.isEmpty ? null : result.first['value'] as String;
  }

  Future<List<OdifySyncEvent>> readAll() async {
    final db = await database;
    final rows = await db.query(
      'sync_events',
      columns: ['event_json'],
      orderBy: 'hlc, device_id, event_id',
    );
    return [
      for (final row in rows)
        _decodeEvent(row['event_json']! as String),
    ];
  }

  Future<OdifySyncState> state() async =>
      OdifySyncState.reduce(await readAll());

  /// Persist batch identity BEFORE calling Drive, so retrying a timed-out
  /// upload reuses exactly the same immutable remote filename and content.
  Future<OdifyPendingBatch?> pendingBatch({int limit = 50}) async {
    if (limit <= 0 || limit > 100) {
      throw RangeError.range(limit, 1, 100, 'limit');
    }
    final db = await database;
    return db.transaction((txn) async {
      final assigned = await txn.query(
        'sync_events',
        columns: ['batch_id'],
        where: 'local_pending = 1 AND batch_id IS NOT NULL',
        orderBy: 'hlc, event_id',
        limit: 1,
      );
      if (assigned.isNotEmpty) {
        final id = assigned.first['batch_id']! as String;
        return _readBatch(txn, id);
      }
      final rows = await txn.query(
        'sync_events',
        columns: ['event_id'],
        where: 'local_pending = 1 AND batch_id IS NULL',
        orderBy: 'hlc, device_id, event_id',
        limit: limit,
      );
      if (rows.isEmpty) return null;
      final ids = [for (final row in rows) row['event_id']! as String];
      final digest = sha256.convert(utf8.encode(ids.join('\n'))).toString();
      final id = 'odify-v1-batch-${digest.substring(0, 32)}.json';
      for (final eventId in ids) {
        await txn.update(
          'sync_events',
          {'batch_id': id},
          where: 'event_id = ? AND local_pending = 1',
          whereArgs: [eventId],
        );
      }
      return _readBatch(txn, id);
    });
  }

  Future<OdifyPendingBatch> _readBatch(
    Transaction txn,
    String batchId,
  ) async {
    final rows = await txn.query(
      'sync_events',
      columns: ['event_json'],
      where: 'batch_id = ? AND local_pending = 1',
      whereArgs: [batchId],
      orderBy: 'hlc, device_id, event_id',
    );
    if (rows.isEmpty) {
      throw StateError('Sync batch disappeared');
    }
    return OdifyPendingBatch(
      batchId,
      [for (final row in rows) _decodeEvent(row['event_json']! as String)],
    );
  }

  Future<void> markUploaded(OdifyPendingBatch batch) async {
    final db = await database;
    await db.transaction((txn) async {
      final count = await txn.update(
        'sync_events',
        {'local_pending': 0},
        where: 'batch_id = ? AND local_pending = 1',
        whereArgs: [batch.batchId],
      );
      if (count != batch.events.length) {
        throw StateError('Upload acknowledgment did not match batch size');
      }
    });
  }

  Future<int> pendingCount() async {
    final db = await database;
    final n = await db.rawQuery(
      'SELECT COUNT(*) AS count FROM sync_events WHERE local_pending = 1',
    );
    return (n.single['count'] as int?) ?? 0;
  }

  static OdifySyncEvent _decodeEvent(String source) {
    final data = jsonDecode(source);
    if (data is! Map) {
      throw const FormatException('Invalid stored sync event');
    }
    return OdifySyncEvent.fromJson(Map<String, Object?>.from(data));
  }

  static bool _sameEvent(String original, OdifySyncEvent event) =>
      OdifySyncState.deepEventEquals(
        Map<String, Object?>.from(jsonDecode(original) as Map),
        event.toJson(),
      );

  static void _validateEvent(OdifySyncEvent event) {
    OdifySyncEvent.fromJson(event.toJson());
  }
}

class OdifyPendingBatch {
  const OdifyPendingBatch(this.batchId, this.events);
  final String batchId;
  final List<OdifySyncEvent> events;
}
