import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show Clipboard, ClipboardData;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:spotiflac_android/providers/music_player_provider.dart';
import 'package:spotiflac_android/providers/local_library_provider.dart';
import 'package:spotiflac_android/services/odify_youtube.dart';
import 'package:spotiflac_android/services/odify_discovery.dart';
import 'package:spotiflac_android/services/odify_home_feed.dart';
import 'package:spotiflac_android/services/odify_likes.dart';
import 'package:spotiflac_android/widgets/odify_glass.dart';

/// An Odify entry point in the existing SpotiFLAC Home, rather than a browser.
class OdifyYouTubeExploreCard extends StatelessWidget {
  const OdifyYouTubeExploreCard({super.key});

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return OdifyGlassSurface(
      radius: 23,
      accent: colors.primary,
      onTap: () => Navigator.of(context, rootNavigator: true).push(
        MaterialPageRoute<void>(builder: (_) => const OdifyYouTubeExplorer()),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 2),
        leading: Container(
          width: 48,
          height: 48,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: colors.primary.withValues(alpha: 0.12),
            borderRadius: BorderRadius.circular(16),
          ),
          child: Icon(Icons.headphones_rounded, color: colors.primary),
        ),
        title: const Text(
          '음악 찾기 · Discover',
          style: TextStyle(fontWeight: FontWeight.w800),
        ),
        subtitle: const Text('눌러서 곡 검색 · 취향 추천 · 커버 · 라이브 보기'),
        trailing: Icon(Icons.north_east_rounded, color: colors.primary),
      ),
    );
  }
}

class OdifyYouTubeExplorer extends ConsumerStatefulWidget {
  const OdifyYouTubeExplorer({
    super.key,
    this.initialCategory = 'For You',
    this.initialQuery = '',
  });

  final String initialCategory;
  final String initialQuery;

  @override
  ConsumerState<OdifyYouTubeExplorer> createState() => _OdifyYouTubeExplorerState();
}

class _OdifyYouTubeExplorerState extends ConsumerState<OdifyYouTubeExplorer> {
  final _searchController = TextEditingController();
  final _results = <OdifyVideo>[];
  String _category = 'For You';
  bool _usingStarter = false;
  final _reasons = <String, String>{};
  Set<String> _liked = <String>{};
  String? _busyVideo;
  String? _error;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _category = widget.initialCategory;
    _searchController.text = widget.initialQuery;
    OdifyLikeStore.read().then((likes) {
      if (mounted) setState(() => _liked = likes);
    }).catchError((Object _) { /* Music playback must still work. */ });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (_category == 'For You') {
        unawaited(_discover());
      } else if (_searchController.text.trim().isNotEmpty) {
        unawaited(_search());
      }
    });
  }

  Future<void> _discover({bool refresh = false}) async {
    if (_loading) return;
    setState(() { _loading = true; _error = null; _category = 'For You'; });
    try {
      final recommended = await OdifyDiscover.recommendations(refresh: refresh);
      final starter = recommended.isEmpty
          ? await OdifyHomeFeedService.shared.trending()
          : null;
      if (!mounted) return;
      setState(() {
        _results..clear()..addAll(
          starter?.videos ?? recommended.map((item) => item.video),
        );
        _usingStarter = starter != null;
        _reasons
          ..clear()
          ..addEntries(recommended.map((item) =>
            MapEntry(item.video.id, item.reason)));
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final input = _searchController.text.trim();
    if (input.isEmpty || _loading) return;
    setState(() { _loading = true; _error = null; _category = _category == 'For You' ? 'All' : _category; });
    try {
      final isPlaylist = input.startsWith('https://') && input.contains('list=');
      final query = _category == 'Covers'
          ? '$input cover 歌ってみた 커버'
          : _category == 'Live' ? '$input live performance' : input;
      final result = isPlaylist
          ? await OdifyYouTube.playlist(input)
          : await OdifyYouTube.search(query);
      if (!mounted) return;
      setState(() {
        _results..clear()..addAll(result);
        _usingStarter = false;
        _reasons.clear();
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _runStreamDiagnostic() async {
    if (_results.isEmpty || _busyVideo != null || _loading) return;
    final id = _results.first.id;
    setState(() => _busyVideo = id);
    late final String report;
    try {
      report = await OdifyYouTube.diagnoseVideo(id);
    } catch (_) {
      report = '진단 실패: 네이티브 플러그인을 확인해 주세요.';
    } finally {
      if (mounted) setState(() => _busyVideo = null);
    }
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('스트리밍 연결 진단'),
        content: SingleChildScrollView(child: SelectableText(report)),
        actions: [
          TextButton(
            onPressed: () => Clipboard.setData(ClipboardData(text: report)),
            child: const Text('진단 결과 복사'),
          ),
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('닫기'),
          ),
        ],
      ),
    );
  }
  Future<void> _toggleLike(OdifyVideo video) async {
    final key = OdifyLikeStore.trackKey(video.artist, video.title);
    final shouldLike = !_liked.contains(key);
    try {
      final next = await OdifyLikeStore.setLiked(
        video.artist, video.title, liked: shouldLike,
      );
      if (mounted) setState(() => _liked = next);
    } catch (error) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('좋아요 저장에 실패했습니다: $error')));
    }
  }

  Future<void> _play(OdifyVideo video) async {
    if (_busyVideo != null) return;
    setState(() => _busyVideo = video.id);
    try {
      // A single-item queue disables Next/Previous. Keep the current
      // Discover/search result ordering as the session queue instead.
      // Each stream URL is resolved just-in-time from its stable video ID.
      final index = _results.indexWhere((item) => item.id == video.id);
      if (index < 0) return;
      await ref.read(musicPlayerControllerProvider).playAll(
        [for (final item in _results) OdifyYouTube.playable(item)],
        initialIndex: index,
      );
    } catch (error) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('재생에 실패했습니다: $error')));
    } finally {
      if (mounted) setState(() => _busyVideo = null);
    }
  }

  Future<void> _download(OdifyVideo video) async {
    if (_busyVideo != null) return;
    setState(() => _busyVideo = video.id);
    try {
      await OdifyYouTube.downloadMp3(video);
      if (!mounted) return;
      await ref.read(localLibraryProvider.notifier).reloadFromStorage();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('${video.title} · MP3가 라이브러리에 추가되었습니다.')));
    } catch (error) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('MP3 저장에 실패했습니다: $error')));
    } finally {
      if (mounted) setState(() => _busyVideo = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Odify Discover'),
        actions: [
          IconButton(
            tooltip: '스트리밍 지연/추출 진단',
            icon: const Icon(Icons.monitor_heart_outlined),
            onPressed: _results.isEmpty || _busyVideo != null || _loading
                ? null : _runStreamDiagnostic,
          ),
        ],
      ),
      body: SafeArea(child: Column(children: [
        const Padding(
          padding: EdgeInsets.fromLTRB(16, 8, 16, 6),
          child: Text(
            '상단에 가수·곡명 입력 → 검색 · Covers는 커버곡 · Live는 라이브 · For You는 취향 추천',
            style: TextStyle(fontSize: 12),
          ),
        ),
        Padding(padding: const EdgeInsets.fromLTRB(16, 10, 16, 2), child: TextField(
          controller: _searchController,
          textInputAction: TextInputAction.search,
          onSubmitted: (_) => _search(),
          decoration: InputDecoration(
            hintText: '곡·가수·커버곡 또는 공개 플레이리스트 URL',
            prefixIcon: const Icon(Icons.search),
            suffixIcon: IconButton(
              tooltip: '검색',
              onPressed: _loading ? null : _search,
              icon: const Icon(Icons.arrow_forward_rounded),
            ),
            border: const OutlineInputBorder(),
          ),
        )),
        SizedBox(height: 50, child: ListView(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          scrollDirection: Axis.horizontal,
          children: [for (final name in const ['For You','All','Covers','Live'])
            Padding(padding: const EdgeInsets.only(right: 8), child: ChoiceChip(
              label: Text(name),
              selected: _category == name,
              onSelected: (_) {
                if (_loading) return;
                if (name == 'For You') {
                  _searchController.clear();
                  _discover();
                } else {
                  setState(() {
                    _category = name;
                    _results.clear();
                    _reasons.clear();
                    _error = null;
                  });
                  if (_searchController.text.trim().isNotEmpty) _search();
                }
              },
            )),
          ],
        )),
        if (_usingStarter && _category == 'For You')
          const Padding(
            padding: EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: Text('아직 개인 취향 자료가 부족해 음악 검색 결과를 먼저 보여드립니다.'),
          ),
        if (_category == 'For You') Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
          child: Row(children: [
            const Icon(Icons.auto_awesome, size: 18),
            const SizedBox(width: 8),
            const Expanded(child: Text('좋아요와 최근 60일 청취 기록 기반 추천',
              style: TextStyle(fontSize: 13))),
            IconButton(
              tooltip: '추천 새로고침',
              onPressed: _loading ? null : () => _discover(refresh: true),
              icon: const Icon(Icons.refresh),
            ),
          ]),
        ),
        if (_loading) const LinearProgressIndicator(),
        if (_error != null) Padding(
          padding: const EdgeInsets.all(16),
          child: Text(_error!, style: TextStyle(color: colors.error)),
        ),
        if (!_loading && _error == null && _results.isEmpty)
          Expanded(child: Center(child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text(_category == 'For You'
              ? '좋아요와 청취 기록이 쌓이면 좋아할 만한 곡과 커버를 추천합니다.\n먼저 음악을 듣고 좋아요를 눌러주세요.'
              : '원곡, 가수, 커버곡을 검색해보세요.',
              textAlign: TextAlign.center),
          )))
        else Expanded(child: ListView.builder(
          itemCount: _results.length,
          itemBuilder: (context, index) {
            final song = _results[index];
            final running = _busyVideo == song.id;
            return ListTile(
              contentPadding: const EdgeInsets.fromLTRB(16, 4, 8, 4),
              leading: ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: Image.network(song.thumbnail, width: 64, height: 52,
                  cacheWidth: 128, fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => const SizedBox(
                    width: 64, child: Icon(Icons.music_note))),
              ),
              title: Text(song.title, maxLines: 2, overflow: TextOverflow.ellipsis),
              subtitle: Text(_reasons.containsKey(song.id)
                ? '${song.artist} · ${_reasons[song.id]}'
                : '${song.artist}${song.isCover ? ' · Cover' : ''}',
                maxLines: 1, overflow: TextOverflow.ellipsis),
              onTap: running ? null : () => _play(song),
              trailing: running
                ? const SizedBox(width: 48, height: 24, child: Center(
                    child: CircularProgressIndicator(strokeWidth: 2)))
                : SizedBox(width: 144, child: Row(children: [
                    IconButton(tooltip: 'Odify에서 재생',
                      icon: const Icon(Icons.play_circle), onPressed: _busyVideo == null ? () => _play(song) : null),
                    IconButton(tooltip: 'MP3로 저장',
                      icon: const Icon(Icons.download_rounded), onPressed: _busyVideo == null ? () => _download(song) : null),
                    IconButton(
                      tooltip: '좋아요',
                      icon: Icon(_liked.contains(OdifyLikeStore.trackKey(
                        song.artist, song.title))
                          ? Icons.favorite_rounded : Icons.favorite_border_rounded),
                      onPressed: () => _toggleLike(song),
                    ),
                  ])),
            );
          },
        )),
      ])),
    );
  }
}
