import 'dart:io';

import 'package:flutter/services.dart';
import 'package:spotiflac_android/services/playable_media.dart';
import 'package:spotiflac_android/services/library_database.dart';
import 'package:spotiflac_android/services/odify_device_artwork.dart';

class OdifyDeviceTrack {
  const OdifyDeviceTrack({
    required this.id,
    required this.uri,
    required this.title,
    required this.artist,
    required this.album,
    required this.durationMs,
    this.modifiedSeconds = 0,
    this.albumId = 0,
    this.cachedCoverPath,
  });

  final String id;
  final String uri;
  final String title;
  final String artist;
  final String album;
  final int durationMs;
  final int modifiedSeconds;
  final int albumId;
  final String? cachedCoverPath;

  factory OdifyDeviceTrack.fromPlatform(Map<dynamic, dynamic> data) {
    return OdifyDeviceTrack(
      id: data['id']?.toString() ?? '',
      uri: data['uri']?.toString() ?? '',
      title: data['title']?.toString() ?? '',
      artist: data['artist']?.toString() ?? '',
      album: data['album']?.toString() ?? '',
      durationMs: (data['durationMs'] as num?)?.toInt() ?? 0,
      modifiedSeconds: (data['modifiedSeconds'] as num?)?.toInt() ?? 0,
      albumId: (data['albumId'] as num?)?.toInt() ?? 0,
      cachedCoverPath: data['cachedCoverPath'] as String?,
    );
  }

  PlayableMedia toPlayable() => PlayableMedia(
    id: 'odify-device:$id',
    source: uri,
    title: title,
    artist: artist,
    album: album,
    artUri: _artworkUri(),
    duration: durationMs > 0 ? Duration(milliseconds: durationMs) : null,
  );

  String? _artworkUri() {
    final path = OdifyDeviceArtwork.peek(
      id,
      modifiedSeconds: modifiedSeconds,
      nativeCachedCover: cachedCoverPath,
    );
    return path == null ? null : Uri.file(path).toString();
  }
}

class OdifyDeviceCatalog {
  /// Import accessible MediaStore tracks into the same local library that
  /// SpotiFLAC uses. Files remain in place; the library stores content URIs.
  /// Ids are namespaced and the final prune only touches our own snapshots.
  static Future<int> syncToLibrary() async {
    const size = 150;
    final now = DateTime.now().toUtc();
    final stamp = now.toIso8601String();
    var offset = 0;
    final database = LibraryDatabase.instance;
    while (true) {
      final tracks = await page(offset: offset, limit: size);
      if (tracks.isEmpty) break;
      await database.upsertBatch([
        for (final song in tracks)
          {
            'id': 'odify-media:${song.id}',
            'trackName': song.title.isEmpty ? 'Unknown Title' : song.title,
            'artistName': song.artist.isEmpty ? 'Unknown Artist' : song.artist,
            'albumName': song.album.isEmpty ? 'Unknown Album' : song.album,
            'filePath': song.uri,
            // Only known cached previews are indexed now; artwork misses are
            // resolved lazily when browsing or playing, never in the 20k scan.
            'coverPath': OdifyDeviceArtwork.peek(
              song.id,
              modifiedSeconds: song.modifiedSeconds,
              nativeCachedCover: song.cachedCoverPath,
            ),
            'fileModTime': song.modifiedSeconds * 1000,
            'scannedAt': stamp,
            'duration': (song.durationMs / 1000).round(),
          },
      ], sourceId: LocalLibraryItem.legacySourceId);
      offset += tracks.length;
      if (tracks.length < size) break;
      if (offset >= 20000) {
        throw StateError('Device library exceeds the current 20,000-track scan cap.');
      }
    }
    // Remove stale Odify MediaStore rows after the *full* successful scan.
    // Leave other local folder scans and downloaded tracks untouched.
    final connection = await database.database;
    await connection.delete('library',
      where: "id LIKE 'odify-media:%' AND scanned_at <> ?",
      whereArgs: [stamp]);
    return offset;
  }

  static const _channel = MethodChannel('com.odify.music/media_store');

  /// Always reads the Android MediaStore index; no duplicate MP3 copies.
  static Future<List<OdifyDeviceTrack>> page({
    int offset = 0,
    int limit = 150,
  }) async {
    if (!Platform.isAndroid) return const [];
    final response = await _channel.invokeListMethod<dynamic>('listMusic', {
      'offset': offset,
      'limit': limit,
    });
    return [
      for (final entry in response ?? const [])
        if (entry is Map)
          OdifyDeviceTrack.fromPlatform(entry),
    ];
  }
}
