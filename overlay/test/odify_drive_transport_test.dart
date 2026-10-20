import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:spotiflac_android/services/odify_sync/drive_sync_transport.dart';
import 'package:spotiflac_android/services/odify_sync/sync_merge.dart';
import 'package:spotiflac_android/services/odify_sync/sync_repository.dart';

const filename = 'odify-v1-batch-00000000000000000000000000000000.json';

OdifySyncEvent example() => const OdifySyncEvent(
  eventId: 'e1',
  deviceId: 'galaxy',
  hlc: '2026-10-20T07:00:00.000Z#0001',
  op: 'playlist.create',
  payload: {'playlistId': 'p1', 'name': 'Japanese Covers'},
);

void main() {
  test('bootstrap scans appData then catches up from first change token',
      () async {
    final event = example();
    final calls = <String>[];
    final client = MockClient((request) async {
      calls.add(request.url.path);
      expect(request.url.host, 'www.googleapis.com');
      expect(
        request.headers.entries.any((header) =>
          header.key.toLowerCase() == 'authorization' &&
          header.value == 'Bearer test-token'),
        true,
      );

      if (request.url.path.endsWith('changes/startPageToken')) {
        expect(request.url.queryParameters['spaces'], 'appDataFolder');
        return http.Response('{"startPageToken":"5"}', 200);
      }
      if (request.url.path.endsWith('/files') &&
          request.method == 'GET') {
        expect(request.url.queryParameters['spaces'], 'appDataFolder');
        return http.Response(jsonEncode({
          'files': [{'id': 'f1', 'name': filename}],
        }), 200);
      }
      if (request.url.path.endsWith('/files/f1')) {
        expect(request.url.queryParameters['alt'], 'media');
        return http.Response(jsonEncode({
          'schemaVersion': 1,
          'events': [event.toJson()],
        }), 200);
      }
      if (request.url.path.endsWith('/changes')) {
        expect(request.url.queryParameters['pageToken'], '5');
        return http.Response(jsonEncode({
          'changes': [],
          'newStartPageToken': '6',
        }), 200);
      }
      return http.Response('{"error":"Unexpected test URL"}', 404);
    });
    final transport = OdifyDriveSyncTransport(
      accessToken: () async => 'test-token',
      client: client,
    );
    final delta = await transport.pull();
    expect(delta.cursor, '6');
    expect(delta.events.single.eventId, 'e1');
    expect(calls, contains('/drive/v3/files/f1'));
  });

  test('upload uses hidden Drive parent and multipart/related', () async {
    var created = 0;
    final client = MockClient((request) async {
      if (request.method == 'GET') {
        expect(request.url.queryParameters['q'], "name = '$filename'");
        return http.Response('{"files":[]}', 200);
      }
      if (request.method == 'POST') {
        created++;
        expect(request.url.path, '/upload/drive/v3/files');
        expect(request.url.queryParameters['uploadType'], 'multipart');
        expect(
          request.headers.entries.any((header) =>
            header.key.toLowerCase() == 'content-type' &&
            header.value.contains('multipart/related')),
          true,
        );
        final body = utf8.decode(request.bodyBytes);
        expect(body, contains('appDataFolder'));
        expect(body, contains('Japanese Covers'));
        expect(body, isNot(contains('content://')));
        return http.Response('{"id":"f-new"}', 200);
      }
      return http.Response('{}', 404);
    });
    final transport = OdifyDriveSyncTransport(
      accessToken: () async => 'test-token',
      client: client,
    );
    await transport.putIfAbsent(
      OdifyPendingBatch(filename, [example()]),
    );
    expect(created, 1);
  });

  test('retry finds existing batch and does not reupload', () async {
    var posts = 0;
    final client = MockClient((request) async {
      if (request.method == 'POST') {
        posts++;
        return http.Response('{}', 200);
      }
      if (request.url.path.endsWith('/files/f1')) {
        return http.Response(jsonEncode({
          'schemaVersion': 1, 'events': [example().toJson()],
        }), 200);
      }
      return http.Response(jsonEncode({
        'files': [{'id': 'f1', 'name': filename}],
      }), 200);
    });
    final transport = OdifyDriveSyncTransport(
      accessToken: () async => 'test-token',
      client: client,
    );
    await transport.putIfAbsent(
      OdifyPendingBatch(filename, [example()]),
    );
    expect(posts, 0);
  });

  test('HTTP 429 retains retryable error without leaking details', () async {
    final client = MockClient((_) async => http.Response(
      '{"error":"private details must not enter logs"}', 429,
    ));
    final transport = OdifyDriveSyncTransport(
      accessToken: () async => 'test-token',
      client: client,
    );
    try {
      await transport.pull();
      fail('Expected 429');
    } on OdifyDriveSyncError catch (error) {
      expect(error.statusCode, 429);
      expect(error.shouldRetry, true);
      expect(error.toString(), isNot(contains('private details')));
    }
  });
}
