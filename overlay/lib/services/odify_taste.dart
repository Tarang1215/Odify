part of 'music_player_service.dart';

/// Local taste prior that only reads SpotiFLAC's on-device listening history.
class OdifyTasteProfile {
  const OdifyTasteProfile({
    required this.artistMinutes,
    required this.trackMinutes,
    required this.totalMinutes,
  });

  final Map<String, double> artistMinutes;
  final Map<String, double> trackMinutes;
  final double totalMinutes;

  static OdifyTasteProfile? _cached;
  static DateTime? _cachedAt;

  static Future<OdifyTasteProfile> fromLocalHistory() async {
    final now = DateTime.now();
    if (_cached != null &&
        _cachedAt != null &&
        now.difference(_cachedAt!) < const Duration(minutes: 10)) {
      return _cached!;
    }
    try {
      final summary = await ListeningStatisticsStore.instance.load(
        DateTime(now.year, now.month, now.day - 59),
        DateTime(now.year, now.month, now.day + 1),
      );
      final artists = <String, double>{};
      final tracks = <String, double>{};
      for (final row in summary.artists.entries) {
        final key = normalizeLookupText(row.key);
        if (key.isNotEmpty && row.value > 0) {
          artists[key] = row.value / 60000.0;
        }
      }
      for (final row in summary.tracks.take(500)) {
        if (row.milliseconds <= 0) continue;
        final key = normalizeLookupText(row.track.artist) +
            '|' + normalizeLookupText(row.track.title);
        tracks[key] = row.milliseconds / 60000.0;
      }
      final result = OdifyTasteProfile(
        artistMinutes: artists,
        trackMinutes: tracks,
        totalMinutes: summary.milliseconds / 60000.0,
      );
      _cached = result;
      _cachedAt = now;
      return result;
    } catch (_) {
      return const OdifyTasteProfile(
        artistMinutes: {},
        trackMinutes: {},
        totalMinutes: 0,
      );
    }
  }

  double bonus(PlayableMedia media) {
    if (totalMinutes <= 0) return 0;
    final artist = normalizeLookupText(media.artist);
    final key = artist + '|' + normalizeLookupText(media.title);
    final artistTime = artistMinutes[artist] ?? 0;
    final trackTime = trackMinutes[key] ?? 0;
    final boost = min(4.0, log(1 + artistTime) * 1.2);
    final penalty = min(2.5, log(1 + trackTime) * 0.8);
    return (boost - penalty).clamp(-2.5, 4.0).toDouble();
  }
}
