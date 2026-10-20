import 'dart:async';

import 'package:spotiflac_android/services/music_player_service.dart'
    show OdifyTasteProfile;
import 'package:spotiflac_android/services/odify_discovery.dart';
import 'package:spotiflac_android/services/odify_likes.dart';
import 'package:spotiflac_android/services/odify_youtube.dart';

typedef OdifyHomeSearch = Future<List<OdifyVideo>> Function(
  String query, int limit,
);
typedef OdifyHomeTaste = Future<OdifyDiscoveryPlan> Function();

/// Search-backed discovery results, NOT an official YouTube chart or ranking.
/// No arbitrary fixed "top song" claim is made about these results.
class OdifyHomeFeedResult {
  const OdifyHomeFeedResult({
    required this.videos,
    required this.description,
    this.personalized = false,
  });

  final List<OdifyVideo> videos;
  final String description;
  final bool personalized;
}

/// Lazy, bounded, in-memory home data coordinator.
///
/// An initial Home visit performs at most two short searches: one discovery
/// query for KR songs, then one personal query based on local listening/likes.
/// Requests are serialized to avoid running parallel yt-dlp Python searches.
/// The Home UI never reads history or calls the network in build().
class OdifyHomeFeedService {
  OdifyHomeFeedService({
    OdifyHomeSearch? search,
    OdifyHomeTaste? taste,
    Duration cacheLifetime = const Duration(minutes: 30),
  })  : _search = search ??
            ((term, limit) => OdifyYouTube.search(term, limit: limit)),
        _taste = taste ?? _localPlan,
        _cacheLifetime = cacheLifetime;

  static final OdifyHomeFeedService shared = OdifyHomeFeedService();

  final OdifyHomeSearch _search;
  final OdifyHomeTaste _taste;
  final Duration _cacheLifetime;
  final Map<String, (DateTime, OdifyHomeFeedResult)> _cache = {};
  final Map<String, Future<OdifyHomeFeedResult>> _pending = {};
  Future<void> _searchTail = Future<void>.value();

  static const markets = ['KR', 'JP', 'Global'];

  static String marketLabel(String market) => switch (market) {
        'JP' => '일본',
        'Global' => '글로벌',
        _ => '한국',
      };

  static String trendingQuery(String market, {int? year}) {
    final y = year ?? DateTime.now().year;
    return switch (market) {
      'JP' => 'JPOP 人気曲 新曲 $y official music',
      'Global' => 'global popular new music $y official audio',
      _ => '한국 인기곡 신곡 $y official music',
    };
  }

  static Future<OdifyDiscoveryPlan> _localPlan() async {
    final profile = await OdifyTasteProfile.fromLocalHistory();
    return OdifyDiscoveryPlan.fromSignals(
      artistMinutes: profile.artistMinutes,
      trackMinutes: profile.trackMinutes,
      likedKeys: profile.likedKeys,
    );
  }

  Future<List<OdifyVideo>> _queuedSearch(String term, int limit) {
    final done = Completer<List<OdifyVideo>>();
    // Queue only the expensive extractor search. Metadata preparation and UI
    // updates remain independent and cannot block a paint/frame.
    _searchTail = _searchTail.then((_) async {
      try {
        done.complete(await _search(term, limit));
      } catch (error, stack) {
        done.completeError(error, stack);
      }
    });
    return done.future;
  }

  Future<OdifyHomeFeedResult> trending({
    String market = 'KR',
    bool refresh = false,
  }) {
    final region = markets.contains(market) ? market : 'KR';
    return _load(
      'trending:$region',
      () async {
        final rows = await _queuedSearch(trendingQuery(region), 12);
        return OdifyHomeFeedResult(
          videos: _validSongs(rows).take(8).toList(growable: false),
          description: '${marketLabel(region)} 음악 검색 기반 · 공식 차트 순위 아님',
        );
      },
      refresh: refresh,
    );
  }

  Future<OdifyHomeFeedResult> forYou({bool refresh = false}) {
    return _load(
      'forYou:${OdifyLikeStore.revision}',
      () async {
        final plan = await _taste();
        if (plan.queries.isEmpty) {
          final starter = await trending();
          return OdifyHomeFeedResult(
            videos: starter.videos,
            description: '취향 학습 전 추천 · 좋아요와 청취 기록을 쌓아보세요',
          );
        }

        // A single personalized query on Home, not five back-to-back calls.
        // The complete Discover screen can still load a richer five-seed mix.
        final query = plan.queries.first;
        final rows = await _queuedSearch(query.term, 12);
        final ranked = OdifyDiscoveryRanker.rank(
          {query: rows},
          plan,
          limit: 8,
        );
        if (ranked.isEmpty) {
          final starter = await trending();
          return OdifyHomeFeedResult(
            videos: starter.videos,
            description: '새 추천 결과가 없어 음악 탐색 결과를 표시합니다',
          );
        }
        return OdifyHomeFeedResult(
          videos: ranked.map((item) => item.video).toList(growable: false),
          description: '좋아요·청취 기록 기반 · ${query.reason}',
          personalized: true,
        );
      },
      refresh: refresh,
    );
  }

  Future<OdifyHomeFeedResult> _load(
    String key,
    Future<OdifyHomeFeedResult> Function() factory, {
    bool refresh = false,
  }) {
    final current = _cache[key];
    if (!refresh && current != null &&
        DateTime.now().difference(current.$1) < _cacheLifetime) {
      return Future.value(current.$2);
    }
    final inFlight = _pending[key];
    if (inFlight != null) return inFlight;
    final task = factory().then((result) {
      // Cache empty results only briefly is preferable, but do not cache
      // failures; a retry must always reattempt network operations.
      _cache[key] = (DateTime.now(), result);
      return result;
    });
    _pending[key] = task;
    task.then<void>(
      (_) => _pending.remove(key),
      onError: (Object _, StackTrace __) => _pending.remove(key),
    );
    return task;
  }

  static List<OdifyVideo> _validSongs(List<OdifyVideo> rows) {
    final ids = <String>{};
    return [
      for (final video in rows)
        if (RegExp(r'^[A-Za-z0-9_-]{11}$').hasMatch(video.id) &&
            video.title.trim().isNotEmpty &&
            (video.duration == 0 ||
                (video.duration >= 45 && video.duration <= 1200)) &&
            ids.add(video.id))
          video,
    ];
  }
}
