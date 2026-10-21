import 'dart:io';

import 'package:flutter/services.dart';
import 'package:spotiflac_android/services/odify_audio_proxy.dart';
import 'package:spotiflac_android/services/playable_media.dart';
import 'package:spotiflac_android/services/library_database.dart';

class OdifyVideo {
  const OdifyVideo({
    required this.id,
    required this.title,
    required this.artist,
    required this.thumbnail,
    required this.isCover,
    this.duration = 0,
    this.uploader = '',
    this.originalTitle = '',
  });

  final String id, title, artist, thumbnail;
  final bool isCover;
  final int duration;
  /// The channel that published this recording (not necessarily the artist).
  final String uploader;
  /// Preserve the original video title for attribution and troubleshooting.
  final String originalTitle;

  factory OdifyVideo.fromMap(Map<dynamic, dynamic> json) {
    final rawTitle = json['title']?.toString() ?? '';
    final reportedArtist = json['musicArtist']?.toString() ?? '';
    final uploader = json['channel']?.toString() ??
        json['artist']?.toString() ?? '';
    final isCover = json['isCover'] == true;
    final label = OdifyTrackMetadata.parse(
      title: rawTitle,
      uploader: uploader,
      reportedArtist: reportedArtist,
      isCover: isCover,
    );
    return OdifyVideo(
      id: json['id']?.toString() ?? '',
      title: label.title,
      artist: label.artist,
      thumbnail: json['thumbnail']?.toString() ?? '',
      isCover: isCover,
      duration: (json['duration'] as num?)?.toInt() ?? 0,
      uploader: uploader,
      originalTitle: rawTitle,
    );
  }
}

/// Channel attribution and musical performer are different, especially for
/// covers and fan uploads. Never infer an original artist from an arbitrary
/// uploader's "Artist - Title" video unless ownership can be corroborated.
class OdifyTrackMetadata {
  const OdifyTrackMetadata({required this.artist, required this.title});
  final String artist, title;

  static String _clean(String value) => value.trim();
  static String _compact(String value) => value.toLowerCase()
      .replaceAll(RegExp(r'[^\p{L}\p{N}]', unicode: true), '');

  static bool _isOfficialChannel(String channel, String artist) {
    final c = _compact(channel);
    final a = _compact(artist);
    if (c.isEmpty || a.isEmpty) return false;
    return c == a || c == '${a}topic' || c == '${a}vevo' ||
        c == '${a}official' || c == '${a}officialyoutubechannel';
  }

  static final _artistTitle = RegExp(r'^(.{1,90}?)\s+[-–—]\s+(.{1,180})$');
  static final _officialSuffix = RegExp(
    r'\s*[\[(](?:official\s+(?:music\s+)?(?:video|audio)|music\s+video|mv|m/v)[\])]\s*$',
    caseSensitive: false,
  );

  static OdifyTrackMetadata parse({
    required String title,
    required String uploader,
    String reportedArtist = '',
    bool isCover = false,
  }) {
    final raw = _clean(title);
    final channel = _clean(uploader);
    final knownArtist = _clean(reportedArtist);
    // For covers, the uploader/performer must not be replaced by the original
    // songwriter/artist appearing in a YouTube video title.
    if (isCover) {
      return OdifyTrackMetadata(
        artist: channel.isNotEmpty ? channel : knownArtist,
        title: raw,
      );
    }
    final match = _artistTitle.firstMatch(raw);
    if (match != null) {
      final prefix = _clean(match.group(1)!);
      final trustworthy = (knownArtist.isNotEmpty &&
              _compact(prefix) == _compact(knownArtist)) ||
          _isOfficialChannel(channel, prefix);
      if (trustworthy) {
        final songTitle = _clean(match.group(2)!).replaceFirst(_officialSuffix, '').trim();
        if (songTitle.isNotEmpty) {
          return OdifyTrackMetadata(
            artist: knownArtist.isEmpty ? prefix : knownArtist,
            title: songTitle,
          );
        }
      }
    }
    return OdifyTrackMetadata(
      artist: knownArtist.isNotEmpty ? knownArtist : channel,
      title: raw,
    );
  }
}

/// Persist only a stable YouTube video identity in the player's queue.
/// Extracted audio links may expire and must be resolved immediately before
/// playback rather than stored in the session or listening history.
class OdifyYouTube {
  static const _channel = MethodChannel('com.odify.music/yt_dlp');
  static final _idPattern = RegExp(r'^[A-Za-z0-9_-]{11}$');
  static final _nativeSourcePattern =
      RegExp(r'^odify-cdn://audio/[a-f0-9]{48}$');
  static const sourceScheme = 'odify-youtube';

  static String sourceForId(String videoId) {
    if (!_idPattern.hasMatch(videoId)) {
      throw const FormatException('Invalid YouTube video ID');
    }
    return '$sourceScheme://video/$videoId';
  }

  static String? idFromSource(String source) {
    final uri = Uri.tryParse(source);
    if (uri == null || uri.scheme != sourceScheme ||
        uri.host != 'video' || uri.pathSegments.length != 1 ||
        uri.query.isNotEmpty || uri.fragment.isNotEmpty ||
        !_idPattern.hasMatch(uri.pathSegments.single)) {
      return null;
    }
    return uri.pathSegments.single;
  }

  static Future<List<OdifyVideo>> search(String query, {int limit = 30}) async {
    final bounded = limit.clamp(1, 30);
    final raw = await _channel.invokeListMethod<dynamic>('search', {
      'query': query, 'limit': bounded,
    });
    return [for (final item in raw ?? const []) if (item is Map) OdifyVideo.fromMap(item)];
  }

  static Future<List<OdifyVideo>> playlist(String url) async {
    final raw = await _channel.invokeListMethod<dynamic>('playlist', {'url': url});
    return [for (final item in raw ?? const []) if (item is Map) OdifyVideo.fromMap(item)];
  }

  /// A playable item is immediately queueable. Only a stable ID is persisted.
  static PlayableMedia playable(OdifyVideo video) => PlayableMedia(
        id: 'odify-youtube:${video.id}',
        source: sourceForId(video.id),
        title: video.title,
        artist: video.artist,
        artUri: video.thumbnail,
        duration: video.duration > 0 ? Duration(seconds: video.duration) : null,
        format: 'stream',
      );

  /// Called by the existing SpotiFLAC audio service just before loading a
  /// track. The resulting signed URL stays transient and is never serialized.
  static Future<String> resolveAudioSource(
    String source, {
    bool preferNative = true,
  }) async {
    final id = idFromSource(source);
    if (id == null) throw const FormatException('Invalid Odify playback source');
    final data = await _channel.invokeMapMethod<dynamic, dynamic>(
      'resolve', {'videoId': id},
    );
    final url = data?['url']?.toString() ?? '';
    final parsed = Uri.tryParse(url);
    if (parsed == null || parsed.scheme != 'https' || parsed.host.isEmpty) {
      throw StateError('오디오 주소를 확인하지 못했습니다.');
    }
    // Native MediaPlayer.setDataSource(url, headers) eliminates the Dart
    // loopback relay and keeps seek/redirect handling inside Android's media
    // stack. Only use handles created by the bundled, pinned Kotlin plugin.
    // The existing ranged proxy remains available as a safe fallback.
    final nativeSource = data?['nativeSource']?.toString() ?? '';
    if (preferNative && _nativeSourcePattern.hasMatch(nativeSource)) {
      return nativeSource;
    }
    // Fallback: relay the signed URL and extractor headers via loopback.
    final rawHeaders = data?['headers'];
    final headers = <String, String>{
      if (rawHeaders is Map)
        for (final entry in rawHeaders.entries)
          entry.key.toString(): entry.value.toString(),
    };
    return OdifyAudioProxy.shared.open(url, headers: headers);
  }

  /// Downloads only to Odify's app-owned directory. No Samsung files change.
  static Future<String> downloadMp3(OdifyVideo video) async {
    sourceForId(video.id);
    final data = await _channel.invokeMapMethod<dynamic, dynamic>(
      'downloadMp3', {'videoId': video.id},
    );
    final path = data?['path']?.toString() ?? '';
    if (path.isEmpty || !await File(path).exists()) {
      throw StateError('변환된 MP3 파일을 찾지 못했습니다.');
    }
    await LibraryDatabase.instance.upsert({
      'id': 'odify-download:${video.id}',
      'trackName': video.title,
      'artistName': video.artist.isEmpty ? 'Unknown Artist' : video.artist,
      'albumName': 'YouTube',
      'filePath': path,
      'coverPath': video.thumbnail,
      'scannedAt': DateTime.now().toUtc().toIso8601String(),
      'duration': video.duration,
      'format': 'mp3',
    }, sourceId: LocalLibraryItem.legacySourceId);
    return path;
  }
}
