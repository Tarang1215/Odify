import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:spotiflac_android/services/odify_discovery.dart';
import 'package:spotiflac_android/services/odify_home_feed.dart';
import 'package:spotiflac_android/services/odify_youtube.dart';
import 'package:spotiflac_android/widgets/odify_home_feed.dart';

void main() {
  testWidgets('Home explains preview and loads results after first frame',
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

    await tester.pump(const Duration(milliseconds: 950));
    await tester.pump();
    await tester.pump();
    expect(find.text('Sample K-pop'), findsWidgets);
    expect(find.textContaining('공식 차트 순위 아님'), findsOneWidget);
    expect(find.textContaining('취향 학습 전'), findsOneWidget);
    expect(calls, 1, reason: 'Empty taste reuses cached discovery results');
  });
}
