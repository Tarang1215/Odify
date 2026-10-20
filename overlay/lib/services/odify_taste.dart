part of 'music_player_service.dart';

/// A short-lived, privacy-preserving snapshot of listening habits. Stored
/// history remains in SpotiFLAC's on-device database; no cloud calls.
class OdifyTasteProfile {
  const OdifyTasteProfile({
    required this.artistMinutes,
    required this.trackMinutes,
    required this.totalMinutes,
    this.likedKeys = const <String>{},
    this.likedArtistCounts = const <String, int>{},
  });

  final Map<String, double> artistMinutes;
  final Map<String, double> trackMinutes;
  final double totalMinutes;
  final Set<String> likedKeys;
  final Map<String, int> likedArtistCounts;

  static OdifyTasteProfile? _cache;
  static DateTime? _cacheTime;
  static int _cacheLikesRevision = -1;

  static Future<OdifyTasteProfile> fromLocalHistory() async {
    final now = DateTime.now();
    final previous = _cache;
    if (previous != null &&
        _cacheTime != null &&
        now.difference(_cacheTime!) < const Duration(minutes: 10) &&
        _cacheLikesRevision == OdifyLikeStore.revision) {
      return previous;
    }
    Set<String> likes = <String>{};
    try {
      likes = await OdifyLikeStore.read();
    } catch (_) {
      // A preferences read failure must not block music playback.
    }
    final likedArtists = <String, int>{};
    for (final key in likes) {
      final sep = key.indexOf('|');
      if (sep <= 0) continue;
      final artist = normalizeLookupText(key.substring(0, sep));
      likedArtists[artist] = (likedArtists[artist] ?? 0) + 1;
    }
    try {
      // A bounded 60-day window keeps this feature cheap even for large
      // libraries. Unlike a streaming recommendation API, nothing is uploaded.
      final from = DateTime(now.year, now.month, now.day - 59);
      final until = DateTime(now.year, now.month, now.day + 1);
      final summary = await ListeningStatisticsStore.instance.load(from, until);
      final artist = <String, double>{};
      final song = <String, double>{};
      for (final entry in summary.artists.entries) {
        final key = normalizeLookupText(entry.key);
        if (key.isNotEmpty && entry.value > 0) {
          artist[key] = entry.value / 60000.0;
        }
      }
      for (final entry in summary.tracks.take(500)) {
        if (entry.milliseconds <= 0) continue;
        final key = '${normalizeLookupText(entry.track.artist)}|'
            '${normalizeLookupText(entry.track.title)}';
        song[key] = entry.milliseconds / 60000.0;
      }
      final result = OdifyTasteProfile(
        artistMinutes: artist,
        trackMinutes: song,
        totalMinutes: summary.milliseconds / 60000.0,
        likedKeys: likes,
        likedArtistCounts: likedArtists,
      );
      _cache = result;
      _cacheTime = now;
      _cacheLikesRevision = OdifyLikeStore.revision;
      return result;
    } catch (_) {
      // Never interrupt music playback when usage statistics are disabled,
      // unavailable or need migration.
      // Likes still seed recommendations even when listening stats are off.
      return OdifyTasteProfile(
        artistMinutes: const {},
        trackMinutes: const {},
        totalMinutes: 0,
        likedKeys: likes,
        likedArtistCounts: likedArtists,
      );
    }
  }

  /// Modest, bounded taste prior used alongside the upstream online learner.
  /// The repeat penalty in selectAutoplayTracks still applies afterwards.
  double bonus(PlayableMedia item) {
    if (totalMinutes <= 0 && likedKeys.isEmpty) return 0;
    final artist = normalizeLookupText(item.artist);
    final song = '$artist|${normalizeLookupText(item.title)}';
    final minutesByArtist = artistMinutes[artist] ?? 0;
    final minutesBySong = trackMinutes[song] ?? 0;
    final artistSignal = min(4.0, log(1 + minutesByArtist) * 1.2);
    // Favor an artist's unfamiliar tracks over repeating the same recording.
    final exactSongPenalty = min(2.5, log(1 + minutesBySong) * 0.8);
    final liked = likedKeys.contains(OdifyLikeStore.trackKey(item.artist, item.title));
    final artistLikeSignal = min(3.0, (likedArtistCounts[artist] ?? 0) * 0.65);
    return (artistSignal - (liked ? 0 : exactSongPenalty) +
        (liked ? 7.0 : 0.0) + artistLikeSignal).clamp(-2.5, 13.0).toDouble();
  }
}
