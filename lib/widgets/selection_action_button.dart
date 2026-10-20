import 'package:flutter/material.dart';
import 'package:mornye/theme/app_tokens.dart';
import 'package:mornye/theme/mornye_theme.dart';
import 'package:mornye/widgets/app_action_button.dart';
import 'package:mornye/widgets/expressive_button.dart';

/// Responsive rows shared by selection-mode action buttons.
class SelectionActionGrid extends StatelessWidget {
  const SelectionActionGrid({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        const spacing = 8.0;
        final columns = (constraints.maxWidth / 320).floor().clamp(2, 4);
        final itemWidth =
            (constraints.maxWidth - spacing * (columns - 1)) / columns;
        return Wrap(
          spacing: spacing,
          runSpacing: spacing,
          children: [
            for (final child in children)
              SizedBox(width: itemWidth, child: child),
          ],
        );
      },
    );
  }
}

/// Icon+label pill used in selection-mode bottom bars, with the active theme's
/// disabled tones and room for a two-line label.
class SelectionActionButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final VoidCallback? onPressed;
  final ColorScheme colorScheme;

  const SelectionActionButton({
    super.key,
    required this.icon,
    required this.label,
    required this.onPressed,
    required this.colorScheme,
  });

  @override
  Widget build(BuildContext context) {
    if (context.isMornye) {
      return AppActionButton(
        outlined: true,
        tonal: true,
        onPressed: onPressed,
        icon: Icon(icon),
        label: Text(label, maxLines: 2, overflow: TextOverflow.ellipsis),
        fontSize: 15,
      );
    }
    return Semantics(
      button: true,
      enabled: onPressed != null,
      label: label,
      onTap: onPressed,
      excludeSemantics: true,
      child: ExpressiveButton(
        tonal: true,
        onPressed: onPressed,
        icon: Icon(icon, size: 18),
        style: FilledButton.styleFrom(
          backgroundColor: colorScheme.secondaryContainer,
          foregroundColor: colorScheme.onSecondaryContainer,
          minimumSize: Size(48, context.tokens.minTouchTarget),
          padding: const EdgeInsets.all(12),
          textStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
        ),
        child: Text(label, maxLines: 2, overflow: TextOverflow.ellipsis),
      ),
    );
  }
}
