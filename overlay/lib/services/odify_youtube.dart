import 'dart:io';

import 'package:flutter/services.dart';
import 'package:spotiflac_android/services/playable_media.dart';
import 'package:spotiflac_android/services/library_database.dart';
import 'package:spotiflac_android/providers/local_library_provider.dart';

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

/// The native plugin searches video metadata and extracts an audio URL only
/// when the user requests playback. Never persist signed stream addresses.
class OdifyYouTube {
  static const _channel = MethodChannel('com.odify.music/yt_dlp');
  static final _videoId = RegExp(r'^[A-Za-z0-9_-]{11}

  static Future<List<OdifyVideo>> search(String query) async {
    final raw = await _channel.invokeListMethod<dynamic>('search', {'query': query});
    return [for (final item in raw ?? const []) if (item is Map) OdifyVideo.fromMap(item)];
  }

  static Future<List<OdifyVideo>> playlist(String url) async {
    final raw = await _channel.invokeListMethod<dynamic>('playlist', {'url': url});
    return [for (final item in raw ?? const []) if (item is Map) OdifyVideo.fromMap(item)];
  }

  /// Persist a video ID, not a short-lived signed audio stream address.
  static PlayableMedia playable(OdifyVideo video) => PlayableMedia(
    id: 'odify-youtube:${video.id}',
    source: sourceForId(video.id),
    title: video.title,
    artist: video.artist,
    artUri: video.thumbnail,
    duration: video.duration > 0 ? Duration(seconds: video.duration) : null,
    format: 'stream',
  );

  /// Called from the existing SpotiFLAC audio service before playback.
  static Future<String> resolveAudioSource(String source) async {
    final id = idFromSource(source);
    if (id == null) throw const FormatException('Invalid playback source');
    final data = await _channel.invokeMapMethod<dynamic, dynamic>(
      'resolve', {'videoId': id});
    final url = data?['url']?.toString() ?? '';
    final parsed = Uri.tryParse(url);
    if (parsed == null ||
        (parsed.scheme != 'https' && parsed.scheme != 'http') ||
        parsed.host.isEmpty) throw StateError('오디오 주소를 확인하지 못했습니다.');
    return url;
  }

  /// Downloads a file within Odify's app-owned directory and adds it to the
  /// existing Library. This doesn't write into Samsung Music's shared files.
  static Future<String> downloadMp3(OdifyVideo video) async {
    final data = await _channel.invokeMapMethod<dynamic, dynamic>(
      'downloadMp3', {'videoId': video.id});
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
);
  static const sourceScheme = 'odify-youtube';
  static String sourceForId(String id) {
    if (!_videoId.hasMatch(id)) throw const FormatException('Invalid video ID');
    return '$sourceScheme://video/$id';
  }
  static String? idFromSource(String source) {
    final uri = Uri.tryParse(source);
    if (uri == null || uri.scheme != sourceScheme ||
        uri.host != 'video' || uri.pathSegments.length != 1 ||
        uri.query.isNotEmpty || uri.fragment.isNotEmpty ||
        !_videoId.hasMatch(uri.pathSegments.single)) return null;
    return uri.pathSegments.single;
  }

  static Future<List<OdifyVideo>> search(String query) async {
    final raw = await _channel.invokeListMethod<dynamic>('search', {'query': query});
    return [for (final item in raw ?? const []) if (item is Map) OdifyVideo.fromMap(item)];
  }

  static Future<List<OdifyVideo>> playlist(String url) async {
    final raw = await _channel.invokeListMethod<dynamic>('playlist', {'url': url});
    return [for (final item in raw ?? const []) if (item is Map) OdifyVideo.fromMap(item)];
  }

  static Future<PlayableMedia> resolve(OdifyVideo video) async {
    final data = await _channel.invokeMapMethod<dynamic, dynamic>(
      'resolve', {'videoId': video.id});
    final url = data?['url']?.toString() ?? '';
    if (!url.startsWith('https://') && !url.startsWith('http://')) {
      throw StateError('오디오 주소를 확인하지 못했습니다.');
    }
    return PlayableMedia(
      id: 'odify-youtube:${video.id}',
      source: url,
      title: video.title,
      artist: video.artist,
      artUri: video.thumbnail,
      duration: video.duration > 0 ? Duration(seconds: video.duration) : null,
      format: 'stream',
    );
  }

  /// Downloads a file within Odify's app-owned directory and adds it to the
  /// existing Library. This doesn't write into Samsung Music's shared files.
  static Future<String> downloadMp3(OdifyVideo video) async {
    final data = await _channel.invokeMapMethod<dynamic, dynamic>(
      'downloadMp3', {'videoId': video.id});
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
