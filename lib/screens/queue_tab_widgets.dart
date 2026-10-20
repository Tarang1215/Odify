part of 'queue_tab.dart';

class _QueueItemSliverRow extends ConsumerWidget {
  final String itemId;
  final ColorScheme colorScheme;
  final Widget Function(BuildContext, DownloadItem, ColorScheme) itemBuilder;

  const _QueueItemSliverRow({
    super.key,
    required this.itemId,
    required this.colorScheme,
    required this.itemBuilder,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final item = ref.watch(
      downloadQueueLookupProvider.select((lookup) => lookup.byItemId[itemId]),
    );
    if (item == null) {
      return const SizedBox.shrink();
    }

    return RepaintBoundary(child: itemBuilder(context, item, colorScheme));
  }
}

enum _CollectionEntryType { wishlist, loved, favoriteArtists, playlist }

class _CollectionEntry {
  final _CollectionEntryType type;
  final int playlistIndex;

  const _CollectionEntry._(this.type, [this.playlistIndex = -1]);

  static const wishlist = _CollectionEntry._(_CollectionEntryType.wishlist);
  static const loved = _CollectionEntry._(_CollectionEntryType.loved);
  static const favoriteArtists = _CollectionEntry._(
    _CollectionEntryType.favoriteArtists,
  );
  static _CollectionEntry playlist(int index) =>
      _CollectionEntry._(_CollectionEntryType.playlist, index);
}

class _FilterChip extends ConsumerWidget {
  final String label;

  /// Leading glyph; it fills in while the tab is selected.
  final IconData icon;

  /// Items in the tab; null hides the count.
  final int? count;
  final bool isSelected;
  final VoidCallback onTap;

  const _FilterChip({
    required this.label,
    required this.icon,
    required this.count,
    required this.isSelected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colorScheme = Theme.of(context).colorScheme;
    final glyph = AppIcon.adaptive(
      icon,
      size: 18,
      fill: isSelected ? 1 : 0,
      color: isSelected ? colorScheme.onSurface : colorScheme.onSurfaceVariant,
    );

    final content = Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        glyph,
        const SizedBox(width: 8),
        Text(
          label,
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
            color: isSelected
                ? colorScheme.onSurface
                : colorScheme.onSurfaceVariant,
            fontWeight: isSelected ? FontWeight.w600 : FontWeight.w500,
          ),
        ),
        if (count != null) ...[
          const SizedBox(width: 6),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: isSelected
                  ? colorScheme.primary.withValues(alpha: 0.2)
                  : colorScheme.outline.withValues(alpha: 0.2),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Text(
              count.toString(),
              style: TextStyle(
                fontSize: 11,
                color: isSelected
                    ? colorScheme.onPrimaryContainer
                    : colorScheme.onSurfaceVariant,
                fontWeight: FontWeight.w500,
              ),
            ),
          ),
        ],
      ],
    );

    return Semantics(
      button: true,
      selected: isSelected,
      label: count == null ? label : '$label, $count',
      excludeSemantics: true,
      onTap: onTap,
      child: MornyeGlass(
        radius: 22,
        blurEnabled: ref.watch(mornyeBlurEnabledProvider),
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(22),
            child: AnimatedContainer(
              duration: MediaQuery.disableAnimationsOf(context)
                  ? Duration.zero
                  : context.tokens.motionFast,
              constraints: const BoxConstraints(minHeight: 44),
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              decoration: BoxDecoration(
                color: isSelected
                    ? colorScheme.primary.withValues(alpha: 0.10)
                    : Colors.transparent,
                borderRadius: BorderRadius.circular(22),
              ),
              child: content,
            ),
          ),
        ),
      ),
    );
  }
}

/// Library page switcher on Material: primary tabs, since the pages are
/// views of one collection rather than filters. Counts trail each label.
class _LibraryPageTabs extends StatefulWidget {
  const _LibraryPageTabs({
    required this.tabs,
    required this.selectedIndex,
    required this.onSelected,
  });

  /// Label and item count per page; a null count is hidden.
  final List<(String, int?)> tabs;
  final int selectedIndex;
  final ValueChanged<int> onSelected;

  @override
  State<_LibraryPageTabs> createState() => _LibraryPageTabsState();
}

class _LibraryPageTabsState extends State<_LibraryPageTabs>
    with SingleTickerProviderStateMixin {
  late final TabController _controller = TabController(
    length: widget.tabs.length,
    initialIndex: widget.selectedIndex,
    vsync: this,
  );

  @override
  void didUpdateWidget(covariant _LibraryPageTabs oldWidget) {
    super.didUpdateWidget(oldWidget);
    // Swiping the pages moves the indicator along with them.
    if (widget.selectedIndex != _controller.index) {
      _controller.animateTo(widget.selectedIndex);
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Inherits the tab's label color, so the count follows the selection.
    const countStyle = TextStyle(fontWeight: FontWeight.w400);
    return TabBar(
      controller: _controller,
      isScrollable: true,
      tabAlignment: TabAlignment.start,
      onTap: widget.onSelected,
      tabs: [
        for (final (label, count) in widget.tabs)
          Tab(
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(label),
                if (count != null) ...[
                  const SizedBox(width: 6),
                  Text('$count', style: countStyle),
                ],
              ],
            ),
          ),
      ],
    );
  }
}
