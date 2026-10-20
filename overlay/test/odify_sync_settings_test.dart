import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:spotiflac_android/services/odify_sync/google_drive_auth.dart';
import 'package:spotiflac_android/services/odify_sync/sync_repository.dart';
import 'package:spotiflac_android/widgets/odify_sync_settings.dart';

class _NotConfiguredGoogleAuth implements OdifyDriveAuth {
  @override
  bool get isConfigured => false;

  @override
  Future<OdifyGoogleAccount?> restore() async =>
      throw StateError('Unconfigured settings must not access Google SDK');

  @override
  Future<OdifyGoogleAccount> connect() async =>
      throw StateError('No OAuth project is configured');

  @override
  Future<String> accessToken() async =>
      throw StateError('No OAuth token');

  @override
  Future<void> signOut() async {}
}

void main() {
  testWidgets('sync opt-in screen stays usable without Google setup',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: OdifySyncSettingsScreen(auth: _NotConfiguredGoogleAuth()),
    ));
    await tester.pump();
    expect(find.text('Odify · 동기화 및 백업'), findsOneWidget);
    expect(find.text('Google 계정 미연결'), findsOneWidget);
    expect(find.text('Google 계정 연결 및 Drive 권한 승인'),
        findsOneWidget);
    final button = tester.widget<FilledButton>(
      find.widgetWithText(FilledButton, 'Google 계정 연결 및 Drive 권한 승인'),
    );
    expect(button.onPressed, isNull);
    expect(find.textContaining('Google Cloud OAuth 설정 전입니다.'),
        findsOneWidget);
  });

  test('each Google account uses distinct opaque local database names', () {
    final a = OdifySyncRepository.storageNameFor('user-sub-AAA');
    final b = OdifySyncRepository.storageNameFor('user-sub-BBB');
    expect(a, isNot(b));
    expect(a, OdifySyncRepository.storageNameFor('user-sub-AAA'));
    expect(a, isNot(contains('user-sub-AAA')));
    expect(a.endsWith('.db'), true);
    expect(
      () => OdifySyncRepository.storageNameFor(''),
      throwsArgumentError,
    );
  });
}
