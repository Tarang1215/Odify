import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:spotiflac_android/services/odify_discovery.dart';
import 'package:spotiflac_android/services/odify_home_feed.dart';
import 'package:spotiflac_android/services/odify_youtube.dart';

const songs = <OdifyVideo>[
  OdifyVideo(
    id: 'abcdefghijk',
    title: 'New music',
    artist: 'Artist A',
    thumbnail: '',
    isCover: false,
    duration: 220,
  ),
  OdifyVideo(
    id: 'ABCDEFGHIJK',
    title: 'New cover',
    artist: 'Artist B',
    thumbnail: '',
    isCover: true,
    duration: 250,
  ),
];

void main() {
  test('Home first load bounds requests and caches per market', () async {
    final calls = <String>[];
    final service = OdifyHomeFeedService(
      search: (query, limit) async {
        calls.add(query);
        expect(limit, 12);
        return songs;
      },
      taste: () async => const OdifyDiscoveryPlan(
        queries: [], knownSongs: {},
      ),
    );

    final popular = await service.trending();
    final forYou = await service.forYou();
    expect(popular.videos.length, 2);
    expect(forYou.personalized, false);
    expect(forYou.description, contains('취향 학습 전'));
    expect(calls, hasLength(1));
    expect(await service.trending(), same(popular));
    expect(await service.forYou(), same(forYou));
    expect(calls, hasLength(1));

    final japan = await service.trending(market: 'JP');
    expect(japan.description, contains('일본'));
    expect(calls, hasLength(2));
    expect(OdifyHomeFeedService.trendingQuery('JP', year: 2026),
      contains('JPOP'));
  });

  test('Personalized Home uses only one ranked search seed', () async {
    final queries = <String>[];
    final service = OdifyHomeFeedService(
      search: (query, limit) async {
        queries.add(query);
        return songs;
      },
      taste: () async => OdifyDiscoveryPlan(
        queries: const [
          OdifyDiscoveryQuery('A cover', '좋아하는 커버', 12),
          OdifyDiscoveryQuery('B live', '라이브', 8),
          OdifyDiscoveryQuery('C original', '원곡', 6),
        ],
        knownSongs: const {},
      ),
    );

    final result = await service.forYou();
    expect(result.personalized, true);
    expect(result.videos, isNotEmpty);
    expect(queries, ['A cover']);
    await service.forYou(refresh: true);
    expect(queries, ['A cover', 'A cover']);
  });

  test('Consecutive requests serialize the expensive native search', () async {
    final blockers = <Completer<void>>[];
    var active = 0;
    var maxActive = 0;
    final service = OdifyHomeFeedService(
      search: (query, limit) async {
        active++;
        if (active > maxActive) maxActive = active;
        final blocker = Completer<void>();
        blockers.add(blocker);
        await blocker.future;
        active--;
        return songs;
      },
      taste: () async => OdifyDiscoveryPlan(
        queries: const [
          OdifyDiscoveryQuery('artist cover', 'likes', 11),
        ],
        knownSongs: const {},
      ),
    );

    final trending = service.trending();
    final personalized = service.forYou();
    await Future<void>.delayed(const Duration(milliseconds: 1));
    expect(blockers.length, 1);
    blockers.first.complete();
    await Future<void>.delayed(const Duration(milliseconds: 1));
    expect(blockers.length, 2);
    blockers.last.complete();
    await Future.wait([trending, personalized]);
    expect(maxActive, 1);
  });

  test('Invalid videos, repeated IDs and very long mixes are filtered', () async {
    final service = OdifyHomeFeedService(
      search: (query, limit) async => const [
        songs[0],
        songs[0],
        OdifyVideo(
          id: 'bad', title: 'Invalid', artist: '',
          thumbnail: '', isCover: false,
        ),
        OdifyVideo(
          id: 'lllllllllll', title: 'Three-hour mix', artist: '',
          thumbnail: '', isCover: false, duration: 10000,
        ),
      ],
      taste: () async => const OdifyDiscoveryPlan(
        queries: [], knownSongs: {},
      ),
    );
    expect((await service.trending()).videos.map((v) => v.id),
      ['abcdefghijk']);
  });
}
