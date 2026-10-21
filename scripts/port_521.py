#!/usr/bin/env python3
"""Reviewed, fail-closed Odify compatibility patches for Mornye v5.2.1 only.

Odify's own Dart/Kotlin modules are overlaid; this script patches minimal
integration seams in the pinned framework. No silent upstream updates.
"""
from __future__ import annotations

import argparse
from pathlib import Path


def one(path: Path, before: str, after: str) -> None:
    source = path.read_text(encoding="utf-8")
    count = source.count(before)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one reviewed anchor, got {count}: {before[:95]!r}")
    path.write_text(source.replace(before, after), encoding="utf-8")


def replace_category_grid(library: Path) -> None:
    one(library,
        "import 'package:mornye/widgets/local_library_artwork_builder.dart';",
        "import 'package:mornye/widgets/local_library_artwork_builder.dart';\n"
        "import 'package:mornye/widgets/odify_library_shortcuts.dart';")
    source = library.read_text(encoding="utf-8")
    begin = "            if (_overview && _query.isEmpty)\n              SliverPadding("
    ending = "            // The seasonal Rewind, beside the listening history it replays."
    if source.count(begin) != 1 or source.count(ending) != 1:
        raise RuntimeError("Mornye Library overview anchors changed")
    start = source.index(begin)
    stop = source.index(ending, start)
    if stop <= start:
        raise RuntimeError("Mornye Library overview order changed")
    replacement = """            if (_overview && _query.isEmpty)
              SliverPadding(
                padding: EdgeInsets.fromLTRB(24 + listInset, 0, 24 + listInset, 20),
                sliver: SliverToBoxAdapter(
                  child: OdifyLibraryShortcuts(
                    playlistsLabel: context.l10n.searchPlaylists,
                    artistsLabel: context.l10n.searchArtists,
                    albumsLabel: context.l10n.searchAlbums,
                    songsLabel: context.l10n.searchSongs,
                    onPlaylists: () => widget.onOpenSection('playlists'),
                    onArtists: () => _openPage(MornyeLibraryPage.artists),
                    onAlbums: () => _openPage(MornyeLibraryPage.albums),
                    onSongs: () => widget.onOpenSection('all'),
                  ),
                ),
              ),
"""
    library.write_text(source[:start] + replacement + source[stop:],
                       encoding="utf-8")


def patch_settings(settings: Path) -> None:
    source = settings.read_text(encoding="utf-8")
    fragments = [
        "import 'dart:async';\n",
        "import 'package:intl/intl.dart';\n",
        "import 'package:mornye/screens/settings/donate_page.dart';\n",
        "import 'package:mornye/widgets/app_progress_indicator.dart';\n",
        "    this.showDonationProgress = false,\n",
        "  final bool showDonationProgress;\n",
        """          _Destination(
            icon: Icons.favorite_outline,
            iconColor: Colors.pink,
            title: l10n.settingsDonate,
            subtitle: l10n.settingsDonateSubtitle,
            keywords: const ['support', 'ko-fi', 'sponsor'],
            pageBuilder: () =>
                DonatePage(remoteConfigService: _remoteConfigService),
            showDonationProgress: true,
          ),
""",
        """    if (page is DonatePage) {
      unawaited(_loadDonationGoal(refresh: false));
    }

""",
        "    final goal = _monthlyGoal;\n",
        """      footer: destination.showDonationProgress && goal?.isVisible == true
          ? _DonationGoalProgress(goal: goal!)
          : null,
""",
    ]
    for fragment in fragments:
        if source.count(fragment) != 1:
            raise RuntimeError("Upstream Settings donor contract changed: " + fragment[:60])
        source = source.replace(fragment, "")
    begin = "  bool _hasRequestedDonationGoal = false;\n"
    end = "  @override\n  void dispose() {"
    if source.count(begin) != 1 or source.count(end) != 1:
        raise RuntimeError("Upstream donor lifecycle changed")
    start, stop = source.index(begin), source.index(end, source.index(begin))
    source = source[:start] + source[stop:]
    begin = "class _DonationGoalProgress extends StatelessWidget {"
    if source.count(begin) != 1:
        raise RuntimeError("Upstream donation progress class changed")
    source = source[:source.index(begin)]
    for leftover in ("DonatePage", "settingsDonate", "_loadDonationGoal",
                     "showDonationProgress", "_DonationGoalProgress", "fetchConfigSnapshot"):
        if leftover in source:
            raise RuntimeError(f"Donor flow was not fully removed: {leftover}")
    settings.write_text(source, encoding="utf-8")


def patch_profile(profile: Path) -> None:
    one(profile,
        "import 'package:mornye/widgets/settings_group.dart';",
        "import 'package:mornye/widgets/settings_group.dart';\n"
        "import 'package:mornye/widgets/odify_sync_settings.dart';")
    one(profile,
        "                    const SizedBox(height: 24),\n"
        "                    AppActionButton(\n"
        "                      onPressed: _busy ? null : _save,",
        """                    const SizedBox(height: 16),
                    SettingsGroup(
                      margin: EdgeInsets.zero,
                      children: [
                        ListTile(
                          key: const Key('odify-profile-drive-sync'),
                          leading: const Icon(Icons.cloud_sync_outlined),
                          title: const Text('Google 계정 · Drive 동기화'),
                          subtitle: const Text('재생목록과 좋아요 동기화 관리'),
                          trailing: const Icon(Icons.chevron_right_rounded),
                          onTap: _busy ? null : () => Navigator.of(context).push(
                            MaterialPageRoute<void>(
                              builder: (_) => const OdifySyncSettingsScreen(),
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 24),
                    AppActionButton(
                      onPressed: _busy ? null : _save,""")


def patch_theme(root: Path) -> None:
    p = root / "lib/providers/theme_provider.dart"
    one(p, "(ref) => const ThemeSettings(),",
        "(ref) => const ThemeSettings(\n"
        "    themeMode: ThemeMode.light,\n"
        "    style: AppThemeStyle.mornye,\n"
        "    mornyeAccent: MornyeAccent.blue,\n"
        "    useDynamicColor: false,\n"
        "    mornyeGlassClarity: 0.82,\n"
        "  ),")
    for before, after in (
        ("themeModeFromString(prefs.getString(kThemeModeKey))",
         "themeModeFromString(prefs.getString(kThemeModeKey) ?? 'light')"),
        ("prefs.getBool(kUseDynamicColorKey) ?? true",
         "prefs.getBool(kUseDynamicColorKey) ?? false"),
        ("themeStyleFromString(prefs.getString(kThemeStyleKey))",
         "themeStyleFromString(prefs.getString(kThemeStyleKey) ?? 'mornye')"),
        ("mornyeAccentFromString(prefs.getString(kMornyeAccentKey))",
         "mornyeAccentFromString(prefs.getString(kMornyeAccentKey) ?? 'blue')"),
        ("prefs.getDouble(kMornyeGlassClarityKey)",
         "prefs.getDouble(kMornyeGlassClarityKey) ?? 0.82"),
    ):
        one(p, before, after)
    # Migrate inherited green preference once, preserving explicit changes.
    one(p, "mornyeAccentFromString(prefs.getString(kMornyeAccentKey) ?? 'blue')",
        """mornyeAccentFromString(
      (prefs.getBool('odify_blue_theme_migrated_v1') ?? false)
          ? prefs.getString(kMornyeAccentKey) ?? 'blue'
          : ((prefs.getString(kMornyeAccentKey) ?? 'green') == 'green'
              ? 'blue' : prefs.getString(kMornyeAccentKey)),
    )""")
    one(p, "      var loaded = loadBootstrapThemeSettings(prefs);",
        """      var loaded = loadBootstrapThemeSettings(prefs);
      if (!(prefs.getBool('odify_blue_theme_migrated_v1') ?? false)) {
        if ((prefs.getString(kMornyeAccentKey) ?? 'green') == 'green') {
          await prefs.setString(kMornyeAccentKey, 'blue');
          if (prefs.getString(kThemeModeKey) == 'dark') {
            await prefs.setString(kThemeModeKey, 'light');
            loaded = loaded.copyWith(themeMode: ThemeMode.light);
          }
        }
        await prefs.setBool('odify_blue_theme_migrated_v1', true);
      }""")
    one(root / "lib/theme/mornye_theme.dart",
        "MornyeAccent.blue => CupertinoColors.systemBlue,",
        """MornyeAccent.blue => const CupertinoDynamicColor.withBrightness(
          color: Color(0xff526f9e), darkColor: Color(0xff9fb8e2),
        ),""")
    r = root / "lib/providers/runtime_profile_provider.dart"
    one(r,
        """  if (!lowEnd && ref.watch(deviceSupportsLiquidGlassProvider)) {
    return MornyeGlassLevel.liquid;
  }
  // The user's "always use blur effects" override restores the full material.
  if (ref.watch(backdropBlurEnabledProvider)) return MornyeGlassLevel.liquid;
  return lowEnd ? MornyeGlassLevel.flat : MornyeGlassLevel.frosted;""",
        """  final forceEffects = ref.watch(
    settingsProvider.select((s) => s.forceBackdropBlur),
  );
  if (forceEffects && ref.watch(deviceSupportsLiquidGlassProvider)) {
    return MornyeGlassLevel.liquid;
  }
  if (lowEnd && !forceEffects) return MornyeGlassLevel.flat;
  return MornyeGlassLevel.frosted;""")
    one(root / "lib/widgets/mini_player.dart",
        "            child: MornyeGlass.navigation(\n"
        "              strongTint: true,",
        "            child: MornyeGlass.navigation(\n"
        "              radius: 24,\n"
        "              highlightBorder: true,\n"
        "              strongTint: true,")


def patch_art(root: Path) -> None:
    p = root / "lib/widgets/local_library_artwork_builder.dart"
    one(p, "import 'package:mornye/services/downloaded_embedded_cover_resolver.dart';",
        "import 'package:mornye/services/downloaded_embedded_cover_resolver.dart';\n"
        "import 'package:mornye/services/odify_device_artwork.dart';")
    one(p,
        """    final cover =
        _coverPath ??
        DownloadedEmbeddedCoverResolver.resolve(
          widget.filePath,
          onChanged: _onCoverChanged,
        );""",
        """    final cover =
        _coverPath ??
        (OdifyDeviceArtwork.isMediaStoreAudioUri(widget.filePath)
            ? OdifyDeviceArtwork.resolve(
                widget.filePath, onChanged: _onCoverChanged,
              )
            : DownloadedEmbeddedCoverResolver.resolve(
                widget.filePath, onChanged: _onCoverChanged,
              ));""")


def patch_updater(root: Path) -> None:
    info = root / "lib/constants/app_info.dart"
    identity = info.read_text(encoding="utf-8")
    if "static const String version = '5.2.1';" in identity:
        for old, new in (
            ("static const String version = '5.2.1';",
             "static const String version = '0.9.1';"),
            ("static const String buildNumber = '163';",
             "static const String buildNumber = '171';"),
            ("static const String appName = 'Mornye';",
             "static const String appName = 'Odify';"),
            ("static const String githubRepo = 'spotiflacapp/SpotiFLAC-Mobile';",
             "static const String githubRepo = 'Tarang1215/Odify';"),
        ):
            one(info, old, new)
    # Also support the Odify-owned app_info.dart overlay. Never rewrite
    # existing account data or fall back to the upstream release domain.
    own_identity = info.read_text(encoding="utf-8")
    for own_constant in (
        "static const String version = '0.9.1';",
        "static const String buildNumber = '171';",
        "static const String appName = 'Odify';",
        "static const String githubRepo = 'Tarang1215/Odify';",
    ):
        if own_constant not in own_identity:
            raise RuntimeError("Odify app identity missing: " + own_constant)
    expected_remote = (
        "https://raw.githubusercontent.com/Tarang1215/Odify/"
        "main/config/odify_remote.json"
    )
    if expected_remote not in own_identity:
        one(info,
            "static const String remoteConfigApiUrl =\n"
            "      'https://api.zarz.moe/v1/spotiflac-mobile/config';",
            "static const String remoteConfigApiUrl =\n"
            f"      '{expected_remote}';")
    one(root / "lib/services/update_checker.dart",
        "  Future<UpdateInfo?> checkForUpdate({String channel = 'stable'}) async {\n",
        "  Future<UpdateInfo?> checkForUpdate({String channel = 'stable'}) async {\n"
        "    // Odify controls its releases; never compare with Mornye versions.\n"
        "    // Disabled until Odify can ship signed release updates safely.\n"
        "    if (!const bool.fromEnvironment('ODIFY_ENABLE_UPDATER',\n"
        "        defaultValue: false)) return null;\n")


def patch_startup_prompts(root: Path) -> None:
    """Remove vendor upgrade tours and forced updater from the Odify shell."""
    path = root / "lib/screens/main_shell_startup_prompts.dart"
    source = path.read_text(encoding="utf-8")
    before = "  Future<void> _checkUpgradeIntro() async {"
    between = "  Future<bool> _checkForUpdates() async {"
    after = "  Future<void> _checkAppAnnouncement() async {"
    if any(source.count(x) != 1 for x in (before, between, after)):
        raise RuntimeError("Mornye startup update/intro API changed")
    if "UpdateChecker().checkForUpdate(" not in source:
        raise RuntimeError("Expected inherited updater not present")
    start = source.index(before)
    end = source.index(after)
    source = (
        source[:start]
        + "  Future<void> _checkUpgradeIntro() async {\\n"
          "    // Odify has its own onboarding, not the Mornye 5.2 release tour.\\n"
          "  }\\n\\n"
          "  Future<bool> _checkForUpdates() async {\\n"
          "    if (_hasCheckedUpdate) return false;\\n"
          "    _hasCheckedUpdate = true;\\n"
          "    return false; // Never force installation of a vendor APK.\\n"
          "  }\\n\\n"
        + source[end:]
    )
    # Convert intended escaped newlines to actual newlines when inserted.
    source = source.replace("  Future<void> _checkUpgradeIntro() async {\\n", "  Future<void> _checkUpgradeIntro() async {\n")
    source = source.replace("    // Odify has its own onboarding, not the Mornye 5.2 release tour.\\n", "    // Odify has its own onboarding, not the Mornye 5.2 release tour.\n")
    source = source.replace("  }\\n\\n  Future<bool> _checkForUpdates() async {\\n", "  }\n\n  Future<bool> _checkForUpdates() async {\n")
    source = source.replace("    if (_hasCheckedUpdate) return false;\\n", "    if (_hasCheckedUpdate) return false;\n")
    source = source.replace("    _hasCheckedUpdate = true;\\n", "    _hasCheckedUpdate = true;\n")
    source = source.replace("    return false; // Never force installation of a vendor APK.\\n", "    return false; // Never force installation of a vendor APK.\n")
    source = source.replace("  }\\n\\n  Future<void> _checkAppAnnouncement()", "  }\n\n  Future<void> _checkAppAnnouncement()")
    if "UpdateChecker().checkForUpdate(" in source or "showUpdateDialog(" in source:
        raise RuntimeError("Inherited vendor upgrade flow remains")
    path.write_text(source, encoding="utf-8")


def port(root: Path) -> None:
    if not (root / "pubspec.yaml").is_file():
        raise FileNotFoundError("Missing pinned Mornye 5.2.1 workspace")
    patch_theme(root)
    patch_art(root)
    patch_profile(root / "lib/screens/settings/profile_settings_page.dart")
    patch_settings(root / "lib/screens/settings/settings_tab.dart")
    replace_category_grid(root / "lib/screens/mornye_library_screen.dart")
    patch_updater(root)
    patch_startup_prompts(root)
    # The upstream MainShell compiles these imports into the same library as
    # main_shell_startup_prompts.dart. They become unused once the vendor
    # update and rebrand dialogs are removed, so delete only reviewed imports.
    shell = root / "lib/screens/main_shell.dart"
    for import_line in (
        "import 'package:shared_preferences/shared_preferences.dart';",
        "import 'package:mornye/screens/upgrade_intro_screen.dart';",
        "import 'package:mornye/services/upgrade_intro_service.dart';",
        "import 'package:mornye/services/update_checker.dart';",
        "import 'package:mornye/widgets/update_dialog.dart';",
    ):
        one(shell, import_line + "\n", "")
    print("Applied reviewed Odify native-screen integration and isolated update policy.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("target", type=Path)
    args = parser.parse_args()
    port(args.target.resolve())


if __name__ == "__main__":
    main()

