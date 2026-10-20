import 'dart:async';
import 'dart:collection';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

/// Streams an extracted YouTube audio response through a loopback-only
/// HTTP endpoint. Android MediaPlayer cannot attach yt-dlp's original
/// per-format HTTP headers to a UrlSource; the relay preserves those headers,
/// Range/Content-Range and Content-Length without buffering a whole song.
///
/// Only opaque, unpredictable paths are exposed to other local processes.
/// No remote proxy endpoint, background download or long-lived file is used.
class OdifyAudioProxy {
  OdifyAudioProxy({bool Function(Uri)? allowSource})
      : _allowSource = allowSource ?? _trustedAudioSource;

  static final OdifyAudioProxy shared = OdifyAudioProxy();

  final bool Function(Uri) _allowSource;
  final LinkedHashMap<String, _AudioSession> _sessions =
      LinkedHashMap<String, _AudioSession>();
  final HttpClient _client = HttpClient()
    ..connectionTimeout = const Duration(seconds: 12)
    ..idleTimeout = const Duration(seconds: 15)
    ..maxConnectionsPerHost = 4
    ..autoUncompress = false;
  HttpServer? _server;
  Future<HttpServer>? _starting;

  static bool _trustedAudioSource(Uri uri) {
    final host = uri.host.toLowerCase();
    return uri.scheme == 'https' &&
        uri.userInfo.isEmpty &&
        (host == 'googlevideo.com' || host.endsWith('.googlevideo.com'));
  }

  static const _forwardedHeaders = {
    'user-agent',
    'referer',
    'origin',
    'accept',
    'accept-language',
  };

  /// The URL is valid for one extracted video for as long as yt-dlp's
  /// signed CDN URL remains valid. The player resolves it again on next play.
  Future<String> open(
    String remoteUrl, {
    Map<String, String> headers = const {},
  }) async {
    final remote = Uri.tryParse(remoteUrl);
    if (remote == null || !_allowSource(remote)) {
      throw const FormatException('Untrusted audio stream origin');
    }
    final safeHeaders = <String, String>{};
    for (final entry in headers.entries) {
      final name = entry.key.trim().toLowerCase();
      final value = entry.value.trim();
      if (_forwardedHeaders.contains(name) &&
          value.isNotEmpty &&
          !value.contains('\r') &&
          !value.contains('\n')) {
        safeHeaders[name] = value;
      }
    }
    safeHeaders.putIfAbsent('user-agent', () => 'Mozilla/5.0');
    final server = await _getServer();
    final random = Random.secure();
    final bytes = List<int>.generate(24, (_) => random.nextInt(256));
    final token = base64UrlEncode(bytes).replaceAll('=', '');
    _sessions[token] = _AudioSession(remote, safeHeaders);
    // Drop old identities rather than letting stale player queues accumulate.
    while (_sessions.length > 12) {
      _sessions.remove(_sessions.keys.first);
    }
    return 'http://127.0.0.1:${server.port}/stream/$token';
  }

  Future<HttpServer> _getServer() async {
    final openServer = _server;
    if (openServer != null) return openServer;
    final pending = _starting;
    if (pending != null) return pending;
    final starting = HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    _starting = starting;
    try {
      final server = await starting;
      _server = server;
      server.listen(
        (request) => unawaited(_serve(request)),
        onError: (Object _) {},
      );
      return server;
    } finally {
      _starting = null;
    }
  }

  Future<void> _serve(HttpRequest request) async {
    final path = request.uri.pathSegments;
    final token = path.length == 2 && path.first == 'stream'
        ? path.last
        : '';
    final session = _sessions[token];
    if ((request.method != 'GET' && request.method != 'HEAD') ||
        session == null ||
        request.uri.hasQuery ||
        request.uri.hasFragment) {
      request.response.statusCode = HttpStatus.notFound;
      await request.response.close();
      return;
    }

    var bodyStarted = false;
    try {
      final upstream = await _client.openUrl(request.method, session.uri);
      upstream.followRedirects = true;
      for (final header in session.headers.entries) {
        upstream.headers.set(header.key, header.value);
      }
      upstream.headers.set(HttpHeaders.acceptEncodingHeader, 'identity');
      final range = request.headers.value(HttpHeaders.rangeHeader);
      if (range != null && RegExp(r'^bytes=[0-9]+(-[0-9]*)??
        upstream.headers.set(HttpHeaders.rangeHeader, range);
      }
      final ifRange = request.headers.value(HttpHeaders.ifRangeHeader);
      if (ifRange != null && !ifRange.contains('\r') && !ifRange.contains('\n')) {
        upstream.headers.set(HttpHeaders.ifRangeHeader, ifRange);
      }
      final result = await upstream.close().timeout(
        const Duration(seconds: 18),
      );
      final outgoing = request.response;
      outgoing.statusCode = result.statusCode;
      // MediaPlayer must see exactly the CDN's response boundaries for seek.
      for (final header in const [
        HttpHeaders.contentTypeHeader,
        HttpHeaders.contentRangeHeader,
        HttpHeaders.acceptRangesHeader,
        HttpHeaders.etagHeader,
        HttpHeaders.lastModifiedHeader,
        HttpHeaders.cacheControlHeader,
      ]) {
        final value = result.headers.value(header);
        if (value != null) outgoing.headers.set(header, value);
      }
      if (result.contentLength >= 0) {
        outgoing.contentLength = result.contentLength;
      }
      if (request.method != 'HEAD') {
        bodyStarted = true;
        await outgoing.addStream(result);
      } else {
        await result.drain<void>();
      }
    } catch (_) {
      // A client can disconnect while seeking; do not tear down playback.
      try {
        if (!bodyStarted) {
          request.response.statusCode = HttpStatus.badGateway;
          request.response.headers.contentType =
              ContentType('text', 'plain', charset: 'utf-8');
          request.response.write('Audio stream is temporarily unavailable');
        }
      } catch (_) {}
    } finally {
      try {
        await request.response.close();
      } catch (_) {}
    }
  }

  /// Kept separate for tests and deterministic teardown, not called by the
  /// playback UI: audio_service may keep this process playing in background.
  Future<void> dispose() async {
    final server = _server;
    _server = null;
    _sessions.clear();
    await server?.close(force: true);
    _client.close(force: true);
  }
}

class _AudioSession {
  const _AudioSession(this.uri, this.headers);
  final Uri uri;
  final Map<String, String> headers;
}
).hasMatch(range)) {)?
        upstream.headers.set(HttpHeaders.rangeHeader, range);
      }
      final ifRange = request.headers.value(HttpHeaders.ifRangeHeader);
      if (ifRange != null && !ifRange.contains('\r') && !ifRange.contains('\n')) {
        upstream.headers.set(HttpHeaders.ifRangeHeader, ifRange);
      }
      final result = await upstream.close().timeout(
        const Duration(seconds: 18),
      );
      final outgoing = request.response;
      outgoing.statusCode = result.statusCode;
      // MediaPlayer must see exactly the CDN's response boundaries for seek.
      for (final header in const [
        HttpHeaders.contentTypeHeader,
        HttpHeaders.contentRangeHeader,
        HttpHeaders.acceptRangesHeader,
        HttpHeaders.etagHeader,
        HttpHeaders.lastModifiedHeader,
        HttpHeaders.cacheControlHeader,
      ]) {
        final value = result.headers.value(header);
        if (value != null) outgoing.headers.set(header, value);
      }
      if (result.contentLength >= 0) {
        outgoing.contentLength = result.contentLength;
      }
      if (request.method != 'HEAD') {
        bodyStarted = true;
        await outgoing.addStream(result);
      } else {
        await result.drain<void>();
      }
    } catch (_) {
      // A client can disconnect while seeking; do not tear down playback.
      try {
        if (!bodyStarted) {
          request.response.statusCode = HttpStatus.badGateway;
          request.response.headers.contentType =
              ContentType('text', 'plain', charset: 'utf-8');
          request.response.write('Audio stream is temporarily unavailable');
        }
      } catch (_) {}
    } finally {
      try {
        await request.response.close();
      } catch (_) {}
    }
  }

  /// Kept separate for tests and deterministic teardown, not called by the
  /// playback UI: audio_service may keep this process playing in background.
  Future<void> dispose() async {
    final server = _server;
    _server = null;
    _sessions.clear();
    await server?.close(force: true);
    _client.close(force: true);
  }
}

class _AudioSession {
  const _AudioSession(this.uri, this.headers);
  final Uri uri;
  final Map<String, String> headers;
}
).hasMatch(range)) {
        upstream.headers.set(HttpHeaders.rangeHeader, range);
      }
      final ifRange = request.headers.value(HttpHeaders.ifRangeHeader);
      if (ifRange != null && !ifRange.contains('\r') && !ifRange.contains('\n')) {
        upstream.headers.set(HttpHeaders.ifRangeHeader, ifRange);
      }
      final result = await upstream.close().timeout(
        const Duration(seconds: 18),
      );
      final outgoing = request.response;
      outgoing.statusCode = result.statusCode;
      // MediaPlayer must see exactly the CDN's response boundaries for seek.
      for (final header in const [
        HttpHeaders.contentTypeHeader,
        HttpHeaders.contentRangeHeader,
        HttpHeaders.acceptRangesHeader,
        HttpHeaders.etagHeader,
        HttpHeaders.lastModifiedHeader,
        HttpHeaders.cacheControlHeader,
      ]) {
        final value = result.headers.value(header);
        if (value != null) outgoing.headers.set(header, value);
      }
      if (result.contentLength >= 0) {
        outgoing.contentLength = result.contentLength;
      }
      if (request.method != 'HEAD') {
        bodyStarted = true;
        await outgoing.addStream(result);
      } else {
        await result.drain<void>();
      }
    } catch (_) {
      // A client can disconnect while seeking; do not tear down playback.
      try {
        if (!bodyStarted) {
          request.response.statusCode = HttpStatus.badGateway;
          request.response.headers.contentType =
              ContentType('text', 'plain', charset: 'utf-8');
          request.response.write('Audio stream is temporarily unavailable');
        }
      } catch (_) {}
    } finally {
      try {
        await request.response.close();
      } catch (_) {}
    }
  }

  /// Kept separate for tests and deterministic teardown, not called by the
  /// playback UI: audio_service may keep this process playing in background.
  Future<void> dispose() async {
    final server = _server;
    _server = null;
    _sessions.clear();
    await server?.close(force: true);
    _client.close(force: true);
  }
}

class _AudioSession {
  const _AudioSession(this.uri, this.headers);
  final Uri uri;
  final Map<String, String> headers;
}
