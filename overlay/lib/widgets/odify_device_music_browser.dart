import 'dart:io';

import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:spotiflac_android/providers/music_player_provider.dart';
import 'package:spotiflac_android/services/odify_device_catalog.dart';
import 'package:spotiflac_android/services/odify_likes.dart';

/// Browse Android's actual shared audio catalogue and play inside Odify.
class OdifyDeviceMusicBrowser extends ConsumerStatefulWidget {
  const OdifyDeviceMusicBrowser({super.key});

  @override
  ConsumerState<OdifyDeviceMusicBrowser> createState() =>
      _OdifyDeviceMusicBrowserState();
}

class _OdifyDeviceMusicBrowserState
    extends ConsumerState<OdifyDeviceMusicBrowser> {
  static const _pageSize = 150;
  final _items = <OdifyDeviceTrack>[];
  final _controller = TextEditingController();
  Set<String> _likes = {};
  bool _loading = false;
  bool _canLoadMore = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _prepare();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _prepare() async {
    if (!Platform.isAndroid) return;
    try {
      final android = await DeviceInfoPlugin().androidInfo;
      final permission = android.version.sdkInt >= 33
          ? Permission.audio
          : Permission.storage;
      final status = await permission.request();
      if (!status.isGranted) {
        if (mounted) setState(() => _error = '음악 접근 권한이 필요합니다.');
        return;
      }
      _likes = await OdifyLikeStore.read();
      await _loadMore();
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    }
  }

  Future<void> _loadMore() async {
    if (_loading || !_canLoadMore) return;
    setState(() { _loading = true; _error = null; });
    try {
      final result = await OdifyDeviceCatalog.page(
        offset: _items.length,
        limit: _pageSize,
      );
      if (!mounted) return;
      setState(() {
        _items.addAll(result);
        _canLoadMore = result.length == _pageSize;
      });
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _toggleLike(OdifyDeviceTrack track) async {
    final key = OdifyLikeStore.trackKey(track.artist, track.title);
    final liked = !_likes.contains(key);
    try {
      final updated = await OdifyLikeStore.setLiked(
        track.artist, track.title, liked: liked,
      );
      if (mounted) setState(() => _likes = updated);
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('즐겨찾기 저장 실패: $error')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final query = _controller.text.trim().toLowerCase();
    final filtered = [
      for (final track in _items)
        if (query.isEmpty ||
            '${track.title} ${track.artist} ${track.album}'
                .toLowerCase().contains(query)) track,
    ];
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
        child: Column(children: [
          Row(children: [
            const Expanded(child: Text('내 휴대폰 음악',
                style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold))),
            IconButton(
              tooltip: '다시 검색',
              onPressed: _loading ? null : () {
                setState(() { _items.clear(); _canLoadMore = true; });
                _loadMore();
              },
              icon: const Icon(Icons.refresh),
            ),
          ]),
          TextField(
            controller: _controller,
            onChanged: (_) => setState(() {}),
            decoration: const InputDecoration(
              prefixIcon: Icon(Icons.search),
              hintText: '곡, 가수, 앨범 검색',
            ),
          ),
          const SizedBox(height: 8),
          if (_error != null)
            ListTile(
              leading: const Icon(Icons.warning_amber_outlined),
              title: Text(_error!),
              trailing: TextButton(onPressed: _prepare, child: const Text('재시도')),
            ),
          Expanded(child: ListView.builder(
            itemCount: filtered.length + (_canLoadMore ? 1 : 0),
            itemBuilder: (context, index) {
              if (index == filtered.length) {
                return Center(child: TextButton(
                  onPressed: _loading ? null : _loadMore,
                  child: Text(_loading ? '불러오는 중...' : '더 불러오기'),
                ));
              }
              final track = filtered[index];
              final liked = _likes.contains(
                OdifyLikeStore.trackKey(track.artist, track.title));
              return ListTile(
                leading: const Icon(Icons.music_note_outlined),
                title: Text(track.title, maxLines: 1, overflow: TextOverflow.ellipsis),
                subtitle: Text(track.artist, maxLines: 1, overflow: TextOverflow.ellipsis),
                onTap: () => ref.read(musicPlayerControllerProvider)
                    .playSingle(track.toPlayable()),
                trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                  IconButton(
                    tooltip: liked ? '좋아요 취소' : '좋아요',
                    onPressed: () => _toggleLike(track),
                    icon: Icon(liked ? Icons.favorite : Icons.favorite_border),
                  ),
                  IconButton(
                    tooltip: '대기열에 추가',
                    onPressed: () => ref.read(musicPlayerControllerProvider)
                        .addToQueue(track.toPlayable()),
                    icon: const Icon(Icons.playlist_add),
                  ),
                ]),
              );
            },
          )),
        ]),
      ),
    );
  }
}
