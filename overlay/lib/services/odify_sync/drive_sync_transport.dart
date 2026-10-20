import 'dart:convert';

import 'package:http/http.dart' as http;

import 'sync_coordinator.dart';
import 'sync_merge.dart';
import 'sync_repository.dart';

typedef OdifyDriveTokenProvider = Future<String> Function();

/// Google Drive v3 appDataFolder metadata transport.
///
/// This is an adapter only: an OS-specific Google OAuth provider must supply
/// the short-lived Drive appData token after explicit user opt-in. Never
/// embed Google client secrets, tokens, or service-account credentials here.
/// Do not initialize for another signed-in account without a separate ledger.
class OdifyDriveSyncTransport implements OdifySyncTransport {
  OdifyDriveSyncTransport({
    required this.accessToken,
    http.Client? client,
  }) : _client = client ?? http.Client(),
       _ownsClient = client == null;

  static const scope = 'https://www.googleapis.com/auth/drive.appdata';
  static const _api = 'https://www.googleapis.com/drive/v3';
  static const _upload = 'https://www.googleapis.com/upload/drive/v3';
  static const _maxBatchBytes = 2 * 1024 * 1024;
  static final _batchName =
      RegExp(r'^odify-v1-batch-[a-f0-9]{32}\.json$');

  final OdifyDriveTokenProvider accessToken;
  final http.Client _client;
  final bool _ownsClient;

  void close() {
    if (_ownsClient) _client.close();
  }

  Future<http.Response> _send(
    String method,
    Uri uri, {
    List<int>? bytes,
    String? contentType,
  }) async {
    final token = await accessToken();
    if (token.isEmpty) {
      throw StateError('Odify Drive authorization is required');
    }
    final request = http.Request(method, uri)
      ..headers['Authorization'] = 'Bearer $token'
      ..headers['Accept'] = 'application/json';
    if (contentType != null) request.headers['Content-Type'] = contentType;
    if (bytes != null) request.bodyBytes = bytes;
    final streamed = await _client.send(request);
    final response = await http.Response.fromStream(streamed);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      // Intentionally omit response bodies; they may contain account details.
      throw OdifyDriveSyncError(response.statusCode);
    }
    if (response.bodyBytes.length > _maxBatchBytes) {
      throw const FormatException('Drive response exceeds safe sync limit');
    }
    return response;
  }

  Future<Map<String, Object?>> _getJson(
    String resource,
    Map<String, String> query,
  ) async {
    final uri = Uri.parse('$_api/$resource')
        .replace(queryParameters: query.isEmpty ? null : query);
    final response = await _send('GET', uri);
    final decoded = jsonDecode(utf8.decode(response.bodyBytes));
    if (decoded is! Map) {
      throw const FormatException('Invalid Drive API response');
    }
    return Map<String, Object?>.from(decoded);
  }

  Future<List<Map<String, Object?>>> _listBatchFiles({
    String? exactName,
  }) async {
    final files = <Map<String, Object?>>[];
    String? token;
    for (var page = 0; page < 100; page++) {
      final query = <String, String>{
        'spaces': 'appDataFolder',
        'pageSize': '1000',
        'fields': 'nextPageToken,files(id,name,mimeType,size)',
        'q': exactName == null
            ? "name contains 'odify-v1-batch-'"
            : "name = '$exactName'",
        if (token != null) 'pageToken': token,
      };
      final data = await _getJson('files', query);
      final rows = data['files'];
      if (rows is! List) {
        throw const FormatException('Invalid Drive file list');
      }
      for (final entry in rows) {
        if (entry is! Map) continue;
        final file = Map<String, Object?>.from(entry);
        if (file['id'] is String &&
            file['name'] is String &&
            _batchName.hasMatch(file['name']! as String)) {
          files.add(file);
        }
      }
      token = data['nextPageToken'] as String?;
      if (token == null || token.isEmpty) return files;
    }
    throw const FormatException('Drive sync file listing is too large');
  }

  Future<List<OdifySyncEvent>> _download(String fileId) async {
    final uri = Uri.parse('$_api/files/${Uri.encodeComponent(fileId)}')
        .replace(queryParameters: {'alt': 'media'});
    final response = await _send('GET', uri);
    final payload = jsonDecode(utf8.decode(response.bodyBytes));
    if (payload is! Map || payload['schemaVersion'] != 1 ||
        payload['events'] is! List) {
      throw const FormatException('Invalid Odify cloud batch');
    }
    final rows = payload['events'] as List;
    if (rows.length > 100) {
      throw const FormatException('Cloud batch contains too many events');
    }
    return [
      for (final item in rows)
        OdifySyncEvent.fromJson(
          Map<String, Object?>.from(item as Map),
        ),
    ];
  }

  Future<OdifyRemoteDelta> _readChanges(String cursor) async {
    final events = <OdifySyncEvent>[];
    final seenFileIds = <String>{};
    var pageToken = cursor;
    for (var page = 0; page < 100; page++) {
      final data = await _getJson('changes', {
        'pageToken': pageToken,
        'spaces': 'appDataFolder',
        'pageSize': '1000',
        'fields': 'nextPageToken,newStartPageToken,changes(fileId,removed,file(id,name,mimeType))',
      });
      final changes = data['changes'];
      if (changes is! List) {
        throw const FormatException('Invalid Drive change page');
      }
      for (final item in changes) {
        if (item is! Map) continue;
        final change = Map<String, Object?>.from(item);
        if (change['removed'] == true) continue;
        final file = change['file'];
        if (file is! Map) continue;
        final name = file['name'];
        final id = change['fileId'] ?? file['id'];
        if (id is String && name is String &&
            _batchName.hasMatch(name) && seenFileIds.add(id)) {
          events.addAll(await _download(id));
        }
      }
      final next = data['nextPageToken'];
      if (next is String && next.isNotEmpty) {
        pageToken = next;
        continue;
      }
      final last = data['newStartPageToken'];
      if (last is! String || last.isEmpty) {
        throw const FormatException('Drive changes cursor missing');
      }
      return OdifyRemoteDelta(cursor: last, events: events);
    }
    throw const FormatException('Drive changes feed is too large');
  }

  @override
  Future<OdifyRemoteDelta> pull({String? cursor}) async {
    if (cursor != null) return _readChanges(cursor);

    // First sync must cover files which existed BEFORE the change token.
    // Read the token FIRST, then enumerate existing files, then catch up.
    final tokenData = await _getJson('changes/startPageToken', {
      'spaces': 'appDataFolder',
    });
    final token = tokenData['startPageToken'];
    if (token is! String || token.isEmpty) {
      throw const FormatException('Drive starting cursor missing');
    }
    final knownFiles = await _listBatchFiles();
    final events = <OdifySyncEvent>[];
    for (final file in knownFiles) {
      events.addAll(await _download(file['id']! as String));
    }
    final delta = await _readChanges(token);
    events.addAll(delta.events);
    return OdifyRemoteDelta(cursor: delta.cursor, events: events);
  }

  @override
  Future<void> putIfAbsent(OdifyPendingBatch batch) async {
    if (!_batchName.hasMatch(batch.batchId) ||
        batch.events.isEmpty || batch.events.length > 100) {
      throw const FormatException('Unsafe or empty Odify upload batch');
    }
    final expected = {
      'schemaVersion': 1,
      'events': [for (final event in batch.events) event.toJson()],
    };
    // Validating at the wire boundary prevents accidental upload of Android
    // content URIs, local paths, stream tokens or unsupported operations.
    for (final event in batch.events) {
      OdifySyncEvent.fromJson(event.toJson());
    }
    final existing = await _listBatchFiles(exactName: batch.batchId);
    for (final file in existing) {
      final remote = await _download(file['id']! as String);
      if (remote.length != batch.events.length) {
        throw const FormatException('Remote batch name collision');
      }
      for (var index = 0; index < remote.length; index++) {
        if (!OdifySyncState.deepEventEquals(
          remote[index].toJson(), batch.events[index].toJson(),
        )) {
          throw const FormatException('Remote sync batch contents differ');
        }
      }
    }
    if (existing.isNotEmpty) return;

    final metadata = jsonEncode({
      'name': batch.batchId,
      'mimeType': 'application/json',
      'parents': ['appDataFolder'],
    });
    final boundary = 'odify_${DateTime.now().microsecondsSinceEpoch}';
    final json = jsonEncode(expected);
    final body = utf8.encode(
      '--$boundary\r\n'
      'Content-Type: application/json; charset=UTF-8\r\n\r\n'
      '$metadata\r\n'
      '--$boundary\r\n'
      'Content-Type: application/json\r\n\r\n'
      '$json\r\n'
      '--$boundary--\r\n',
    );
    if (body.length > _maxBatchBytes) {
      throw const FormatException('Upload batch exceeds safe limit');
    }
    final uri = Uri.parse('$_upload/files')
        .replace(queryParameters: {'uploadType': 'multipart', 'fields': 'id'});
    await _send(
      'POST',
      uri,
      bytes: body,
      contentType: 'multipart/related; boundary=$boundary',
    );
  }
}

class OdifyDriveSyncError implements Exception {
  const OdifyDriveSyncError(this.statusCode);
  final int statusCode;

  bool get shouldReauthorize => statusCode == 401 || statusCode == 403;
  bool get shouldRetry => statusCode == 429 || statusCode >= 500;

  @override
  String toString() => 'Odify Drive API error (HTTP $statusCode)';
}
