import 'dart:io';

import 'package:google_sign_in/google_sign_in.dart';

import 'drive_sync_transport.dart';

/// Metadata only. OAuth access tokens are never persisted to Odify databases,
/// telemetry, or GitHub. GoogleSignIn owns credentials in OS-managed storage.
class OdifyGoogleAccount {
  const OdifyGoogleAccount({required this.id, required this.email});
  final String id;
  final String email;
}

abstract class OdifyDriveAuth {
  bool get isConfigured;
  Future<OdifyGoogleAccount?> restore();
  Future<OdifyGoogleAccount> connect();
  Future<String> accessToken();
  Future<void> signOut();
}

/// Android-first OAuth adapter. Desktop Windows needs a different
/// authorization flow; do not attempt GoogleSignIn's unsupported Windows SDK.
/// A web OAuth client ID is a PUBLIC configuration value, not a client secret.
/// The Android package com.odify.music and APK signing SHA must be registered
/// in the same Google Cloud project before this works.
class OdifyGoogleDriveAuth implements OdifyDriveAuth {
  OdifyGoogleDriveAuth._();
  static final OdifyGoogleDriveAuth instance = OdifyGoogleDriveAuth._();

  static const _serverClientId = String.fromEnvironment(
    'ODIFY_GOOGLE_SERVER_CLIENT_ID',
  );
  static const _scopes = <String>[OdifyDriveSyncTransport.scope];
  final GoogleSignIn _signIn = GoogleSignIn.instance;
  Future<void>? _initialization;
  GoogleSignInAccount? _account;

  @override
  bool get isConfigured => Platform.isAndroid && _serverClientId.isNotEmpty;

  Future<void> _initialize() {
    if (!isConfigured) {
      throw StateError(
        'Android Google OAuth 설정이 필요합니다. '
        'Odify 전용 Google Cloud 프로젝트 및 서명 SHA를 등록하세요.',
      );
    }
    return _initialization ??= _signIn.initialize(
      serverClientId: _serverClientId,
    );
  }

  @override
  Future<OdifyGoogleAccount?> restore() async {
    if (!isConfigured) return null;
    await _initialize();
    final restored = await _signIn.attemptLightweightAuthentication();
    _account = restored;
    if (restored == null) return null;
    // A previous identity is not sufficient to infer Drive consent.
    // Actual sync checks Drive authorization separately in accessToken().
    return OdifyGoogleAccount(id: restored.id, email: restored.email);
  }

  /// Call only from a user-tapped Connect button. OAuth consent may display.
  @override
  Future<OdifyGoogleAccount> connect() async {
    await _initialize();
    if (!_signIn.supportsAuthenticate()) {
      throw UnsupportedError('Interactive Google login is unavailable here');
    }
    // If a different account should be used, explicitly Sign out first.
    final signedIn = _account ?? await _signIn.authenticate();
    // This is intentionally separate from silent login; only consent to
    // Drive appDataFolder when the user requested sync.
    await signedIn.authorizationClient.authorizeScopes(_scopes);
    _account = signedIn;
    return OdifyGoogleAccount(id: signedIn.id, email: signedIn.email);
  }

  /// No interaction from timers/background tasks; surface an actionable error
  /// instead of prompting for new Drive permission unexpectedly.
  @override
  Future<String> accessToken() async {
    final account = _account;
    if (account == null) throw StateError('Google 계정을 먼저 연결하세요.');
    final authorization =
        await account.authorizationClient.authorizationForScopes(_scopes);
    if (authorization == null) {
      throw StateError('Google Drive 권한이 만료되었습니다. 동기화를 다시 연결하세요.');
    }
    return authorization.accessToken;
  }

  @override
  Future<void> signOut() async {
    if (_initialization != null) {
      await _signIn.signOut();
    }
    _account = null;
  }
}
