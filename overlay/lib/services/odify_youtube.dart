import 'dart:io';

import 'package:flutter/services.dart';
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
  });

  final String id, title, artist, thumbnail;
  final bool isCover;
  final int duration;

  factory OdifyVideo.fromMap(Map<dynamic, dynamic> json) => OdifyVideo(
        id: json['id']?.toString() ?? '',
        title: json['title']?.toString() ?? '',
        artist: json['artist']?.toString() ?? '',
        thumbnail: json['thumbnail']?.toString() ?? '',
        isCover: json['isCover'] == true,
        duration: (json['duration'] as num?)?.toInt() ?? 0,
      );
}

/// Persist only a stable YouTube video identity in the player's queue.
/// Extracted audio links may expire and must be resolved immediately before
/// playback rather than stored in the session or listening history.
class OdifyYouTube {
  static const _channel = MethodChannel('com.odify.music/yt_dlp');
  static final _idPattern = RegExp(r'^[A-Za-z0-9_-]{11}$');
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
  static Future<String> resolveAudioSource(String source) async {
    final id = idFromSource(source);
    if (id == null) throw const FormatException('Invalid Odify playback source');
    final data = await _channel.invokeMapMethod<dynamic, dynamic>(
      'resolve', {'videoId': id},
    );
    final url = data?['url']?.toString() ?? '';
    final parsed = Uri.tryParse(url);
    if (parsed == null ||
        (parsed.scheme != 'https' && parsed.scheme != 'http') ||
        parsed.host.isEmpty) {
      throw StateError('오디오 주소를 확인하지 못했습니다.');
    }
    return url;
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
