import 'dart:async';

import 'package:flutter/material.dart';
import 'package:spotiflac_android/services/odify_sync/drive_sync_transport.dart';
import 'package:spotiflac_android/services/odify_sync/google_drive_auth.dart';
import 'package:spotiflac_android/services/odify_sync/sync_coordinator.dart';
import 'package:spotiflac_android/services/odify_sync/sync_merge.dart';
import 'package:spotiflac_android/services/odify_sync/sync_repository.dart';

/// Opt-in cloud sync settings. Does not access Drive in Home/player startup.
class OdifySyncSettingsScreen extends StatefulWidget {
  const OdifySyncSettingsScreen({super.key, this.auth});

  /// Test dependency injection: no Google SDK invoked by widget tests.
  final OdifyDriveAuth? auth;

  @override
  State<OdifySyncSettingsScreen> createState() =>
      _OdifySyncSettingsScreenState();
}

class _OdifySyncSettingsScreenState extends State<OdifySyncSettingsScreen> {
  late final OdifyDriveAuth _auth =
      widget.auth ?? OdifyGoogleDriveAuth.instance;
  OdifyGoogleAccount? _account;
  OdifySyncRepository? _repository;
  OdifyDriveSyncTransport? _drive;
  OdifySyncState? _snapshot;
  int _pending = 0;
  bool _busy = false;
  String _status = 'Google 계정을 연결하면 동기화할 수 있습니다.';
  DateTime? _lastSync;

  @override
  void initState() {
    super.initState();
    unawaited(_restore());
  }

  @override
  void dispose() {
    final repository = _repository;
    if (repository != null) unawaited(repository.close());
    _drive?.close();
    super.dispose();
  }

  Future<void> _restore() async {
    if (!_auth.isConfigured) {
      setState(() {
        _status = 'Google Cloud OAuth 설정 전입니다. 재생에는 영향이 없습니다.';
      });
      return;
    }
    await _perform(() async {
      final account = await _auth.restore();
      if (account == null) {
        _status = '연결된 계정이 없습니다. 동기화는 선택 사항입니다.';
        return;
      }
      await _useAccount(account);
      _status = '이전 Google 계정을 찾았습니다. 동기화를 눌러 상태를 확인하세요.';
    });
  }

  Future<void> _perform(Future<void> Function() action) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await action();
    } catch (error) {
      // No raw HTTP response body or OAuth token should be included in UI.
      final message = error is OdifyDriveSyncError
          ? 'Drive 동기화 오류 (HTTP ${error.statusCode}). 권한·연결을 확인하세요.'
          : error is StateError
              ? error.message.toString()
              : '연결 또는 동기화에 실패했습니다. Google 계정 설정을 확인하세요.';
      _status = message;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _useAccount(OdifyGoogleAccount account) async {
    if (_account?.id != account.id) {
      final old = _repository;
      if (old != null) await old.close();
      _drive?.close();
      // Independent DB/Drive cursor for each Google account. No silent merge.
      _repository = OdifySyncRepository(accountId: account.id);
      _drive = OdifyDriveSyncTransport(accessToken: _auth.accessToken);
    }
    _account = account;
    await _refreshLocal();
  }

  Future<void> _refreshLocal() async {
    final repository = _repository;
    if (repository == null) return;
    _snapshot = await repository.state();
    _pending = await repository.pendingCount();
    if (mounted) setState(() {});
  }

  Future<void> _connect() => _perform(() async {
    final account = await _auth.connect();
    await _useAccount(account);
    _status = 'Google Drive 접근을 승인했습니다. 동기화를 실행합니다.';
    await _doSync();
  });

  Future<void> _doSync() async {
    final repository = _repository;
    final drive = _drive;
    if (repository == null || drive == null) return;
    // No app server, Trove, Supabase or background polling involved.
    final coordinator =
        OdifySyncCoordinator(ledger: repository, transport: drive);
    _snapshot = await coordinator.syncOnce();
    _pending = await repository.pendingCount();
    _lastSync = DateTime.now();
    _status = 'Drive 동기화 완료 · 대기 중인 변경 ${_pending}개';
    if (mounted) setState(() {});
  }

  Future<void> _sync() => _perform(_doSync);

  Future<void> _disconnect() => _perform(() async {
    await _auth.signOut();
    final old = _repository;
    _repository = null;
    _drive?.close();
    _drive = null;
    if (old != null) await old.close();
    _account = null;
    _snapshot = null;
    _pending = 0;
    _lastSync = null;
    _status = 'Google 계정 연결이 해제됐습니다. 클라우드 데이터는 유지됩니다.';
  });

  Future<void> _createPlaylist() async {
    if (_busy || _repository == null) return;
    final controller = TextEditingController();
    String? name;
    try {
      name = await showDialog<String>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: const Text('새 재생목록'),
          content: TextField(
            controller: controller,
            autofocus: true,
            maxLength: 200,
            decoration: const InputDecoration(hintText: '재생목록 이름'),
            onSubmitted: (value) => Navigator.of(dialogContext).pop(value),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('취소'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(controller.text),
              child: const Text('만들기'),
            ),
          ],
        ),
      );
    } finally {
      controller.dispose();
    }
    final trimmed = name?.trim() ?? '';
    if (trimmed.isEmpty || !mounted) return;
    await _perform(() async {
      await _repository!.recordLocalChange(
        op: 'playlist.create',
        payload: {
          'playlistId': OdifySyncClock.newId(),
          'name': trimmed,
        },
      );
      await _refreshLocal();
      _status = '재생목록이 기기에 저장됐습니다. 동기화로 Drive에 반영하세요.';
    });
  }

  @override
  Widget build(BuildContext context) {
    final account = _account;
    final playlists = _snapshot?.playlists.values
            .where((playlist) => !playlist.deleted)
            .toList()
          ?..sort((a, b) => a.name.compareTo(b.name));
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Odify · 동기화 및 백업')),
      body: ListView(
        padding: const EdgeInsets.all(18),
        children: [
          const ListTile(
            contentPadding: EdgeInsets.zero,
            leading: Icon(Icons.cloud_outlined),
            title: Text('Google Drive 개인 동기화'),
            subtitle: Text(
              'Trove/Supabase와 독립적으로 재생목록 변경 기록만 '
              'Google Drive 앱 전용 공간에 저장합니다.',
            ),
          ),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    account?.email ?? 'Google 계정 미연결',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 8),
                  Text(_status, style: TextStyle(color: colors.onSurfaceVariant)),
                  if (_lastSync != null) ...[
                    const SizedBox(height: 8),
                    Text('마지막 동기화: ${_lastSync!.hour.toString().padLeft(2, '0')}:${_lastSync!.minute.toString().padLeft(2, '0')}'),
                  ],
                  const SizedBox(height: 12),
                  if (_busy) const LinearProgressIndicator(),
                  if (account == null)
                    FilledButton.icon(
                      onPressed: _busy || !_auth.isConfigured ? null : _connect,
                      icon: const Icon(Icons.login),
                      label: const Text('Google 계정 연결 및 Drive 권한 승인'),
                    )
                  else ...[
                    FilledButton.icon(
                      onPressed: _busy ? null : _sync,
                      icon: const Icon(Icons.sync),
                      label: const Text('지금 동기화'),
                    ),
                    OutlinedButton(
                      onPressed: _busy ? null : _disconnect,
                      child: const Text('계정 연결 해제'),
                    ),
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              const Expanded(child: Text(
                '동기화 재생목록',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              )),
              IconButton(
                tooltip: '새 재생목록 만들기',
                onPressed: account == null || _busy ? null : _createPlaylist,
                icon: const Icon(Icons.add_circle_outline),
              ),
            ],
          ),
          if (account == null)
            const ListTile(title: Text('계정을 연결하면 재생목록을 확인할 수 있습니다.'))
          else if (playlists == null || playlists.isEmpty)
            const ListTile(title: Text('아직 동기화 재생목록이 없습니다.'))
          else for (final playlist in playlists)
            ListTile(
              leading: const Icon(Icons.queue_music),
              title: Text(playlist.name),
              subtitle: Text('${playlist.orderedEntries.length}곡'),
            ),
          const SizedBox(height: 12),
          Text(
            '현재 베타 기능은 재생목록 생성·이름 목록 동기화입니다. '
            '기존 SpotiFLAC 재생목록과 좋아요 자동 이관, '
            '곡 추가·순서 변경은 후속 통합 단계에서 연결합니다. '
            'MP3/FLAC 파일과 스트리밍 주소는 업로드하지 않습니다. '
            '로그인하지 않아도 음악 재생은 정상적으로 사용할 수 있습니다.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: colors.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }
}
