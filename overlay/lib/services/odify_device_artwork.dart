import 'dart:async';
import 'dart:collection';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:spotiflac_android/services/library_database.dart';

/// Lazy embedded-cover artwork cache for public Android MediaStore audio.
///
/// The native plug-in reads ID3/APIC artwork by *ID*, resizes it to a small
/// private JPEG and returns its local path. No original MP3 is copied.
/// Nothing is fetched on the initial Home frame or uploaded to Drive.
class OdifyDeviceArtwork {
  static const _channel = MethodChannel('com.odify.music/media_store');
  static const _maxCompleted = 240;
  static final _completed = LinkedHashMap<String, String>();
  static final _inFlight = <String, Future<String?>>{};

  static String? mediaIdFromSource(String? source) {
    if (source == null) return null;
    final uri = Uri.tryParse(source);
    if (uri == null || uri.scheme != 'content' || uri.host != 'media' ||
        uri.pathSegments.length != 4 ||
        uri.pathSegments[1] != 'audio' ||
        uri.pathSegments[2] != 'media') {
      return null;
    }
    final id = uri.pathSegments.last;
    if (!RegExp(r'^[0-9]{1,18}$').hasMatch(id) || int.tryParse(id) == 0) {
      return null;
    }
    return id;
  }

  static bool isMediaStoreAudioUri(String? source) =>
      mediaIdFromSource(source) != null;

  static String _key(String id, int? modifiedSeconds) =>
      '$id:${modifiedSeconds ?? 'latest'}';

  /// Returns the known cover without issuing I/O (suitable for playback
  /// queues). A null result is not evidence that no embedded cover exists.
  static String? peek(
    String id, {
    int? modifiedSeconds,
    String? nativeCachedCover,
  }) {
    if (nativeCachedCover != null && nativeCachedCover.isNotEmpty) {
      _remember(_key(id, modifiedSeconds), nativeCachedCover);
      return nativeCachedCover;
    }
    return _completed[_key(id, modifiedSeconds)] ?? _completed[_key(id, null)];
  }

  static void _remember(String key, String path) {
    _completed.remove(key);
    _completed[key] = path;
    while (_completed.length > _maxCompleted) {
      _completed.remove(_completed.keys.first);
    }
  }

  /// One native extraction per key at a time. The plugin executes on its own
  /// background executor and bounds decoded image size + disk cache.
  static Future<String?> forId(
    String id, {
    int? modifiedSeconds,
    String? nativeCachedCover,
  }) {
    if (!Platform.isAndroid || !RegExp(r'^[0-9]{1,18}$').hasMatch(id)) {
      return Future<String?>.value(null);
    }
    final cached = peek(
      id,
      modifiedSeconds: modifiedSeconds,
      nativeCachedCover: nativeCachedCover,
    );
    if (cached != null) return Future<String?>.value(cached);
    final key = _key(id, modifiedSeconds);
    final pending = _inFlight[key];
    if (pending != null) return pending;
    final task = _extract(id, key);
    _inFlight[key] = task;
    // Always clear on both success and failure, so permissions or missing
    // covers may be rechecked on a later explicit visit.
    task.then<void>(
      (_) => _inFlight.remove(key),
      onError: (Object _, StackTrace __) => _inFlight.remove(key),
    );
    return task;
  }

  static Future<String?> _extract(String id, String key) async {
    try {
      final path = await _channel.invokeMethod<String>(
        'getArtwork', {'id': id},
      );
      if (path == null || path.isEmpty ||
          !await File(path).exists()) {
        return null;
      }
      _remember(key, path);
      _remember(_key(id, null), path);
      // Preserve the art path in Mornye's unified Library, without touching
      // the source audio or reindexing thousands of tracks on Home startup.
      unawaited(_updateLibraryCover(id, path));
      return path;
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  static Future<void> _updateLibraryCover(String id, String path) async {
    try {
      final connection = await LibraryDatabase.instance.database;
      await connection.update(
        'library',
        {'cover_path': path},
        where: 'id = ? AND file_path = ?',
        whereArgs: [
          'odify-media:$id',
          'content://media/external/audio/media/$id',
        ],
      );
    } catch (_) {
      // A successfully extracted cover remains usable by the player even if
      // the library index is concurrently being rebuilt.
    }
  }

  /// For Mornye's LocalLibraryArtworkBuilder and player auto-art recovery.
  /// Returns null immediately on misses while notifying visible rows when a
  /// background extraction finishes.
  static String? resolve(String? source, {VoidCallback? onChanged}) {
    final id = mediaIdFromSource(source);
    if (id == null) return null;
    final cached = peek(id);
    if (cached != null) return cached;
    unawaited(forId(id).then((path) {
      if (path != null) onChanged?.call();
    }));
    return null;
  }

  static Future<String?> forSource(String source) async {
    final id = mediaIdFromSource(source);
    if (id == null) return null;
    return forId(id);
  }

  @visibleForTesting
  static void clearMemoryForTesting() {
    _completed.clear();
    _inFlight.clear();
  }
}
