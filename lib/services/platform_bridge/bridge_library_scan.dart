part of '../platform_bridge.dart';

class LibraryScanNDJSONFile {
  final File file;
  final int expectedCount;
  final int errorCount;

  const LibraryScanNDJSONFile({
    required this.file,
    required this.expectedCount,
    this.errorCount = 0,
  });

  Stream<Map<String, dynamic>> rows() async* {
    await for (final line
        in file
            .openRead()
            .transform(utf8.decoder)
            .transform(const LineSplitter())) {
      if (line.trim().isEmpty) continue;
      final decoded = jsonDecode(line);
      if (decoded is! Map) {
        throw const FormatException('Library scan NDJSON row is not an object');
      }
      yield Map<String, dynamic>.from(decoded);
    }
  }

  Future<void> delete() async {
    await deleteFileQuietly(file);
    // Remove the resumable scan checkpoint after ingestion.
    await deleteFileQuietly(File('${file.path}.state'));
  }
}
