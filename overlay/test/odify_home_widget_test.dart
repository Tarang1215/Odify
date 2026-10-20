import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:spotiflac_android/services/odify_discovery.dart';
import 'package:spotiflac_android/services/odify_home_feed.dart';
import 'package:spotiflac_android/services/odify_likes.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:spotiflac_android/services/odify_youtube.dart';
import 'package:spotiflac_android/widgets/odify_home_feed.dart';

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('Home never starts the native extractor until explicitly requested',
      (tester) async {
    var calls = 0;
    final service = OdifyHomeFeedService(
      search: (query, limit) async {
        calls++;
        return const [
          OdifyVideo(
            id: 'abcdefghijk',
            title: 'Sample K-pop',
            artist: 'Example Artist',
            thumbnail: '',
            isCover: false,
            duration: 220,
          ),
        ];
      },
      taste: () async =>
          const OdifyDiscoveryPlan(queries: [], knownSongs: {}),
    );
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: OdifyHomeFeed(service: service),
            ),
          ),
        ),
      ),
    );
    expect(find.text('오늘의 음악 탐색'), findsOneWidget);
    expect(find.text('나를 위한 추천'), findsOneWidget);
    expect(calls, 0, reason: 'Home must not block its first paint');

    // A delayed native search could kill a real Android 16 process even
    // though widget tests appear green. No elapsed time may start it.
    await tester.pump(const Duration(seconds: 15));
    expect(calls, 0, reason: 'Entering Home must not start yt-dlp');
    expect(find.byKey(const Key('odify-home-load-preview')), findsOneWidget);
    await tester.tap(find.byKey(const Key('odify-home-load-preview')));
    await tester.pump();
    await tester.pump();
    await tester.pump();
    expect(find.text('Sample K-pop'), findsWidgets);
    expect(find.textContaining('공식 차트 순위 아님'), findsOneWidget);
    expect(find.textContaining('취향 학습 전'), findsOneWidget);
    expect(calls, 1, reason: 'Empty taste reuses cached discovery results');
  });

  testWidgets('Home heart records a like and gives visible feedback',
      (tester) async {
    final service = OdifyHomeFeedService(
      search: (query, limit) async => const [
        OdifyVideo(
          id: 'abcdefghijk',
          title: 'Favorite Song',
          artist: 'Musician',
          thumbnail: '',
          isCover: false,
          duration: 200,
        ),
      ],
      taste: () async =>
          const OdifyDiscoveryPlan(queries: [], knownSongs: {}),
    );
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: OdifyHomeFeed(service: service),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.byKey(const Key('odify-home-load-preview')));
    await tester.pump();
    await tester.pump();
    await tester.pump();
    expect(find.byTooltip('좋아요'), findsWidgets);
    await tester.tap(find.byTooltip('좋아요').first);
    await tester.pump();
    expect(
      (await OdifyLikeStore.read()).contains(
        OdifyLikeStore.trackKey('Musician', 'Favorite Song'),
      ),
      true,
    );
    expect(find.byTooltip('좋아요 취소'), findsWidgets);
  });

}
