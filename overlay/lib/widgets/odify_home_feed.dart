import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:spotiflac_android/providers/music_player_provider.dart';
import 'package:spotiflac_android/services/odify_home_feed.dart';
import 'package:spotiflac_android/services/odify_youtube.dart';
import 'package:spotiflac_android/services/odify_likes.dart';
import 'package:spotiflac_android/widgets/odify_youtube_explore.dart';

/// Small, immediately understandable Odify home sections. The first paint
/// requires zero YouTube calls. Searches start only after initial rendering;
/// only short visible previews are built, not a heavy nested result list.
class OdifyHomeFeed extends ConsumerStatefulWidget {
  const OdifyHomeFeed({super.key, this.service});

  /// Inject mock network service in Flutter tests without native yt-dlp.
  final OdifyHomeFeedService? service;

  @override
  ConsumerState<OdifyHomeFeed> createState() => _OdifyHomeFeedState();
}

class _OdifyHomeFeedState extends ConsumerState<OdifyHomeFeed> {
  late final OdifyHomeFeedService _feed =
      widget.service ?? OdifyHomeFeedService.shared;
  Timer? _initialLoad;
  Timer? _personalRefresh;
  Set<String> _liked = <String>{};
  String _market = 'KR';
  OdifyHomeFeedResult? _trending;
  OdifyHomeFeedResult? _personal;
  Object? _trendingError;
  Object? _personalError;
  bool _trendingLoading = false;
  bool _personalLoading = false;
  bool _playing = false;
  int _trendingVersion = 0;
  int _personalVersion = 0;

  @override
  void initState() {
    super.initState();
    unawaited(_readLikes());
    // Flutter paints home/navigation before decoding thumbnails or using the
    // native yt-dlp search interpreter. Cancel on unmount (Home <-> Search).
    _initialLoad = Timer(const Duration(milliseconds: 900), () {
      if (!mounted) return;
      unawaited(_loadTrending());
      unawaited(_loadPersonal());
    });
  }

  @override
  void dispose() {
    _initialLoad?.cancel();
    _personalRefresh?.cancel();
    super.dispose();
  }

  Future<void> _loadTrending({bool refresh = false}) async {
    final version = ++_trendingVersion;
    setState(() {
      _trendingLoading = true;
      _trendingError = null;
    });
    try {
      final result = await _feed.trending(market: _market, refresh: refresh);
      if (!mounted || version != _trendingVersion) return;
      setState(() => _trending = result);
    } catch (error) {
      if (!mounted || version != _trendingVersion) return;
      setState(() => _trendingError = error);
    } finally {
      if (mounted && version == _trendingVersion) {
        setState(() => _trendingLoading = false);
      }
    }
  }

  Future<void> _loadPersonal({bool refresh = false}) async {
    final version = ++_personalVersion;
    setState(() {
      _personalLoading = true;
      _personalError = null;
    });
    try {
      final result = await _feed.forYou(refresh: refresh);
      if (!mounted || version != _personalVersion) return;
      setState(() => _personal = result);
    } catch (error) {
      if (!mounted || version != _personalVersion) return;
      setState(() => _personalError = error);
    } finally {
      if (mounted && version == _personalVersion) {
        setState(() => _personalLoading = false);
      }
    }
  }

  Future<void> _readLikes() async {
    try {
      final likes = await OdifyLikeStore.read();
      if (mounted) setState(() => _liked = likes);
    } catch (_) {
      // A missing preferences store should not prevent Home from rendering.
    }
  }

  Future<void> _toggleLike(OdifyVideo video) async {
    final key = OdifyLikeStore.trackKey(video.artist, video.title);
    try {
      final likes = await OdifyLikeStore.setLiked(
        video.artist,
        video.title,
        liked: !_liked.contains(key),
      );
      if (!mounted) return;
      setState(() => _liked = likes);
      // Batch repeated hearts: personalization must not launch yt-dlp after
      // every rapid user tap, but is refreshed from the saved preferences.
      _personalRefresh?.cancel();
      _personalRefresh = Timer(const Duration(seconds: 3), () {
        if (mounted) unawaited(_loadPersonal(refresh: true));
      });
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('좋아요 저장에 실패했습니다.')),
      );
    }
  }

  Future<void> _play(List<OdifyVideo> tracks, int index) async {
    if (_playing || index < 0 || index >= tracks.length) return;
    setState(() => _playing = true);
    try {
      await ref.read(musicPlayerControllerProvider).playAll(
        [for (final video in tracks) OdifyYouTube.playable(video)],
        initialIndex: index,
      );
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('재생할 수 없습니다. 곡의 접근 가능 여부를 확인해주세요.')),
      );
    } finally {
      if (mounted) setState(() => _playing = false);
    }
  }

  void _openDiscover({String? category, String? query}) {
    Navigator.of(context, rootNavigator: true).push(
      MaterialPageRoute<void>(
        builder: (_) => OdifyYouTubeExplorer(
          initialCategory: category ?? 'For You',
          initialQuery: query ?? '',
        ),
      ),
    );
  }

  Widget _section({
    required String title,
    required IconData icon,
    required bool loading,
    required OdifyHomeFeedResult? result,
    required Object? error,
    required VoidCallback refresh,
    required VoidCallback expand,
  }) {
    final songs = result?.videos ?? const <OdifyVideo>[];
    final colors = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(children: [
          Icon(icon, size: 19, color: colors.primary),
          const SizedBox(width: 8),
          Expanded(
            child: Text(title, style: Theme.of(context).textTheme.titleMedium),
          ),
          IconButton(
            tooltip: '$title 새로고침',
            onPressed: loading ? null : refresh,
            icon: const Icon(Icons.refresh_rounded, size: 20),
          ),
          TextButton(
            onPressed: expand,
            child: const Text('더 보기'),
          ),
        ]),
        if (result != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 5),
            child: Text(
              result.description,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: colors.onSurfaceVariant,
              ),
            ),
          ),
        if (loading && result == null)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: LinearProgressIndicator(minHeight: 2),
          ),
        if (error != null && result == null)
          ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: const Text('곡을 불러오지 못했습니다. 인터넷 연결을 확인해 주세요.'),
            trailing: TextButton(onPressed: refresh, child: const Text('재시도')),
          )
        else if (result != null && songs.isEmpty)
          ListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: const Text('표시할 곡이 없습니다.'),
            trailing: TextButton(onPressed: refresh, child: const Text('새로고침')),
          )
        else for (var index = 0; index < songs.length && index < 4; index++)
          _songTile(songs, index),
      ],
    );
  }

  Widget _songTile(List<OdifyVideo> songs, int index) {
    final video = songs[index];
    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      onTap: _playing ? null : () => _play(songs, index),
      leading: ClipRRect(
        borderRadius: BorderRadius.circular(6),
        child: video.thumbnail.isEmpty
            ? const SizedBox(
                width: 54, height: 44, child: Icon(Icons.music_note_outlined),
              )
            : Image.network(
                video.thumbnail,
                width: 54,
                height: 44,
                cacheWidth: 120,
                fit: BoxFit.cover,
                errorBuilder: (_, _, _) => const SizedBox(
                  width: 54, height: 44, child: Icon(Icons.music_note_outlined),
                ),
              ),
      ),
      title: Text(
        video.title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text(
        video.artist,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            tooltip: _liked.contains(
              OdifyLikeStore.trackKey(video.artist, video.title),
            ) ? '좋아요 취소' : '좋아요',
            onPressed: () => _toggleLike(video),
            icon: Icon(
              _liked.contains(OdifyLikeStore.trackKey(
                video.artist, video.title,
              )) ? Icons.favorite_rounded : Icons.favorite_border_rounded,
              size: 20,
            ),
          ),
          const Icon(Icons.play_arrow_rounded),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _section(
          title: '오늘의 음악 탐색',
          icon: Icons.local_fire_department_outlined,
          loading: _trendingLoading,
          result: _trending,
          error: _trendingError,
          refresh: () => _loadTrending(refresh: true),
          expand: () => _openDiscover(
            category: 'All',
            query: OdifyHomeFeedService.trendingQuery(_market),
          ),
        ),
        SizedBox(
          height: 38,
          child: ListView(
            scrollDirection: Axis.horizontal,
            children: [
              for (final market in OdifyHomeFeedService.markets)
                Padding(
                  padding: const EdgeInsets.only(right: 7),
                  child: ChoiceChip(
                    label: Text(OdifyHomeFeedService.marketLabel(market)),
                    selected: _market == market,
                    visualDensity: VisualDensity.compact,
                    onSelected: (_) {
                      if (_market == market) return;
                      setState(() {
                        _market = market;
                        _trending = null;
                        _trendingError = null;
                      });
                      unawaited(_loadTrending());
                    },
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(height: 13),
        _section(
          title: '나를 위한 추천',
          icon: Icons.auto_awesome_outlined,
          loading: _personalLoading,
          result: _personal,
          error: _personalError,
          refresh: () => _loadPersonal(refresh: true),
          expand: () => _openDiscover(),
        ),
      ],
    );
  }
}
