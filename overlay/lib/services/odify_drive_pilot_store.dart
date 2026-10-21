import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:file_picker/file_picker.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:spotiflac_android/services/playable_media.dart';

/// Google Drive pilot via Android's native document picker (SAF).
///
/// Unlike OAuth Drive sync, this intentionally does not read the user's whole
/// Drive or store tokens. Selected cloud files are copied into Odify's own
/// app documents folder before playback. This is an OFFLINE IMPORT MVP, not
/// direct Drive HTTP streaming.
class OdifyDrivePilotTrack {
  const OdifyDrivePilotTrack({
    required this.id,
    required this.filename,
    required this.relativePath,
    required this.bytes,
    required this.title,
    required this.artist,
    this.album = '',
  });

  final String id;
  final String filename;
  final String relativePath;
  final int bytes;
  final String title;
  final String artist;
  final String album;

  factory OdifyDrivePilotTrack.fromJson(Map<String, dynamic> json) {
    return OdifyDrivePilotTrack(
      id: json['id'] as String,
      filename: json['filename'] as String,
      relativePath: json['relativePath'] as String,
      bytes: json['bytes'] as int,
      title: json['title'] as String,
      artist: json['artist'] as String,
      album: json['album'] as String? ?? '',
    );
  }

  Map<String, dynamic> toJson() => {
    'id': id,
    'filename': filename,
    'relativePath': relativePath,
    'bytes': bytes,
    'title': title,
    'artist': artist,
    'album': album,
  };

  PlayableMedia toPlayable(String libraryRoot) => PlayableMedia(
    id: 'odify-drive-pilot:$id',
    source: p.join(libraryRoot, relativePath),
    title: title,
    artist: artist,
    album: album,
  );
}

class OdifyDrivePilotStore {
  OdifyDrivePilotStore._(this._root);

  static const _maxFileBytes = 100 * 1024 * 1024; // pilot safety guard
  static const _maxBatchFiles = 20;
  static const _extensions = {
    '.mp3', '.m4a', '.aac', '.flac', '.wav', '.ogg', '.opus',
  };

  final Directory _root;
  String get libraryRoot => _root.path;

  static Future<OdifyDrivePilotStore> open() async {
    final support = await getApplicationSupportDirectory();
    final root = Directory(p.join(support.path, 'odify_drive_pilot'));
    await root.create(recursive: true);
    return OdifyDrivePilotStore._(root);
  }

  File get _index => File(p.join(_root.path, 'index.v1.json'));

  Future<List<OdifyDrivePilotTrack>> tracks() async {
    if (!await _index.exists()) return [];
    final parsed = jsonDecode(await _index.readAsString());
    if (parsed is! Map || parsed['version'] != 1 ||
        parsed['tracks'] is! List) {
      throw const FormatException('Odify imported catalog is invalid');
    }
    final result = <OdifyDrivePilotTrack>[];
    for (final entry in parsed['tracks'] as List) {
      if (entry is! Map) continue;
      try {
        final track = OdifyDrivePilotTrack.fromJson(
          Map<String, dynamic>.from(entry),
        );
        // Only accept a generated digest basename and supported extension.
        if (!RegExp(r'^[a-f0-9]{64}\.(mp3|m4a|aac|flac|wav|ogg|opus))
            .hasMatch(track.relativePath)) {
          continue;
        }
        if (await File(p.join(_root.path, track.relativePath)).exists()) {
          result.add(track);
        }
      } catch (_) {
        // A malformed record never prevents opening valid imported songs.
      }
    }
    return result;
  }

  Future<void> _save(List<OdifyDrivePilotTrack> tracks) async {
    final tmp = File(p.join(_root.path, 'index.v1.json.tmp'));
    await tmp.writeAsString(jsonEncode({
      'version': 1,
      'tracks': tracks.map((item) => item.toJson()).toList(),
    }), flush: true);
    // rename replaces the old index atomically on supported Android storage;
    // never delete the good catalog before the new one is ready.
    await tmp.rename(_index.path);
  }

  /// Ask Android DocumentsUI. Google Drive appears as a provider when the
  /// installed Drive app is signed in; local file providers work too.
  /// Returns 0 on cancellation and never destroys an existing catalog.
  Future<int> importFromPicker() async {
    final selected = await FilePicker.pickFiles(type: FileType.audio);
    if (selected.isEmpty) return 0;
    if (selected.length > _maxBatchFiles) {
      throw StateError('한 번에 최대 20곡까지 테스트할 수 있습니다.');
    }
    final existing = await tracks();
    final byId = {for (final t in existing) t.id: t};
    var imported = 0;
    for (final selectedFile in selected) {
      final extension = p.extension(selectedFile.name).toLowerCase();
      if (!_extensions.contains(extension)) {
        throw FormatException('지원하지 않는 음악 형식: ${selectedFile.name}');
      }
      final reportedLength =
          selectedFile.lengthSync() ?? await selectedFile.length();
      if (reportedLength != null &&
          (reportedLength <= 0 || reportedLength > _maxFileBytes)) {
        throw StateError('음악 파일이 비어 있거나 100MB 제한을 초과합니다.');
      }
      final pending = File(p.join(
        _root.path,
        '.import-${DateTime.now().microsecondsSinceEpoch}.tmp',
      ));
      var count = 0;
      try {
        final sink = pending.openWrite();
        try {
          await for (final chunk in selectedFile.readAsByteStream()) {
            count += chunk.length;
            if (count > _maxFileBytes) {
              throw StateError('100MB보다 큰 음악 파일은 현재 가져올 수 없습니다.');
            }
            sink.add(chunk);
          }
          await sink.flush();
        } finally {
          await sink.close();
        }
        if (count == 0) throw const FormatException('빈 음악 파일입니다.');
        final digest = await sha256.bind(pending.openRead()).first;
        final id = digest.toString();
        final relativePath = '$id$extension';
        final destination = File(p.join(_root.path, relativePath));
        if (!await destination.exists()) {
          await pending.rename(destination.path);
        }
        if (!byId.containsKey(id)) {
          final base = p.basenameWithoutExtension(selectedFile.name);
          final divider = base.indexOf(' - ');
          final artist =
              divider > 0 ? base.substring(0, divider).trim() : '';
          final title = divider > 0
              ? base.substring(divider + 3).trim()
              : base.trim();
          byId[id] = OdifyDrivePilotTrack(
            id: id,
            filename: selectedFile.name,
            relativePath: relativePath,
            bytes: count,
            title: title.isEmpty ? selectedFile.name : title,
            artist: artist,
          );
          imported++;
        }
      } finally {
        if (await pending.exists()) await pending.delete();
      }
    }
    await _save(byId.values.toList(growable: false));
    return imported;
  }

  Future<void> remove(OdifyDrivePilotTrack track) async {
    final current = await tracks();
    await _save(current.where((e) => e.id != track.id).toList());
    final target = File(p.join(_root.path, track.relativePath));
    if (await target.exists()) await target.delete();
  }
}
)
            .hasMatch(track.relativePath)) {
          continue;
        }
        if (await File(p.join(_root.path, track.relativePath)).exists()) {
          result.add(track);
        }
      } catch (_) {
        // A malformed record never prevents opening valid imported songs.
      }
    }
    return result;
  }

  Future<void> _save(List<OdifyDrivePilotTrack> tracks) async {
    final tmp = File(p.join(_root.path, 'index.v1.json.tmp'));
    await tmp.writeAsString(jsonEncode({
      'version': 1,
      'tracks': tracks.map((item) => item.toJson()).toList(),
    }), flush: true);
    if (await _index.exists()) await _index.delete();
    await tmp.rename(_index.path);
  }

  /// Ask Android DocumentsUI. Google Drive appears as a provider when the
  /// installed Drive app is signed in; local file providers work too.
  /// Returns 0 on cancellation and never destroys an existing catalog.
  Future<int> importFromPicker() async {
    final selected = await FilePicker.pickFiles(type: FileType.audio);
    if (selected.isEmpty) return 0;
    if (selected.length > _maxBatchFiles) {
      throw StateError('한 번에 최대 20곡까지 테스트할 수 있습니다.');
    }
    final existing = await tracks();
    final byId = {for (final t in existing) t.id: t};
    var imported = 0;
    for (final selectedFile in selected) {
      final extension = p.extension(selectedFile.name).toLowerCase();
      if (!_extensions.contains(extension)) {
        throw FormatException('지원하지 않는 음악 형식: ${selectedFile.name}');
      }
      final reportedLength =
          selectedFile.lengthSync() ?? await selectedFile.length();
      if (reportedLength != null &&
          (reportedLength <= 0 || reportedLength > _maxFileBytes)) {
        throw StateError('음악 파일이 비어 있거나 100MB 제한을 초과합니다.');
      }
      final pending = File(p.join(
        _root.path,
        '.import-${DateTime.now().microsecondsSinceEpoch}.tmp',
      ));
      var count = 0;
      try {
        final sink = pending.openWrite();
        try {
          await for (final chunk in selectedFile.readAsByteStream()) {
            count += chunk.length;
            if (count > _maxFileBytes) {
              throw StateError('100MB보다 큰 음악 파일은 현재 가져올 수 없습니다.');
            }
            sink.add(chunk);
          }
          await sink.flush();
        } finally {
          await sink.close();
        }
        if (count == 0) throw const FormatException('빈 음악 파일입니다.');
        final digest = await sha256.bind(pending.openRead()).first;
        final id = digest.toString();
        final relativePath = '$id$extension';
        final destination = File(p.join(_root.path, relativePath));
        if (!await destination.exists()) {
          await pending.rename(destination.path);
        }
        if (!byId.containsKey(id)) {
          final base = p.basenameWithoutExtension(selectedFile.name);
          final divider = base.indexOf(' - ');
          final artist =
              divider > 0 ? base.substring(0, divider).trim() : '';
          final title = divider > 0
              ? base.substring(divider + 3).trim()
              : base.trim();
          byId[id] = OdifyDrivePilotTrack(
            id: id,
            filename: selectedFile.name,
            relativePath: relativePath,
            bytes: count,
            title: title.isEmpty ? selectedFile.name : title,
            artist: artist,
          );
          imported++;
        }
      } finally {
        if (await pending.exists()) await pending.delete();
      }
    }
    await _save(byId.values.toList(growable: false));
    return imported;
  }

  Future<void> remove(OdifyDrivePilotTrack track) async {
    final current = await tracks();
    await _save(current.where((e) => e.id != track.id).toList());
    final target = File(p.join(_root.path, track.relativePath));
    if (await target.exists()) await target.delete();
  }
}
