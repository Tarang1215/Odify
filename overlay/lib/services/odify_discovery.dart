import 'dart:math';

import 'package:spotiflac_android/services/music_player_service.dart'
    show OdifyTasteProfile;
import 'package:spotiflac_android/services/odify_likes.dart';
import 'package:spotiflac_android/services/odify_youtube.dart';

/// An explainable seed: no listening history or likes leave the phone.
class OdifyDiscoveryQuery {
  const OdifyDiscoveryQuery(this.term, this.reason, this.weight);

  final String term;
  final String reason;
  final double weight;
}

class OdifyDiscoveryPlan {
  const OdifyDiscoveryPlan({required this.queries, required this.knownSongs});

  final List<OdifyDiscoveryQuery> queries;
  final Set<String> knownSongs;

  /// Turn local likes and the last 60 days of listening into a small set of
  /// searches. A bounded query count avoids hammering yt-dlp on every visit.
  static OdifyDiscoveryPlan fromSignals({
    required Map<String, double> artistMinutes,
    required Map<String, double> trackMinutes,
    required Set<String> likedKeys,
  }) {
    final artistWeight = <String, double>{};
    for (final entry in artistMinutes.entries) {
      final artist = entry.key.trim();
      if (artist.isEmpty || artist == 'unknown artist' || entry.value <= 0) {
        continue;
      }
      artistWeight[artist] = log1p(entry.value);
    }
    final songs = <(String artist, String title)>[];
    for (final key in likedKeys.toList()..sort()) {
      final separator = key.indexOf('|');
      if (separator < 1 || separator >= key.length - 1) continue;
      final artist = key.substring(0, separator).trim();
      final title = key.substring(separator + 1).trim();
      if (artist.isEmpty || title.isEmpty) continue;
      artistWeight[artist] = (artistWeight[artist] ?? 0) + 5;
      songs.add((artist, title));
    }
    final artists = artistWeight.entries.toList()
      ..sort((a, b) {
        final weight = b.value.compareTo(a.value);
        return weight != 0 ? weight : a.key.compareTo(b.key);
      });
    final queries = <OdifyDiscoveryQuery>[];
    final used = <String>{};
    void add(String term, String reason, double weight) {
      if (queries.length >= 5) return;
      final cleaned = term.trim();
      if (cleaned.isNotEmpty && used.add(cleaned.toLowerCase())) {
        queries.add(OdifyDiscoveryQuery(cleaned, reason, weight));
      }
    }
    // Covers of familiar songs, but prefer different performers.
    for (final song in songs.take(2)) {
      add('${song.$1} ${song.$2} cover 歌ってみた', '좋아하는 노래의 커버', 12);
    }
    // Other recordings and songs from the user's favorite artists.
    for (final entry in artists.take(3)) {
      add('${entry.key} official audio music', '좋아하는 아티스트의 음악', entry.value);
    }
    if (queries.length < 5 && songs.isNotEmpty) {
      final song = songs.first;
      add('${song.$1} ${song.$2} live', '좋아하는 노래의 라이브', 9);
    }
    return OdifyDiscoveryPlan(
      queries: List.unmodifiable(queries),
      knownSongs: Set.unmodifiable({...trackMinutes.keys, ...likedKeys}),
    );
  }

  static double log1p(double value) => log(1 + max(0.0, value));
}

class OdifyRecommendation {
  const OdifyRecommendation({
    required this.video,
    required this.reason,
    required this.score,
  });

  final OdifyVideo video;
  final String reason;
  final double score;
}

/// Pure, repeatable ranking: suppress previously heard originals and duplicate
/// YouTube IDs; preserve covers as different recordings of a favorite song.
class OdifyDiscoveryRanker {
  static String _text(String value) => value.trim().toLowerCase()
      .replaceAll(RegExp(r'\s+'), ' ');

  static List<OdifyRecommendation> rank(
    Map<OdifyDiscoveryQuery, List<OdifyVideo>> results,
    OdifyDiscoveryPlan plan, {
    int limit = 20,
  }) {
    final bestById = <String, OdifyRecommendation>{};
    final heardTitles = <String>{};
    for (final key in plan.knownSongs) {
      final separator = key.indexOf('|');
      if (separator > 0 && separator < key.length - 1) {
        final title = _text(key.substring(separator + 1));
        if (title.length >= 5) heardTitles.add(title);
      }
    }
    for (final entry in results.entries) {
      final query = entry.key;
      for (final video in entry.value) {
        if (video.id.length != 11 || video.title.trim().isEmpty) continue;
        if (video.duration > 0 && (video.duration < 45 || video.duration > 1200)) {
          continue; // Ignore Shorts and long mixes for music recommendations.
        }
        final title = _text(video.title);
        final isCover = video.isCover ||
            RegExp(r'cover|歌ってみた|커버|弾いてみた').hasMatch(title);
        final isLive = title.contains('live') || title.contains('라이브') ||
            title.contains('ライブ');
        // Only non-cover recordings are skipped. Cover artists deserve their
        // own recommendation, even when the original is in the library.
        if (!isCover && heardTitles.any((known) =>
            title == known || title.startsWith('$known (') ||
            title.startsWith('$known ['))) continue;
        final score = query.weight.clamp(0, 15).toDouble() +
            (isCover ? 3.5 : 0) + (isLive ? 1.5 : 0) +
            (video.duration >= 90 && video.duration <= 480 ? 1 : 0);
        final reason = isCover ? '취향에 맞는 커버곡' :
            isLive ? '취향에 맞는 라이브' : query.reason;
        final candidate = OdifyRecommendation(
          video: video, reason: reason, score: score,
        );
        final previous = bestById[video.id];
        if (previous == null || score > previous.score) {
          bestById[video.id] = candidate;
        }
      }
    }
    final ranked = bestById.values.toList()..sort((a, b) {
      final score = b.score.compareTo(a.score);
      return score != 0 ? score : a.video.id.compareTo(b.video.id);
    });
    final byChannel = <String, int>{};
    final chosen = <OdifyRecommendation>[];
    for (final item in ranked) {
      final channel = _text(item.video.artist);
      if (channel.isNotEmpty && (byChannel[channel] ?? 0) >= 3) continue;
      chosen.add(item);
      if (channel.isNotEmpty) byChannel[channel] = (byChannel[channel] ?? 0) + 1;
      if (chosen.length >= limit) break;
    }
    return chosen;
  }
}

class OdifyDiscover {
  static List<OdifyRecommendation>? _cache;
  static DateTime? _cacheAt;
  static int _likesRevision = -1;
  static Future<List<OdifyRecommendation>>? _pending;

  static Future<List<OdifyRecommendation>> recommendations({
    bool refresh = false,
  }) async {
    final now = DateTime.now();
    if (!refresh && _cache != null && _cacheAt != null &&
        _likesRevision == OdifyLikeStore.revision &&
        now.difference(_cacheAt!) < const Duration(minutes: 20)) {
      return _cache!;
    }
    if (_pending != null) return _pending!;
    final pending = _load();
    _pending = pending;
    try {
      return await pending;
    } finally {
      if (identical(_pending, pending)) _pending = null;
    }
  }

  static Future<List<OdifyRecommendation>> _load() async {
    final taste = await OdifyTasteProfile.fromLocalHistory();
    final plan = OdifyDiscoveryPlan.fromSignals(
      artistMinutes: taste.artistMinutes,
      trackMinutes: taste.trackMinutes,
      likedKeys: taste.likedKeys,
    );
    if (plan.queries.isEmpty) return const [];
    final data = <OdifyDiscoveryQuery, List<OdifyVideo>>{};
    var failures = 0;
    // Each query is deliberately sequential: yt-dlp's Python interpreter and
    // Android media extraction should not run 5 full searches concurrently.
    for (final query in plan.queries) {
      try {
        data[query] = await OdifyYouTube.search(query.term, limit: 12);
      } catch (_) {
        failures++;
      }
    }
    if (failures == plan.queries.length) {
      throw StateError('추천 음악 검색에 연결할 수 없습니다.');
    }
    final ranked = OdifyDiscoveryRanker.rank(data, plan);
    _cache = List.unmodifiable(ranked);
    _cacheAt = DateTime.now();
    _likesRevision = OdifyLikeStore.revision;
    return _cache!;
  }
}
