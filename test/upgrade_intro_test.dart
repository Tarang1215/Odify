import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:mornye/l10n/l10n.dart';
import 'package:mornye/models/theme_settings.dart';
import 'package:mornye/providers/theme_provider.dart';
import 'package:mornye/screens/upgrade_intro_screen.dart';
import 'package:mornye/services/upgrade_intro_service.dart';
import 'package:mornye/theme/app_theme.dart';
import 'package:mornye/theme/mornye_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() async {
    await (FontLoader(
      'MaterialIcons',
    )..addFont(rootBundle.load('fonts/MaterialIcons-Regular.otf'))).load();
    await (FontLoader(
          'packages/material_symbols_icons/MaterialSymbolsRounded',
        )..addFont(
          rootBundle.load(
            'packages/material_symbols_icons/lib/fonts/MaterialSymbolsRounded.ttf',
          ),
        ))
        .load();
    await (FontLoader(
      'Google Sans Flex',
    )..addFont(rootBundle.load('assets/fonts/GoogleSansFlex.ttf'))).load();
    await (FontLoader(
      'Inter',
    )..addFont(rootBundle.load('assets/fonts/InterVariable.ttf'))).load();
    await (FontLoader('packages/cupertino_icons/CupertinoIcons')..addFont(
          rootBundle.load('packages/cupertino_icons/assets/CupertinoIcons.ttf'),
        ))
        .load();
  });

  test(
    'rebrand tour is acknowledged separately from settings migrations',
    () async {
      // Having seen the 5.2 release tour does not hide the rebrand tour.
      SharedPreferences.setMockInitialValues({
        'settings_migration_version': 11,
        'upgrade_intro_5_2_seen': true,
      });
      var prefs = await SharedPreferences.getInstance();
      expect(UpgradeIntroService.shouldShow(prefs), isTrue);
      await UpgradeIntroService.markSeen(prefs);
      prefs = await SharedPreferences.getInstance();
      expect(UpgradeIntroService.shouldShow(prefs), isFalse);
      expect(prefs.getInt('settings_migration_version'), 11);
    },
  );

  final capture = GlobalKey();
  Future<void> openTour(
    WidgetTester tester, {
    Size size = const Size(390, 844),
    double textScale = 1,
    Brightness brightness = Brightness.light,
    AppThemeStyle style = AppThemeStyle.mornye,
  }) async {
    SharedPreferences.setMockInitialValues({kThemeStyleKey: style.name});
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          initialThemeSettingsProvider.overrideWithValue(
            ThemeSettings(style: style),
          ),
        ],
        child: Consumer(
          builder: (context, ref, _) {
            final selectedStyle = ref.watch(
              themeProvider.select((value) => value.style),
            );
            return MaterialApp(
              theme: selectedStyle == AppThemeStyle.mornye
                  ? MornyeTheme.build(brightness)
                  : brightness == Brightness.dark
                  ? AppTheme.dark()
                  : AppTheme.light(),
              localizationsDelegates: AppLocalizations.localizationsDelegates,
              supportedLocales: AppLocalizations.supportedLocales,
              builder: (context, child) => MediaQuery(
                data: MediaQuery.of(
                  context,
                ).copyWith(textScaler: TextScaler.linear(textScale)),
                child: RepaintBoundary(key: capture, child: child),
              ),
              home: Scaffold(
                body: Builder(
                  builder: (context) => TextButton(
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => const UpgradeIntroScreen(),
                      ),
                    ),
                    child: const Text('Open tour'),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
    await tester.tap(find.text('Open tour'));
    await tester.pumpAndSettle();
  }

  Future<void> capturePage(WidgetTester tester, String name) async {
    if (!const bool.fromEnvironment('CAPTURE_UPGRADE_INTRO')) return;
    await tester.runAsync(() async {
      final boundary =
          capture.currentContext!.findRenderObject()! as RenderRepaintBoundary;
      final image = await boundary.toImage(pixelRatio: 2);
      final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
      await File(
        '/tmp/mornye-intro-$name.png',
      ).writeAsBytes(bytes!.buffer.asUint8List());
      image.dispose();
    });
  }

  for (final brightness in Brightness.values) {
    testWidgets('tour advances, goes back and finishes ($brightness)', (
      tester,
    ) async {
      await openTour(tester, brightness: brightness);
      expect(find.text('Goodbye SpotiFLAC,\nhello Mornye.'), findsOneWidget);
      expect(find.bySemanticsLabel('1 / 4'), findsOneWidget);
      await capturePage(tester, '${brightness.name}-welcome');
      await tester.tap(find.text('Continue'));
      await tester.pumpAndSettle();
      expect(find.text('Make it\nyour own.'), findsOneWidget);
      await capturePage(tester, '${brightness.name}-themes');
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      expect(find.text('Goodbye SpotiFLAC,\nhello Mornye.'), findsOneWidget);
      const titles = [
        'Make it\nyour own.',
        'Bring your\nlibrary along.',
        'Same community,\nnew address.',
      ];
      for (var i = 0; i < titles.length; i++) {
        await tester.tap(find.text('Continue'));
        await tester.pumpAndSettle();
        expect(find.text(titles[i]), findsOneWidget);
        await capturePage(tester, '${brightness.name}-page${i + 2}');
      }
      expect(find.bySemanticsLabel('4 / 4'), findsOneWidget);
      expect(find.text('Continue'), findsNothing);
      await tester.tap(find.text('Start listening'));
      await tester.pumpAndSettle();
      expect(find.byType(UpgradeIntroScreen), findsNothing);
      expect(find.text('Open tour'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('theme choice applies live, persists and updates the guide', (
    tester,
  ) async {
    await openTour(tester, style: AppThemeStyle.material);
    await capturePage(tester, 'material-welcome');
    final prefs = await SharedPreferences.getInstance();
    expect(prefs.getString(kThemeStyleKey), 'material');
    await tester.tap(find.text('Continue'));
    await tester.pumpAndSettle();
    final mornye = find.byKey(const ValueKey('intro-theme-mornye'));
    await tester.ensureVisible(mornye);
    await tester.tap(mornye);
    await tester.pumpAndSettle();
    expect(prefs.getString(kThemeStyleKey), 'mornye');
    expect(tester.element(find.byType(UpgradeIntroScreen)).isMornye, isTrue);
    // Both styles walk through the same rebrand pages.
    await tester.tap(find.text('Continue'));
    await tester.pumpAndSettle();
    expect(find.text('Bring your\nlibrary along.'), findsOneWidget);
    expect(find.bySemanticsLabel('3 / 4'), findsOneWidget);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    final material = find.byKey(const ValueKey('intro-theme-material'));
    await tester.ensureVisible(material);
    await tester.tap(material);
    await tester.pumpAndSettle();
    expect(prefs.getString(kThemeStyleKey), 'material');
    expect(tester.element(find.byType(UpgradeIntroScreen)).isMornye, isFalse);
    await capturePage(tester, 'material-themes');
    await tester.drag(find.byType(PageView), const Offset(-500, 0));
    await tester.pumpAndSettle();
    expect(find.text('Bring your\nlibrary along.'), findsOneWidget);
    expect(find.bySemanticsLabel('3 / 4'), findsOneWidget);
    await tester.tap(find.text('Skip'));
    await tester.pumpAndSettle();
    expect(prefs.getString(kThemeStyleKey), 'material');
    expect(tester.takeException(), isNull);
  });

  for (final size in [const Size(320, 568), const Size(844, 390)]) {
    testWidgets(
      'tour scrolls with large text and can skip from any page ($size)',
      (tester) async {
        await openTour(tester, size: size, textScale: 2);
        await tester.tap(find.text('Continue'));
        await tester.pumpAndSettle();
        await tester.drag(
          find.byType(SingleChildScrollView).first,
          const Offset(0, -400),
        );
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await tester.tap(find.text('Skip'));
        await tester.pumpAndSettle();
        expect(find.byType(UpgradeIntroScreen), findsNothing);
      },
    );
  }
}
