import 'package:flutter/material.dart';
import 'package:spotiflac_android/widgets/odify_device_library_card.dart';
import 'package:spotiflac_android/widgets/odify_youtube_explore.dart';

/// Always-visible Odify entry point, independent of SpotiFLAC's optional
/// search/home-feed extensions. In the Search tab we omit the device card
/// to avoid mounting two concurrent MediaStore auto-sync controllers.
class OdifyQuickAccess extends StatelessWidget {
  const OdifyQuickAccess({super.key, this.showDeviceLibrary = true});

  final bool showDeviceLibrary;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Icon(Icons.graphic_eq_rounded, color: colors.primary, size: 27),
              const SizedBox(width: 10),
              const Expanded(
                child: Text(
                  'Odify',
                  style: TextStyle(fontWeight: FontWeight.w800, fontSize: 24),
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
                decoration: BoxDecoration(
                  color: colors.primaryContainer,
                  borderRadius: BorderRadius.circular(99),
                ),
                child: Text(
                  'Discover',
                  style: TextStyle(
                    color: colors.onPrimaryContainer,
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            '음악 · 커버곡 검색은 확장 기능 설치 없이 이용합니다.',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: colors.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 12),
          const OdifyYouTubeExploreCard(),
          if (showDeviceLibrary) ...[
            const SizedBox(height: 8),
            const OdifyDeviceLibraryCard(),
          ],
        ],
      ),
    );
  }
}
