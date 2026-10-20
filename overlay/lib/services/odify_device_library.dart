import 'dart:io';

import 'package:permission_handler/permission_handler.dart';
import 'package:spotiflac_android/providers/local_library_provider.dart';

/// First-time import of common folders visible to Samsung Music. Both apps
/// reference the existing files; no copies are created.
///
/// Samsung Music's private playlists/favourites are NOT accessible this way.
/// Export an M3U/M3U8 playlist from Samsung Music and use SpotiFLAC's existing
/// playlist import flow for those collections.
class OdifyDeviceMusicImport {
  static const defaultFolders = <String, String>{
    '/storage/emulated/0/Music': 'Device Music',
    '/storage/emulated/0/Download': 'Downloaded Music',
    '/storage/emulated/0/Podcasts': 'Podcasts',
  };

  static Future<List<String>> connect(LocalLibraryNotifier notifier) async {
    if (!Platform.isAndroid) return const [];
    final result = await Permission.audio.request();
    if (!result.isGranted) {
      throw StateError('Music permission is required to scan device audio.');
    }
    final connected = <String>[];
    for (final entry in defaultFolders.entries) {
      if (!await Directory(entry.key).exists()) continue;
      try {
        final source = await notifier.addSource(
          path: entry.key,
          displayName: entry.value,
        );
        await notifier.startSourceScan(source.id);
        connected.add(entry.value);
      } catch (_) {
        // One inaccessible folder must not hide successful imports from others.
      }
    }
    if (connected.isEmpty) {
      throw StateError('No accessible shared music folders were found.');
    }
    return connected;
  }
}
