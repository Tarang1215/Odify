part of 'files_settings_page.dart';

class _FolderOption extends StatelessWidget {
  final String title;
  final String subtitle;
  final String example;
  final bool isSelected;
  final VoidCallback onTap;
  const _FolderOption({
    required this.title,
    required this.subtitle,
    required this.example,
    required this.isSelected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return AppSheetOption(
      contentPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 4),
      title: Text(title),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(subtitle),
          const SizedBox(height: 4),
          Text(
            example,
            style: TextStyle(
              fontFamily: 'monospace',
              fontSize: 11,
              color: colorScheme.primary,
            ),
          ),
        ],
      ),
      selected: isSelected,
      trailing: isSelected
          ? AppIcon(Icons.check_circle, color: colorScheme.primary)
          : AppIcon(Icons.circle_outlined, color: colorScheme.outline),
      onTap: onTap,
    );
  }
}

/// Bottom sheet for editing a filename format. Owns its controller and disposes
/// it in [dispose] to avoid use-after-dispose during the close animation.
class _FilenameFormatEditorSheet extends StatefulWidget {
  final String initialText;
  final void Function(String) onSave;
  final ScrollController scrollController;

  const _FilenameFormatEditorSheet({
    required this.initialText,
    required this.onSave,
    required this.scrollController,
  });

  @override
  State<_FilenameFormatEditorSheet> createState() =>
      _FilenameFormatEditorSheetState();
}

class _FilenameFormatEditorSheetState
    extends State<_FilenameFormatEditorSheet> {
  static const _basicTags = [
    '{artist}',
    '{title}',
    '{album}',
    '{track}',
    '{year}',
    '{date}',
    '{disc}',
    '{playlist_position}',
  ];
  static const _advancedTags = [
    '{isrc}',
    '{provider}',
    '{provider_id}',
    '{track_raw}',
    '{track:02}',
    '{track:1}',
    '{playlist_position_raw}',
    '{playlist_position:02}',
    '{date:%Y}',
    '{date:%Y-%m-%d}',
    '{disc_raw}',
    '{disc:02}',
  ];

  late final TextEditingController _controller;
  late bool _showAdvancedTags;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.initialText);
    _showAdvancedTags = RegExp(
      r'\{(?:isrc|provider|platform|provider_id|id|track_raw|disc_raw|track:\d+|disc:\d+|date:[^}]+)\}',
      caseSensitive: false,
    ).hasMatch(widget.initialText);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _insertTag(String tag) {
    final text = _controller.text;
    final selection = _controller.selection;
    final start = selection.start >= 0 ? selection.start : text.length;
    final end = selection.end >= 0 ? selection.end : text.length;
    String insertion = tag;
    if (start > 0) {
      final before = text.substring(0, start);
      if (!before.trim().endsWith('-')) {
        insertion = ' - $tag';
      } else if (before.trim().endsWith('-') && !before.endsWith(' ')) {
        insertion = ' $tag';
      }
    }
    final newText = text.replaceRange(start, end, insertion);
    _controller.value = TextEditingValue(
      text: newText,
      selection: TextSelection.collapsed(offset: start + insertion.length),
    );
  }

  Widget _tagChip(ColorScheme colorScheme, String tag) {
    if (context.isMornye) {
      return AppChoiceChip(
        label: Text(tag),
        selected: false,
        onSelected: (_) => _insertTag(tag),
      );
    }
    if (materialExpressiveEnabled(context)) {
      // Inserting a tag is an action, not a selection: an assist chip.
      return MaterialExpressiveScope(
        child: M3EChip(
          label: tag,
          type: M3EChipType.assist,
          onPressed: () => _insertTag(tag),
        ),
      );
    }
    return ActionChip(
      label: Text(tag),
      onPressed: () => _insertTag(tag),
      backgroundColor: colorScheme.surfaceContainerHighest.withValues(
        alpha: 0.5,
      ),
      side: BorderSide.none,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      labelStyle: TextStyle(
        color: colorScheme.onSurface,
        fontWeight: FontWeight.w500,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return SingleChildScrollView(
      controller: widget.scrollController,
      padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          AppTextField(
            controller: _controller,
            hint: '{artist} - {title}',
            decoration: InputDecoration(
              hintText: '{artist} - {title}',
              filled: true,
              fillColor: colorScheme.surfaceContainerHighest.withValues(
                alpha: 0.3,
              ),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(16),
                borderSide: BorderSide.none,
              ),
            ),
            autofocus: true,
          ),
          const SizedBox(height: 24),
          Text(
            context.l10n.downloadFilenameInsertTag,
            style: Theme.of(
              context,
            ).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: _basicTags
                .map((tag) => _tagChip(colorScheme, tag))
                .toList(),
          ),
          const SizedBox(height: 12),
          AppSwitchListTile(
            value: _showAdvancedTags,
            onChanged: (value) => setState(() => _showAdvancedTags = value),
            contentPadding: EdgeInsets.zero,
            title: Text(context.l10n.filenameShowAdvancedTags),
            subtitle: Text(context.l10n.filenameShowAdvancedTagsDescription),
          ),
          if (_showAdvancedTags) ...[
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: _advancedTags
                  .map((tag) => _tagChip(colorScheme, tag))
                  .toList(),
            ),
          ],
          const SizedBox(height: 32),
          Row(
            children: [
              Expanded(
                child: AppDialogAction(
                  onPressed: () => Navigator.pop(context),
                  style: TextButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(16),
                    ),
                  ),
                  child: Text(context.l10n.dialogCancel),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                flex: 2,
                child: AppDialogAction(
                  filled: true,
                  onPressed: () {
                    widget.onSave(_controller.text);
                    Navigator.pop(context);
                  },
                  style: FilledButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(16),
                    ),
                  ),
                  child: Text(context.l10n.dialogSave),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
        ],
      ),
    );
  }
}
