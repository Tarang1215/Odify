import 'package:flutter/material.dart';
import 'package:spotiflac_android/services/odify_home_feed.dart';
import 'package:spotiflac_android/widgets/odify_device_library_card.dart';
import 'package:spotiflac_android/widgets/odify_glass.dart';
import 'package:spotiflac_android/widgets/odify_youtube_explore.dart';
import 'package:spotiflac_android/widgets/odify_home_feed.dart';

/// A single source of truth for Odify's Home and extension-free Search route.
///
/// The parent Home already owns the one scrolling viewport. No nested
/// vertical scrolls, extra BackdropFilters or animation tickers are created.
class OdifyQuickAccess extends StatelessWidget {
  const OdifyQuickAccess({super.key, this.showDeviceLibrary = true});

  final bool showDeviceLibrary;

  void _open(
    BuildContext context, {
    String category = 'For You',
    String query = '',
  }) {
    Navigator.of(context, rootNavigator: true).push(
      MaterialPageRoute<void>(
        builder: (_) => OdifyYouTubeExplorer(
          initialCategory: category,
          initialQuery: query,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 28),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Semantics(
            header: true,
            child: Row(
              children: [
                Container(
                  height: 44,
                  width: 44,
                  decoration: BoxDecoration(
                    color: colors.primary.withValues(alpha: 0.13),
                    border: Border.all(
                      color: colors.primary.withValues(alpha: 0.22),
                    ),
                    borderRadius: BorderRadius.circular(15),
                  ),
                  child: Icon(
                    Icons.graphic_eq_rounded,
                    size: 26,
                    color: colors.primary,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Odify',
                        style: text.titleLarge?.copyWith(
                          fontWeight: FontWeight.w900,
                          letterSpacing: -0.8,
                        )),
                      Text(
                        showDeviceLibrary
                            ? 'Music, made personal.'
                            : '좋아하는 곡과 새로운 음악을 발견하세요',
                        style: text.bodySmall?.copyWith(
                          color: colors.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                IconButton.filledTonal(
                  key: const Key('odify-discover-search-shortcut'),
                  tooltip: '음악 검색',
                  onPressed: () => _open(context, category: 'All'),
                  icon: const Icon(Icons.search_rounded),
                ),
              ],
            ),
          ),
          const SizedBox(height: 18),
          if (showDeviceLibrary) ...[
            SizedBox(
              height: 44,
              child: ListView(
                scrollDirection: Axis.horizontal,
                children: [
                  OdifyGlassPill(
                    key: const Key('odify-shortcut-all'),
                    label: '모두',
                    selected: true,
                    onTap: () => _open(context, category: 'All',
                      query: OdifyHomeFeedService.trendingQuery('KR')),
                  ),
                  const SizedBox(width: 8),
                  OdifyGlassPill(
                    label: '나를 위한 추천',
                    icon: Icons.auto_awesome_rounded,
                    onTap: () => _open(context),
                  ),
                  const SizedBox(width: 8),
                  OdifyGlassPill(
                    label: 'Trending',
                    icon: Icons.trending_up_rounded,
                    onTap: () => _open(
                      context, category: 'All',
                      query: OdifyHomeFeedService.trendingQuery('KR'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  OdifyGlassPill(
                    label: 'Covers',
                    icon: Icons.mic_none_rounded,
                    onTap: () => _open(context,
                      category: 'Covers', query: 'popular vocal cover'),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 20),
            const OdifyHomeFeed(),
            const SizedBox(height: 24),
          ] else ...[
            Text(
              '음악 · 커버곡 검색은 확장 기능 설치 없이 이용합니다.',
              style: text.bodySmall?.copyWith(
                color: colors.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 16),
          ],
          const OdifyYouTubeExploreCard(),
          if (showDeviceLibrary) ...[
            const SizedBox(height: 18),
            const OdifyDeviceLibraryCard(),
          ],
        ],
      ),
    );
  }
}
