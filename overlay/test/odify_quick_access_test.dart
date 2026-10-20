import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:spotiflac_android/widgets/odify_quick_access.dart';

void main() {
  testWidgets('Odify Discover entry needs no search extension', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: OdifyQuickAccess(showDeviceLibrary: false),
          ),
        ),
      ),
    );

    expect(find.text('Odify'), findsOneWidget);
    expect(find.text('Discover · For You'), findsOneWidget);
    expect(
      find.text('음악 · 커버곡 검색은 확장 기능 설치 없이 이용합니다.'),
      findsOneWidget,
    );
    expect(find.byIcon(Icons.headphones_rounded), findsOneWidget);
  });
}
