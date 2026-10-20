import 'package:shared_preferences/shared_preferences.dart';

/// Odify-only explicit likes. They never overwrite Samsung Music's database.
class OdifyLikeStore {
  static const keyName = 'odify_liked_tracks_v1';
  static int revision = 0;

  static String trackKey(String artist, String title) {
    String normalize(String input) =>
        input.trim().toLowerCase().replaceAll(RegExp(r'\s+'), ' ');
    return '${normalize(artist)}|${normalize(title)}';
  }

  static Future<Set<String>> read() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getStringList(keyName)?.toSet() ?? <String>{};
  }

  static Future<Set<String>> setLiked(
    String artist,
    String title, {
    required bool liked,
  }) async {
    final current = await read();
    final key = trackKey(artist, title);
    if (liked) {
      current.add(key);
    } else {
      current.remove(key);
    }
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(keyName, current.toList()..sort());
    revision++;
    return current;
  }
}
