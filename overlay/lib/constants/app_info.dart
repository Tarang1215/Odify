import 'package:flutter/foundation.dart';

class AppInfo {
  static const String version = '0.9.1';
  static const String upstreamBaseVersion = '5.2.1';
  static const String buildNumber = '171';

  static const String gitCommit = String.fromEnvironment('GIT_COMMIT');
  static String get shortGitCommit =>
      gitCommit.length > 8 ? gitCommit.substring(0, 8) : gitCommit;

  static String get displayVersion => kDebugMode ? 'Internal' : version;

  static const String appName = 'Odify';
  static const String copyright = '© 2026 Zarz Eleutherius';

  static const String mobileAuthor = 'zarzet';
  static const String originalAuthor = 'afkarxyz';

  static const String githubRepo = 'Tarang1215/Odify';
  static const String githubUrl = 'https://github.com/$githubRepo';
  static const String originalGithubUrl =
      'https://github.com/afkarxyz/SpotiFLAC';
  static const String remoteConfigApiUrl =
      'https://raw.githubusercontent.com/Tarang1215/Odify/main/config/odify_remote.json';

  /// Upstream is for license/credits only, never for runtime updates.
  static const String upstreamGithubUrl =
      'https://github.com/mornyeapp/Mornye';

  static const String kofiUrl = 'https://ko-fi.com/zarzet';
  static const String githubSponsorsUrl = 'https://github.com/sponsors/zarzet/';
}

