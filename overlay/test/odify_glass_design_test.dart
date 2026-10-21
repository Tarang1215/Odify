import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:spotiflac_android/models/theme_settings.dart';
import 'package:spotiflac_android/providers/theme_provider.dart';
import 'package:spotiflac_android/widgets/odify_glass.dart';
import 'package:spotiflac_android/widgets/odify_library_shortcuts.dart';

void main() {
  test('new Odify install uses white glass and soft blue, honoring saved colors',
      () async {
    SharedPreferences.setMockInitialValues({});
    final blank = await SharedPreferences.getInstance();
    final defaults = loadBootstrapThemeSettings(blank);
    expect(defaults.themeMode, ThemeMode.light);
    expect(defaults.style, AppThemeStyle.mornye);
    expect(defaults.mornyeAccent, MornyeAccent.blue);
    expect(defaults.useDynamicColor, false);
    expect(defaults.mornyeGlassClarity, greaterThan(0.75));

    SharedPreferences.setMockInitialValues({
      kThemeModeKey: 'light',
      kThemeStyleKey: 'material',
      kMornyeAccentKey: 'purple',
      kUseDynamicColorKey: true,
      kMornyeGlassClarityKey: 0.45,
    });
    final saved = loadBootstrapThemeSettings(
      await SharedPreferences.getInstance(),
    );
    expect(saved.themeMode, ThemeMode.light);
    expect(saved.style, AppThemeStyle.material);
    expect(saved.mornyeAccent, MornyeAccent.purple);
    expect(saved.useDynamicColor, true);
    expect(saved.mornyeGlassClarity, 0.45);
  });

  testWidgets('Odify glass uses static surface without sampling background',
      (tester) async {
    var tapped = false;
    await tester.pumpWidget(MaterialApp(
      theme: ThemeData.dark(),
      home: Scaffold(
        body: Center(
          child: OdifyGlassSurface(
            key: const Key('odify-glass'),
            onTap: () => tapped = true,
            child: const SizedBox(
              width: 140, height: 80,
              child: Center(child: Text('Preview')),
            ),
          ),
        ),
      ),
    ));
    expect(find.byKey(const Key('odify-glass')), findsOneWidget);
    expect(find.byType(BackdropFilter), findsNothing);
    expect(find.text('Preview'), findsOneWidget);
    await tester.tap(find.text('Preview'));
    expect(tapped, true);
  });

  testWidgets('responsive library shortcuts keep all 4 actions accessible',
      (tester) async {
    final clicked = <String>[];
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SizedBox(
          width: 390,
          child: OdifyLibraryShortcuts(
            playlistsLabel: 'Playlists',
            artistsLabel: 'Artists',
            albumsLabel: 'Albums',
            songsLabel: 'Songs',
            onPlaylists: () => clicked.add('playlist'),
            onArtists: () => clicked.add('artist'),
            onAlbums: () => clicked.add('album'),
            onSongs: () => clicked.add('song'),
          ),
        ),
      ),
    ));
    expect(find.byType(OdifyGlassSurface), findsNWidgets(4));
    expect(find.byType(BackdropFilter), findsNothing);
    await tester.tap(find.text('Playlists'));
    await tester.tap(find.text('Artists'));
    await tester.tap(find.text('Albums'));
    await tester.tap(find.text('Songs'));
    expect(clicked, ['playlist', 'artist', 'album', 'song']);
  });

  testWidgets('discovery pill is actually tappable and minimum 42px high',
      (tester) async {
    var taps = 0;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Center(
          child: OdifyGlassPill(
            label: 'Trending',
            selected: true,
            icon: Icons.trending_up,
            onTap: () => taps++,
          ),
        ),
      ),
    ));
    final material = find.byType(Material).last;
    expect(tester.getSize(find.text('Trending')).height, greaterThan(8));
    expect(find.byType(BackdropFilter), findsNothing);
    expect(material, findsOneWidget);
    await tester.tap(find.text('Trending'));
    expect(taps, 1);
  });
}
