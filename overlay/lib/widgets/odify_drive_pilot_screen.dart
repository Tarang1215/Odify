import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:spotiflac_android/providers/music_player_provider.dart';
import 'package:spotiflac_android/services/odify_drive_pilot_store.dart';

/// Deliberately separate from the metadata-only Google Drive OAuth sync.
/// The system picker fetches selected Drive files into an offline test cache.
class OdifyDrivePilotScreen extends ConsumerStatefulWidget {
  const OdifyDrivePilotScreen({super.key});

  @override
  ConsumerState<OdifyDrivePilotScreen> createState() =>
      _OdifyDrivePilotScreenState();
}

class _OdifyDrivePilotScreenState
    extends ConsumerState<OdifyDrivePilotScreen> {
  OdifyDrivePilotStore? _store;
  List<OdifyDrivePilotTrack> _tracks = [];
  bool _busy = false;
  String? _error;
  String? _notice;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final store = await OdifyDrivePilotStore.open();
      final tracks = await store.tracks();
      if (!mounted) return;
      setState(() {
        _store = store;
        _tracks = tracks;
        _error = null;
      });
    } catch (error) {
      if (mounted) setState(() => _error = '라이브러리 열기 실패: $error');
    }
  }

  Future<void> _import() async {
    final store = _store;
    if (store == null || _busy || !Platform.isAndroid) return;
    setState(() {
      _busy = true;
      _error = null;
      _notice = null;
    });
    try {
      final count = await store.importFromPicker();
      final tracks = await store.tracks();
      if (!mounted) return;
      setState(() {
        _tracks = tracks;
        _notice = count == 0
            ? '새로 추가된 곡이 없습니다.'
            : '$count곡을 오프라인 시험 라이브러리에 추가했습니다.';
      });
    } catch (error) {
      if (mounted) setState(() => _error = '음악 가져오기 실패: $error');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _play(int index) async {
    final store = _store;
    if (store == null || index < 0 || index >= _tracks.length) return;
    try {
      await ref.read(musicPlayerControllerProvider).playAll(
        [for (final song in _tracks) song.toPlayable(store.libraryRoot)],
        initialIndex: index,
      );
    } catch (error) {
      if (mounted) setState(() => _error = '재생 실패: $error');
    }
  }

  Future<void> _remove(OdifyDrivePilotTrack track) async {
    final store = _store;
    if (store == null || _busy) return;
    setState(() => _busy = true);
    try {
      await store.remove(track);
      final tracks = await store.tracks();
      if (mounted) setState(() => _tracks = tracks);
    } catch (error) {
      if (mounted) setState(() => _error = '삭제 실패: $error');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Drive 음악 테스트')),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'Google Drive 앱에 로그인한 다음 아래 버튼에서 Drive의 '
              'MP3/M4A 등을 선택하세요. 음악이 Odify에 임시로 아닌 '
              '영구 복사되어 오프라인 재생됩니다. 아직 클라우드 직접 '
              '스트리밍 기능은 아닙니다.',
            ),
            const SizedBox(height: 12),
            FilledButton.icon(
              key: const Key('odify-drive-pilot-import'),
              onPressed: _busy || _store == null || !Platform.isAndroid
                  ? null
                  : _import,
              icon: const Icon(Icons.cloud_download_outlined),
              label: const Text('Google Drive에서 음악 선택'),
            ),
            const SizedBox(height: 8),
            Text('테스트 라이브러리: ${_tracks.length}곡'),
            if (_busy) const LinearProgressIndicator(),
            if (_notice != null) Text(_notice!),
            if (_error != null)
              Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            const Divider(),
            Expanded(
              child: _tracks.isEmpty
                  ? const Center(child: Text(
                      '아직 가져온 음악이 없습니다. Drive에 테스트 음원을 '
                      '올린 후 선택해주세요.',
                    ))
                  : ListView.builder(
                      itemCount: _tracks.length,
                      itemBuilder: (context, index) {
                        final track = _tracks[index];
                        return ListTile(
                          leading: const Icon(Icons.audiotrack_rounded),
                          title: Text(track.title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis),
                          subtitle: Text(
                            track.artist.isEmpty
                                ? track.filename
                                : track.artist,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          onTap: _busy ? null : () => _play(index),
                          trailing: IconButton(
                            tooltip: '테스트 캐시에서 삭제',
                            onPressed: _busy ? null : () => _remove(track),
                            icon: const Icon(Icons.delete_outline),
                          ),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
    );
  }
}
