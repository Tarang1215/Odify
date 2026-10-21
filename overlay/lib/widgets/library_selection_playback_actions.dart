import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:spotiflac_android/l10n/l10n.dart';
import 'package:spotiflac_android/models/unified_library_item.dart';
import 'package:spotiflac_android/providers/music_player_provider.dart';
import 'package:spotiflac_android/widgets/playlist_picker_sheet.dart';
import 'package:spotiflac_android/widgets/selection_action_button.dart';

/// Collection actions stay separate from actions that modify audio files.
class LibrarySelectionPlaybackActions extends ConsumerWidget {
  const LibrarySelectionPlaybackActions({
    super.key,
    required this.items,
    required this.onClose,
  });

  final List<UnifiedLibraryItem> items;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = Theme.of(context).colorScheme;
    final actions = <Widget>[
      SelectionActionButton(
        icon: Icons.playlist_add,
        label: context.l10n.collectionAddToPlaylist,
        colorScheme: colors,
        onPressed: items.isEmpty
            ? null
            : () async {
                final tracks = items.map((item) => item.toTrack()).toList();
                // Do not close the selection overlay BEFORE the new sheet
                // mounts. On Samsung, pop/push in the same frame can dispose
                // the captured ref/context and silently drop the tap.
                final navigatorContext = Navigator.of(
                  context,
                  rootNavigator: true,
                ).context;
                try {
                  await showAddTracksToPlaylistSheet(
                    navigatorContext, ref, tracks,
                  );
                  if (context.mounted) onClose();
                } catch (_) {
                  if (!context.mounted) return;
                  ScaffoldMessenger.of(navigatorContext).showSnackBar(
                    const SnackBar(
                      content: Text('재생목록을 열지 못했습니다. 다시 시도해주세요.'),
                    ),
                  );
                }
              },
      ),
      for (final next in [true, false])
        SelectionActionButton(
          icon: next ? Icons.playlist_play : Icons.queue_music,
          label: next
              ? context.l10n.trackPlayNext
              : context.l10n.trackAddToQueue,
          colorScheme: colors,
          onPressed: items.isEmpty
              ? null
              : () async {
                  final controller = ref.read(musicPlayerControllerProvider);
                  await controller.enqueueLibraryItems(items, playNext: next);
                  if (context.mounted) onClose();
                },
        ),
    ];
    return LayoutBuilder(
      builder: (context, constraints) => Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final action in actions)
              SizedBox(width: (constraints.maxWidth - 8) / 2, child: action),
          ],
        ),
      ),
    );
  }
}
