import 'dart:convert';
import 'dart:io';

/// Lightweight independent fallback for the built-in Mornye lyrics chain.
/// No secret, user account, third-party extension or server is required.
/// Only searches on explicit user action; does not crawl the music library.
class OdifyLyricsFallback {
  static const _host = 'lrclib.net';

  static String cleanTitle(String title) => title
      .replaceAll(RegExp(r'\s*[\[(](?:official\s*(?:music\s*)?(?:video|audio|m/?v)|m/?v|lyrics?|visuali[sz]er|4k)[\])]\s*',
          caseSensitive: false), ' ')
      .replaceAll(RegExp(r'\s+'), ' ').trim();

  static String cleanArtist(String artist) =>
      artist.replaceAll(RegExp(r'\s*[-–—]\s*topic$', caseSensitive: false), '').trim();

  static String normalized(String s) =>
      s.toLowerCase().replaceAll(RegExp(r'[^\p{L}\p{N}]', unicode: true), '');

  static bool matches(Map<String, dynamic> candidate, String title, String artist) {
    final theirTitle = candidate['trackName']?.toString() ?? '';
    final theirArtist = candidate['artistName']?.toString() ?? '';
    final wantedTitle = normalized(cleanTitle(title));
    final wantedArtist = normalized(cleanArtist(artist));
    if (wantedTitle.isEmpty || wantedArtist.isEmpty) return false;
    return normalized(cleanTitle(theirTitle)) == wantedTitle &&
        normalized(cleanArtist(theirArtist)) == wantedArtist;
  }

  static Future<String> search({
    required String title,
    required String artist,
    int durationMs = 0,
  }) async {
    final cleanSong = cleanTitle(title);
    final cleanPerformer = cleanArtist(artist);
    if (cleanSong.isEmpty || cleanPerformer.isEmpty) return '';

    final client = HttpClient()..connectionTimeout = const Duration(seconds: 5);
    client.userAgent = 'Odify/0.9.0 (https://github.com/Tarang1215/Odify)';
    try {
      Future<dynamic> fetch(Uri url) async {
        final req = await client.getUrl(url).timeout(const Duration(seconds: 6));
        req.headers.set('Lrclib-Client', 'Odify/0.9.0');
        final response = await req.close().timeout(const Duration(seconds: 7));
        if (response.statusCode != 200) return null;
        // LRCLIB has many long LRC records, but we do not allow an
        // unbounded payload to consume the Flutter UI isolate.
        final body = await response
            .transform(utf8.decoder)
            .join()
            .timeout(const Duration(seconds: 8));
        if (body.length > 256000) return null;
        return jsonDecode(body);
      }

      final exactArgs = <String, String>{
        'track_name': cleanSong,
        'artist_name': cleanPerformer,
        if (durationMs >= 1000 && durationMs <= 3600000)
          'duration': (durationMs ~/ 1000).toString(),
      };
      final exact = await fetch(Uri.https(_host, '/api/get', exactArgs));
      if (exact is Map) {
        final match = Map<String, dynamic>.from(exact);
        if (matches(match, cleanSong, cleanPerformer)) {
          final lyrics = match['syncedLyrics'] ?? match['plainLyrics'];
          if (lyrics is String && lyrics.trim().isNotEmpty) return lyrics;
        }
      }
      // Search is more tolerant than /api/get of user edits and minor track
      // duration differences, but reject mismatched cover/artist versions.
      final found = await fetch(Uri.https(_host, '/api/search', {
        'track_name': cleanSong,
        'artist_name': cleanPerformer,
      }));
      if (found is List) {
        for (final entry in found) {
          if (entry is! Map) continue;
          final match = Map<String, dynamic>.from(entry);
          if (!matches(match, cleanSong, cleanPerformer)) continue;
          final lyrics = match['syncedLyrics'] ?? match['plainLyrics'];
          if (lyrics is String && lyrics.trim().isNotEmpty) return lyrics;
        }
      }
    } catch (_) {
      // Offline, rate-limited, or blocked providers yield a controlled miss.
      // Audio playback and scrolling should continue without interruption.
    } finally {
      client.close(force: true);
    }
    return '';
  }
}
