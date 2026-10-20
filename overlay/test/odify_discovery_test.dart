import 'package:flutter_test/flutter_test.dart';
import 'package:spotiflac_android/services/odify_discovery.dart';
import 'package:spotiflac_android/services/odify_youtube.dart';

void main() {
  test('Cold start without listening history or likes makes no network seeds', () {
    final plan = OdifyDiscoveryPlan.fromSignals(
      artistMinutes: const {}, trackMinutes: const {}, likedKeys: const {},
    );
    expect(plan.queries, isEmpty);
  });

  test('Likes and listening create diverse bounded search terms', () {
    final plan = OdifyDiscoveryPlan.fromSignals(
      artistMinutes: const {'aimer': 180, 'radwimps': 40},
      trackMinutes: const {'aimer|ref:rain': 20},
      likedKeys: const {'aimer|brave shine', 'radwimps|sparkle'},
    );
    expect(plan.queries.length, lessThanOrEqualTo(5));
    expect(plan.queries.any((q) => q.term.contains('brave shine') &&
      q.term.contains('cover')), isTrue);
    expect(plan.queries.any((q) => q.term.contains('radwimps')), isTrue);
    expect(plan.knownSongs.contains('aimer|ref:rain'), isTrue);
  });

  test('Heard original is skipped, but its cover remains discoverable', () {
    final query = OdifyDiscoveryQuery('artist old song', 'because you listened', 9);
    final plan = OdifyDiscoveryPlan(
      queries: [query], knownSongs: {'artist|old song'},
    );
    final matches = OdifyDiscoveryRanker.rank({query: const [
      OdifyVideo(id: 'aaaaaaaaaaa', title: 'old song', artist: 'artist',
          thumbnail: '', isCover: false, duration: 220),
      OdifyVideo(id: 'bbbbbbbbbbb', title: 'old song (Cover)', artist: 'Singer B',
          thumbnail: '', isCover: true, duration: 220),
    ]}, plan);
    expect(matches.map((r) => r.video.id), ['bbbbbbbbbbb']);
    expect(matches.single.reason, contains('커버'));
  });

  test('Duplicate videos and overly repetitive channels are limited', () {
    const first = OdifyDiscoveryQuery('A', 'A', 10);
    const second = OdifyDiscoveryQuery('B', 'B', 12);
    final plan = OdifyDiscoveryPlan(queries: [first, second], knownSongs: {});
    const videos = [
      OdifyVideo(id: 'aaaaaaaaaaa', title: 'a', artist: 'same', thumbnail: '', isCover: false),
      OdifyVideo(id: 'bbbbbbbbbbb', title: 'b', artist: 'same', thumbnail: '', isCover: false),
      OdifyVideo(id: 'ccccccccccc', title: 'c', artist: 'same', thumbnail: '', isCover: false),
      OdifyVideo(id: 'ddddddddddd', title: 'd', artist: 'same', thumbnail: '', isCover: false),
    ];
    final ranked = OdifyDiscoveryRanker.rank({
      first: videos,
      second: [videos.first],
    }, plan);
    expect(ranked.length, 3);
    expect(ranked.map((r) => r.video.id).toSet().length, 3);
  });

  test('Short clips and long mixes are excluded', () {
    const query = OdifyDiscoveryQuery('music', 'music', 5);
    final plan = OdifyDiscoveryPlan(queries: [query], knownSongs: {});
    final ranked = OdifyDiscoveryRanker.rank({query: const [
      OdifyVideo(id: 'sssssssssss', title: 'short', artist: '', thumbnail: '', isCover: false, duration: 12),
      OdifyVideo(id: 'lllllllllll', title: 'full mix', artist: '', thumbnail: '', isCover: false, duration: 4000),
      OdifyVideo(id: 'mmmmmmmmmmm', title: 'good track', artist: '', thumbnail: '', isCover: false, duration: 200),
    ]}, plan);
    expect(ranked.map((e) => e.video.id), ['mmmmmmmmmmm']);
  });
}
