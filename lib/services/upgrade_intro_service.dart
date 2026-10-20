import 'package:shared_preferences/shared_preferences.dart';

/// Separate from settings migrations: migrating preferences must not consume
/// the rebrand tour before the user has actually seen it.
class UpgradeIntroService {
  /// A new key shows the tour once again.
  static const _seenKey = 'upgrade_intro_mornye_seen';

  /// Fresh installs see the tour too: the application ID changed with the
  /// rebrand, so SpotiFLAC users arrive as new Mornye installations.
  static bool shouldShow(SharedPreferences preferences) =>
      !(preferences.getBool(_seenKey) ?? false);

  static Future<void> markSeen(SharedPreferences preferences) async {
    await preferences.setBool(_seenKey, true);
  }
}
