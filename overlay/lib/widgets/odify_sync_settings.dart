import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:spotiflac_android/providers/music_player_provider.dart';
import 'package:spotiflac_android/services/odify_youtube.dart';
import 'package:spotiflac_android/services/odify_sync/playlist_track_link.dart';
import 'package:spotiflac_android/services/odify_sync/drive_sync_transport.dart';
import 'package:spotiflac_android/services/odify_sync/google_drive_auth.dart';
import 'package:spotiflac_android/services/odify_sync/sync_coordinator.dart';
import 'package:spotiflac_android/services/odify_sync/sync_clock.dart';
import 'package:spotiflac_android/services/odify_sync/sync_merge.dart';
import 'package:spotiflac_android/services/odify_sync/sync_repository.dart';
import 'package:spotiflac_android/widgets/odify_glass.dart';

/// Opt-in cloud sync settings. Does not access Drive in Home/player startup.
class OdifySyncSettingsScreen extends ConsumerStatefulWidget {
  const OdifySyncSettingsScreen({super.key, this.auth});

  /// Test dependency injection: no Google SDK invoked by widget tests.
  final OdifyDriveAuth? auth;

  @override
  ConsumerState<OdifySyncSettingsScreen> createState() =>
      _OdifySyncSettingsScreenState();
}

class _OdifySyncSettingsScreenState extends ConsumerState<OdifySyncSettingsScreen> {
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
      if (!mounted) return;
      if (account == null) {
        _status = '연결된 계정이 없습니다. 동기화는 선택 사항입니다.';
        return;
      }
      await _useAccount(account);
      _status = '이전 Google 계정을 찾았습니다. 동기화를 눌러 상태를 확인하세요.';
    });
  }

  Future<void> _perform(Future<void> Function() action) async {
    if (!mounted || _busy) return;
    setState(() => _busy = true);
    try {
      await action();
    } catch (error) {
      // No raw HTTP response body or OAuth token should be included in UI.
      final message = error is OdifyDriveSyncError
          ? error.shouldReauthorize
              ? 'Drive 인증이 필요합니다 (HTTP ${error.statusCode}). 권한을 다시 승인하세요.'
              : error.shouldRetry
                  ? 'Drive가 일시적으로 응답하지 않습니다 (HTTP ${error.statusCode}). 나중에 다시 시도하세요.'
                  : 'Drive 요청을 처리하지 못했습니다 (HTTP ${error.statusCode}).'
          : error is TimeoutException
              ? '연결 시간이 초과됐습니다. 네트워크를 확인한 뒤 다시 시도하세요.'
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
      if (!mounted) return;
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

  Future<void> _renamePlaylist(OdifySyncPlaylist playlist) async {
    if (_busy || _repository == null) return;
    final controller = TextEditingController(text: playlist.name);
    String? name;
    try {
      name = await showDialog<String>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: const Text('재생목록 이름 변경'),
          content: TextField(
            controller: controller,
            autofocus: true,
            maxLength: 200,
            onSubmitted: (value) => Navigator.of(dialogContext).pop(value),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('취소'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(controller.text),
              child: const Text('저장'),
            ),
          ],
        ),
      );
    } finally {
      controller.dispose();
    }
    final trimmed = name?.trim() ?? '';
    if (!mounted || trimmed.isEmpty || trimmed == playlist.name) return;
    await _perform(() async {
      await _repository!.recordLocalChange(
        op: 'playlist.rename',
        payload: {'playlistId': playlist.id, 'name': trimmed},
      );
      await _refreshLocal();
      _status = '이름 변경을 로컬에 저장했습니다. 동기화 시 공유됩니다.';
    });
  }

  Future<void> _deletePlaylist(OdifySyncPlaylist playlist) async {
    if (_busy || _repository == null) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('재생목록 삭제'),
        content: Text('${playlist.name}을(를) 모든 동기화 기기에서 삭제합니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('삭제'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    await _perform(() async {
      await _repository!.recordLocalChange(
        op: 'playlist.delete',
        payload: {'playlistId': playlist.id},
      );
      await _refreshLocal();
      _status = '삭제 기록이 기기에 보관됐습니다. 동기화 시 다른 기기도 갱신됩니다.';
    });
  }


  /// A playlist entry contains only a video ID and human-readable metadata.
  /// Short-lived yt-dlp streaming URLs are resolved when playback begins.
  Future<void> _playPlaylist(
    OdifySyncPlaylist playlist,
    OdifySyncEntry chosen,
  ) async {
    final playable = playlist.orderedEntries
        .where((entry) => entry.source.kind == 'youtube' &&
            entry.source.videoId != null)
        .toList();
    final index = playable.indexOf(chosen);
    if (index < 0 || playable.isEmpty) return;
    await _perform(() async {
      await ref.read(musicPlayerControllerProvider).playAll(
        [
          for (final entry in playable)
            OdifyYouTube.playable(OdifyVideo(
              id: entry.source.videoId!,
              title: entry.source.title,
              artist: entry.source.artist,
              thumbnail: '',
              isCover: false,
              duration: (entry.source.durationMs ?? 0) ~/ 1000,
            )),
        ],
        initialIndex: index,
      );
      _status = '재생목록의 음악을 재생합니다.';
    });
  }

  Future<void> _addTrack(OdifySyncPlaylist playlist) async {
    if (_busy || _repository == null) return;
    final link = TextEditingController();
    final title = TextEditingController();
    final artist = TextEditingController();
    bool? submit;
    try {
      submit = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: const Text('YouTube 곡 추가'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: link,
                  autofocus: true,
                  keyboardType: TextInputType.url,
                  decoration: const InputDecoration(
                    labelText: 'YouTube 영상 주소 또는 영상 ID',
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: title,
                  maxLength: 200,
                  decoration: const InputDecoration(
                    labelText: '곡 제목 (선택)',
                    hintText: '비워두면 영상 ID로 표시',
                  ),
                ),
                TextField(
                  controller: artist,
                  maxLength: 200,
                  decoration: const InputDecoration(labelText: '아티스트 (선택)'),
                ),
                const SizedBox(height: 6),
                const Text(
                  'Drive에는 영상 ID·제목·아티스트만 저장됩니다. '
                  '음악 파일이나 만료되는 스트리밍 URL은 올라가지 않습니다.',
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('취소'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('곡 추가'),
            ),
          ],
        ),
      );
      if (submit != true || !mounted) return;
      OdifySourceRef source;
      try {
        source = OdifyPlaylistTrackLink.asSource(
          link: link.text, title: title.text, artist: artist.text,
        );
      } on FormatException {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('유효한 YouTube 주소 또는 11자리 영상 ID를 입력하세요.')),
        );
        return;
      }
      await _perform(() async {
        final id = OdifySyncClock.newId();
        await _repository!.recordLocalChange(
          op: 'playlist.entry.add',
          payload: {
            'playlistId': playlist.id,
            'entryId': id,
            'positionKey': OdifyPlaylistTrackLink.appendPosition(id),
            'sourceRef': source.toJson(),
          },
        );
        await _refreshLocal();
        _status = '곡을 추가했습니다. 지금 동기화를 눌러 Drive에 반영하세요.';
      });
    } finally {
      link.dispose();
      title.dispose();
      artist.dispose();
    }
  }

  Future<void> _removeTrack(
    OdifySyncPlaylist playlist,
    OdifySyncEntry entry,
  ) async {
    if (_busy || _repository == null) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('곡 제거'),
        content: Text('${entry.source.title}을(를) 이 재생목록에서 제거할까요?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('제거'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    await _perform(() async {
      await _repository!.recordLocalChange(
        op: 'playlist.entry.remove',
        payload: {
          'playlistId': playlist.id,
          'entryId': entry.id,
        },
      );
      await _refreshLocal();
      _status = '곡을 제거했습니다. 동기화하면 다른 기기에도 반영됩니다.';
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
          OdifyGlassSurface(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
            child: const ListTile(
            contentPadding: EdgeInsets.zero,
            leading: Icon(Icons.cloud_outlined),
            title: Text('Google Drive 개인 동기화'),
            subtitle: Text(
              '재생목록 변경사항을 내 Google Drive의 앱 전용 공간에 '
              '안전하게 보관합니다. 음악 파일은 업로드하지 않습니다.',
            ),
            ),
          ),
          const SizedBox(height: 12),
          OdifyGlassSurface(
            padding: const EdgeInsets.all(18),
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
                    Text('이번 화면에서 마지막 동기화: '
                      '${_lastSync!.year}-'
                      '${_lastSync!.month.toString().padLeft(2, '0')}-'
                      '${_lastSync!.day.toString().padLeft(2, '0')} '
                      '${_lastSync!.hour.toString().padLeft(2, '0')}:'
                      '${_lastSync!.minute.toString().padLeft(2, '0')}'),
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
                    TextButton.icon(
                      onPressed: _busy ? null : _connect,
                      icon: const Icon(Icons.verified_user_outlined),
                      label: const Text('Drive 권한 승인·갱신'),
                    ),
                    OutlinedButton(
                      onPressed: _busy ? null : _disconnect,
                      child: const Text('계정 연결 해제'),
                    ),
                  ],
                ],
              ),
          ),
          const SizedBox(height: 18),
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
            ExpansionTile(
              key: ValueKey('odify-sync-playlist:${playlist.id}'),
              leading: const Icon(Icons.queue_music),
              title: Text(playlist.name),
              subtitle: Text('${playlist.orderedEntries.length}곡'),
              trailing: PopupMenuButton<String>(
                tooltip: '재생목록 관리',
                enabled: !_busy,
                onSelected: (value) {
                  if (value == 'rename') {
                    unawaited(_renamePlaylist(playlist));
                  } else if (value == 'delete') {
                    unawaited(_deletePlaylist(playlist));
                  }
                },
                itemBuilder: (_) => const [
                  PopupMenuItem(value: 'rename', child: Text('이름 변경')),
                  PopupMenuItem(value: 'delete', child: Text('삭제')),
                ],
              ),
              children: [
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16, vertical: 8,
                  ),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: OutlinedButton.icon(
                      onPressed: _busy ? null : () => _addTrack(playlist),
                      icon: const Icon(Icons.add_link),
                      label: const Text('YouTube 곡 추가'),
                    ),
                  ),
                ),
                if (playlist.orderedEntries.isEmpty)
                  const ListTile(
                    title: Text('곡이 없습니다. 영상 주소로 곡을 추가하세요.'),
                  ),
                for (final entry in playlist.orderedEntries)
                  ListTile(
                    key: ValueKey('odify-sync-entry:${entry.id}'),
                    leading: const Icon(Icons.music_note_rounded),
                    title: Text(
                      entry.source.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    subtitle: Text(
                      entry.source.artist.isNotEmpty
                          ? entry.source.artist
                          : entry.source.kind == 'youtube'
                              ? 'YouTube'
                              : '기기 내 파일 · 다른 기기에서 재생 불가',
                    ),
                    onTap: _busy || entry.source.kind != 'youtube'
                        ? null
                        : () => _playPlaylist(playlist, entry),
                    trailing: IconButton(
                      tooltip: '곡 제거',
                      onPressed: _busy
                          ? null
                          : () => _removeTrack(playlist, entry),
                      icon: const Icon(Icons.remove_circle_outline),
                    ),
                  ),
              ],
            ),
          const SizedBox(height: 12),
          Text(
            '현재 베타는 재생목록 생성·이름 변경·삭제와 YouTube 곡 추가·제거를 동기화합니다. '
            '기존 기기 재생목록과 좋아요 자동 이관, 곡 순서 재배치는 준비 중입니다. '
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
