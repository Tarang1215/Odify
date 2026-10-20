import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:spotiflac_android/services/odify_audio_proxy.dart';

void main() {
  test('rejects non-CDN origins, localhost, and credentials', () async {
    final proxy = OdifyAudioProxy();
    try {
      await expectLater(
        proxy.open('http://127.0.0.1:8080/secrets'),
        throwsFormatException,
      );
      await expectLater(
        proxy.open('https://example.com/song.m4a'),
        throwsFormatException,
      );
      await expectLater(
        proxy.open('https://user:pass@foo.googlevideo.com/stream'),
        throwsFormatException,
      );
    } finally {
      await proxy.dispose();
    }
  });

  test('relays CDN headers, range, length, and media bytes unchanged', () async {
    final origin = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final proxy = OdifyAudioProxy(allowSource: (_) => true);
    String? forwardedRange;
    String? forwardedUserAgent;
    String? forwardedReferer;
    String? leakedCredential;
    origin.listen((request) async {
      forwardedRange = request.headers.value(HttpHeaders.rangeHeader);
      forwardedUserAgent = request.headers.value('user-agent');
      forwardedReferer = request.headers.value('referer');
      leakedCredential = request.headers.value('authorization');
      final response = request.response;
      response.statusCode = HttpStatus.partialContent;
      response.headers.set(HttpHeaders.contentTypeHeader, 'audio/mp4');
      response.headers.set(HttpHeaders.contentRangeHeader, 'bytes 5-9/10');
      response.headers.set(HttpHeaders.acceptRangesHeader, 'bytes');
      response.contentLength = 5;
      response.add([5, 6, 7, 8, 9]);
      await response.close();
    });
    final client = HttpClient();
    try {
      final url = await proxy.open(
        'http://127.0.0.1:${origin.port}/test.m4a',
        headers: {
          'User-Agent': 'OdifyTest/1',
          'Referer': 'https://www.youtube.com/',
          'Authorization': 'do-not-forward',
        },
      );
      expect(Uri.parse(url).host, '127.0.0.1');
      final request = await client.getUrl(Uri.parse(url));
      request.headers.set(HttpHeaders.rangeHeader, 'bytes=5-9');
      final result = await request.close();
      final bytes = <int>[];
      await for (final piece in result) {
        bytes.addAll(piece);
      }
      expect(result.statusCode, HttpStatus.partialContent);
      expect(result.headers.value(HttpHeaders.contentRangeHeader),
          'bytes 5-9/10');
      expect(result.headers.contentLength, 5);
      expect(result.headers.contentType?.mimeType, 'audio/mp4');
      expect(bytes, [5, 6, 7, 8, 9]);
      expect(forwardedRange, 'bytes=5-9');
      expect(forwardedUserAgent, 'OdifyTest/1');
      expect(forwardedReferer, 'https://www.youtube.com/');
      expect(leakedCredential, isNull);
    } finally {
      client.close(force: true);
      await proxy.dispose();
      await origin.close(force: true);
    }
  });

  test('loopback rejects unknown bearer tokens', () async {
    final origin = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    final proxy = OdifyAudioProxy(allowSource: (_) => true);
    final client = HttpClient();
    try {
      final valid = await proxy.open(
        'http://127.0.0.1:${origin.port}/unused',
      );
      final uri = Uri.parse(valid).replace(path: '/stream/guess');
      final response = await (await client.getUrl(uri)).close();
      await response.drain<void>();
      expect(response.statusCode, HttpStatus.notFound);
    } finally {
      client.close(force: true);
      await proxy.dispose();
      await origin.close(force: true);
    }
  });
}
