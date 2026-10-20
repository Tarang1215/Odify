import 'dart:io';
import 'package:permission_handler/permission_handler.dart';
import 'package:spotiflac_android/providers/local_library_provider.dart';

/// Connect common folders that Samsung Music scans, without copying files.
/// Samsung Music's private playlists/history require an explicit export.
class OdifyDeviceMusicImport {
  static const folders = <String, String>{
    '/storage/emulated/0/Music': 'Music',
    '/storage/emulated/0/Download': 'Downloads',
    '/storage/emulated/0/Podcasts': 'Podcasts',
  };

  static Future<List<String>> connect(LocalLibraryNotifier notifier) async {
    if (!Platform.isAndroid) return const [];
    final permission = await Permission.audio.request();
    if (!permission.isGranted) {
      throw StateError('Music access permission was denied.');
    }
    final connected = <String>[];
    for (final folder in folders.entries) {
      if (!await Directory(folder.key).exists()) continue;
      try {
        final source = await notifier.addSource(
          path: folder.key,
          displayName: folder.value,
        );
        await notifier.startSourceScan(source.id);
        connected.add(folder.value);
      } catch (_) {
        // Other folders must still be importable if one is inaccessible.
      }
    }
    if (connected.isEmpty) {
      throw StateError('No accessible shared audio folder found.');
    }
    return connected;
  }
}
