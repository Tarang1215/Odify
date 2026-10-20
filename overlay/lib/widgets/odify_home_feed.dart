import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:spotiflac_android/providers/music_player_provider.dart';
import 'package:spotiflac_android/services/odify_home_feed.dart';
import 'package:spotiflac_android/services/odify_youtube.dart';
import 'package:spotiflac_android/services/odify_likes.dart';
import 'package:spotiflac_android/widgets/odify_youtube_explore.dart';
import 'package:spotiflac_android/widgets/odify_glass.dart';

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
  Timer? _personalRefresh;
  bool _previewRequested = false;
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
    // Never boot yt-dlp's embedded Python interpreter just by opening Home.
    // On some Android 16 devices a native extractor crash terminates the
    // entire app (Dart try/catch cannot intercept a native SIGSEGV/OOM).
    // Music discovery stays available via explicit user interaction.
  }

  @override
  void dispose() {
    _personalRefresh?.cancel();
    super.dispose();
  }

  void _requestPreviews() {
    if (_previewRequested) return;
    setState(() => _previewRequested = true);
    unawaited(_loadTrending());
    unawaited(_loadPersonal());
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


  /// Artwork is decoded at device-scale, not at full YouTube poster
  /// resolution. The small fallback is intentionally static (no shimmer).
  Widget _artwork(
    OdifyVideo video, {
    required double size,
    double radius = 14,
  }) {
    final fallback = Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      color: Theme.of(context).colorScheme.onSurface.withValues(alpha: 0.05),
      child: Icon(
        Icons.music_note_rounded,
        color: Theme.of(context).colorScheme.onSurfaceVariant,
      ),
    );
    return RepaintBoundary(
      child: ClipRRect(
        borderRadius: BorderRadius.circular(radius),
        child: video.thumbnail.isEmpty
            ? fallback
            : Image.network(
                video.thumbnail,
                width: size,
                height: size,
                cacheWidth: (size * MediaQuery.devicePixelRatioOf(context))
                    .round()
                    .clamp(96, 512),
                fit: BoxFit.cover,
                gaplessPlayback: true,
                errorBuilder: (_, _, _) => fallback,
              ),
      ),
    );
  }

  bool _isLiked(OdifyVideo video) => _liked.contains(
    OdifyLikeStore.trackKey(video.artist, video.title),
  );

  Widget _heart(OdifyVideo video) => IconButton(
    tooltip: _isLiked(video) ? '좋아요 취소' : '좋아요',
    onPressed: () => _toggleLike(video),
    visualDensity: VisualDensity.compact,
    icon: Icon(
      _isLiked(video)
          ? Icons.favorite_rounded
          : Icons.favorite_border_rounded,
      color: _isLiked(video) ? Theme.of(context).colorScheme.primary : null,
      size: 21,
    ),
  );

  Widget _featuredMix(List<OdifyVideo> songs) {
    final colors = Theme.of(context).colorScheme;
    final track = songs.first;
    return RepaintBoundary(
      child: OdifyGlassSurface(
        radius: 26,
        accent: colors.primary,
        onTap: _playing ? null : () => _play(songs, 0),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'YOUR DAILY MIX',
                      style: Theme.of(context).textTheme.labelSmall?.copyWith(
                        color: colors.primary,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 1.2,
                      ),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      '오늘의 추천',
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                        fontWeight: FontWeight.w800,
                        letterSpacing: -0.6,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      track.title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodyMedium,
                    ),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        Icon(Icons.play_circle_filled_rounded,
                          color: colors.primary, size: 29),
                        const SizedBox(width: 6),
                        Text('바로 듣기',
                          style: Theme.of(context).textTheme.labelMedium),
                      ],
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              _artwork(track, size: 120, radius: 20),
            ],
          ),
        ),
      ),
    );
  }

  Widget _personalSection() {
    final colors = Theme.of(context).colorScheme;
    final tracks = _personal?.videos ?? const <OdifyVideo>[];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        OdifySectionTitle(
          title: '나를 위한 추천',
          subtitle: _personal?.description ??
              '좋아요와 청취 기록으로 나만의 음악을 찾아드립니다',
          onMore: () => _openDiscover(),
        ),
        const SizedBox(height: 12),
        if (_personalLoading && _personal == null)
          const OdifyGlassSurface(
            radius: 26,
            padding: EdgeInsets.all(24),
            child: SizedBox(
              height: 112,
              child: Center(
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
          )
        else if (_personalError != null && _personal == null)
          OdifyGlassSurface(
            child: ListTile(
              title: const Text('추천곡을 불러오지 못했습니다'),
              subtitle: const Text('잠시 후 다시 시도해주세요'),
              trailing: TextButton(
                onPressed: () => _loadPersonal(refresh: true),
                child: const Text('재시도'),
              ),
            ),
          )
        else if (tracks.isNotEmpty) ...[
          _featuredMix(tracks),
          const SizedBox(height: 14),
          SizedBox(
            height: 244,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.only(bottom: 4),
              itemCount: tracks.length.clamp(0, 6),
              separatorBuilder: (_, _) => const SizedBox(width: 12),
              itemBuilder: (context, index) {
                final video = tracks[index];
                return SizedBox(
                  width: 154,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      OdifyGlassSurface(
                        radius: 18,
                        onTap: _playing ? null : () => _play(tracks, index),
                        child: _artwork(video, size: 154, radius: 18),
                      ),
                      const SizedBox(height: 6),
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              video.title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: Theme.of(context).textTheme.labelLarge
                                  ?.copyWith(fontWeight: FontWeight.w700),
                            ),
                          ),
                          SizedBox(
                            width: 36,
                            height: 36,
                            child: _heart(video),
                          ),
                        ],
                      ),
                      Text(
                        video.artist,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: colors.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                );
              },
            ),
          ),
        ] else
          OdifyGlassSurface(
            padding: const EdgeInsets.all(20),
            child: Row(
              children: [
                Icon(Icons.auto_awesome, color: colors.primary),
                const SizedBox(width: 12),
                const Expanded(
                  child: Text('음악을 듣고 좋아요를 눌러 나만의 추천을 만들어보세요.'),
                ),
                IconButton(
                  tooltip: '추천 새로고침',
                  onPressed: () => _loadPersonal(refresh: true),
                  icon: const Icon(Icons.refresh_rounded),
                ),
              ],
            ),
          ),
      ],
    );
  }

  Widget _trendingSection() {
    final colors = Theme.of(context).colorScheme;
    final tracks = _trending?.videos ?? const <OdifyVideo>[];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        OdifySectionTitle(
          title: '오늘의 음악 탐색',
          subtitle: _trending?.description ??
              '한국 · 일본 · 글로벌 인기 음악을 찾아보세요',
          onMore: () => _openDiscover(
            category: 'All',
            query: OdifyHomeFeedService.trendingQuery(_market),
          ),
        ),
        const SizedBox(height: 12),
        SizedBox(
          height: 44,
          child: ListView(
            scrollDirection: Axis.horizontal,
            children: [
              for (final market in OdifyHomeFeedService.markets) ...[
                OdifyGlassPill(
                  label: OdifyHomeFeedService.marketLabel(market),
                  selected: _market == market,
                  onTap: () {
                    if (_market == market) return;
                    setState(() {
                      _market = market;
                      _trending = null;
                      _trendingError = null;
                    });
                    unawaited(_loadTrending());
                  },
                ),
                const SizedBox(width: 8),
              ],
            ],
          ),
        ),
        const SizedBox(height: 14),
        if (_trendingLoading && _trending == null)
          const OdifyGlassSurface(
            padding: EdgeInsets.all(22),
            child: SizedBox(
              height: 64,
              child: Center(
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
          )
        else if (_trendingError != null && _trending == null)
          OdifyGlassSurface(
            child: ListTile(
              title: const Text('곡을 불러오지 못했습니다.'),
              subtitle: const Text('인터넷 연결을 확인해 주세요.'),
              trailing: TextButton(
                onPressed: () => _loadTrending(refresh: true),
                child: const Text('재시도'),
              ),
            ),
          )
        else if (tracks.isEmpty && _trending != null)
          const OdifyGlassSurface(
            padding: EdgeInsets.all(18),
            child: Text('표시할 곡이 없습니다. 다시 탐색해보세요.'),
          )
        else if (tracks.isNotEmpty)
          OdifyGlassSurface(
            radius: 22,
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: Column(
              children: [
                for (var index = 0;
                    index < tracks.length && index < 4;
                    index++) ...[
                  if (index > 0)
                    Divider(
                      height: 1,
                      indent: 76,
                      color: colors.onSurface.withValues(alpha: 0.065),
                    ),
                  _songTile(tracks, index),
                ],
              ],
            ),
          ),
      ],
    );
  }

  Widget _songTile(List<OdifyVideo> tracks, int index) {
    final video = tracks[index];
    return ListTile(
      key: ValueKey('odify-song:${video.id}'),
      contentPadding: const EdgeInsets.only(left: 10, right: 8),
      minVerticalPadding: 8,
      onTap: _playing ? null : () => _play(tracks, index),
      leading: _artwork(video, size: 55, radius: 12),
      title: Text(
        video.title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
          fontWeight: FontWeight.w700,
        ),
      ),
      subtitle: Text(
        video.artist,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _heart(video),
          const Icon(Icons.play_arrow_rounded, size: 24),
          const SizedBox(width: 2),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    if (!_previewRequested) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const OdifySectionTitle(
            title: '나를 위한 추천',
            subtitle: '좋아요와 청취 기록 기반의 개인 맞춤 음악',
          ),
          const SizedBox(height: 12),
          OdifyGlassSurface(
            radius: 24,
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  '홈을 빠르고 안정적으로 열기 위해 음악 추천은 요청할 때 불러옵니다.',
                ),
                const SizedBox(height: 12),
                FilledButton.tonalIcon(
                  key: const Key('odify-home-load-preview'),
                  onPressed: _requestPreviews,
                  icon: const Icon(Icons.playlist_play_rounded),
                  label: const Text('추천 음악 불러오기'),
                ),
              ],
            ),
          ),
          const SizedBox(height: 26),
          OdifySectionTitle(
            title: '오늘의 음악 탐색',
            subtitle: '한국 · 일본 · 글로벌 인기 음악을 검색해보세요',
            onMore: () => _openDiscover(
              category: 'All',
              query: OdifyHomeFeedService.trendingQuery(_market),
            ),
          ),
        ],
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _personalSection(),
        const SizedBox(height: 26),
        _trendingSection(),
      ],
    );
  }
}
