import 'sync_merge.dart';

/// Portable YouTube playlist entry metadata. Never sends expiring playback
/// URLs, local files, credentials or Android content:// URIs to Google Drive.
class OdifyPlaylistTrackLink {
  static final RegExp _videoId = RegExp(r'^[A-Za-z0-9_-]{11}$');

  static String? parseYoutubeId(String input) {
    final source = input.trim();
    if (_videoId.hasMatch(source)) return source;
    final uri = Uri.tryParse(source);
    if (uri == null || uri.scheme != 'https' ||
        uri.userInfo.isNotEmpty || uri.hasPort ||
        uri.host.isEmpty) return null;
    final hostname = uri.host.toLowerCase();
    String? candidate;
    if (hostname == 'youtu.be') {
      if (uri.pathSegments.length != 1) return null;
      candidate = uri.pathSegments.single;
    } else if ({
      'youtube.com', 'www.youtube.com', 'm.youtube.com',
      'music.youtube.com', 'www.youtube-nocookie.com',
    }.contains(hostname)) {
      final segments = uri.pathSegments;
      if (segments.length == 1 && segments.single == 'watch') {
        candidate = uri.queryParametersAll['v']?.singleOrNull;
      } else if (segments.length == 2 &&
          {'shorts', 'live', 'embed'}.contains(segments.first)) {
        candidate = segments.last;
      }
    }
    return candidate != null && _videoId.hasMatch(candidate)
        ? candidate : null;
  }

  static OdifySourceRef asSource({
    required String link,
    required String title,
    String artist = '',
  }) {
    final id = parseYoutubeId(link);
    if (id == null) {
      throw const FormatException('유효한 YouTube 영상 주소를 입력하세요.');
    }
    return OdifySourceRef.fromJson({
      'kind': 'youtube',
      'videoId': id,
      'title': title.trim().isEmpty ? 'YouTube · $id' : title.trim(),
      'artist': artist.trim(),
    });
  }

  /// Stable, sortable addition key for playlists created with Odify.
  /// The random entry ID breaks ties between concurrent devices.
  static String appendPosition(String entryId, {DateTime? now}) {
    final micros = (now ?? DateTime.now()).toUtc().microsecondsSinceEpoch;
    return 'z${micros.toString().padLeft(20, '0')}-$entryId';
  }
}
