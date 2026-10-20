import 'package:flutter/material.dart';

/// Odify's inexpensive "glass" material for SCROLLING content.
///
/// Intentionally does not use BackdropFilter or saveLayer: the root Mornye
/// navigation/mini-player already performs grouped backdrop filtering.
/// Hundreds of blurred album tiles would cause avoidable GPU overdraw.
class OdifyGlassSurface extends StatelessWidget {
  const OdifyGlassSurface({
    super.key,
    required this.child,
    this.radius = 24,
    this.padding = EdgeInsets.zero,
    this.accent,
    this.onTap,
  });

  final Widget child;
  final double radius;
  final EdgeInsetsGeometry padding;
  final Color? accent;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    final glow = accent ?? colors.primary;
    final card = DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(radius),
        // A static two-tone surface reads as glass even when the underlying
        // Home background is uniform. Costs just a single decorated layer.
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: dark
              ? [
                  Color.alphaBlend(
                    glow.withValues(alpha: 0.11),
                    const Color(0xff242629),
                  ),
                  const Color(0xff17191c),
                ]
              : [
                  Color.alphaBlend(
                    glow.withValues(alpha: 0.065),
                    const Color(0xfffcfcfd),
                  ),
                  const Color(0xffeff1f4),
                ],
        ),
        border: Border.all(
          color: dark
              ? Colors.white.withValues(alpha: 0.12)
              : colors.onSurface.withValues(alpha: 0.08),
          width: 1,
        ),
      ),
      child: Padding(padding: padding, child: child),
    );
    if (onTap == null) return card;
    return Material(
      color: Colors.transparent,
      borderRadius: BorderRadius.circular(radius),
      child: InkWell(
        borderRadius: BorderRadius.circular(radius),
        onTap: onTap,
        child: card,
      ),
    );
  }
}

/// Restrained pill used by the Home's discovery shortcuts. No blur pass.
class OdifyGlassPill extends StatelessWidget {
  const OdifyGlassPill({
    super.key,
    required this.label,
    required this.onTap,
    this.selected = false,
    this.icon,
  });

  final String label;
  final VoidCallback onTap;
  final bool selected;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final foreground = selected ? colors.onPrimary : colors.onSurface;
    return Semantics(
      button: true,
      selected: selected,
      child: Material(
        color: selected
            ? colors.primary
            : colors.onSurface.withValues(alpha: 0.065),
        borderRadius: BorderRadius.circular(99),
        child: InkWell(
          borderRadius: BorderRadius.circular(99),
          onTap: onTap,
          child: Container(
            constraints: const BoxConstraints(minHeight: 42),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(99),
              border: Border.all(
                color: selected
                    ? Colors.transparent
                    : colors.onSurface.withValues(alpha: 0.075),
              ),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (icon != null) ...[
                  Icon(icon, size: 17, color: foreground),
                  const SizedBox(width: 6),
                ],
                Text(
                  label,
                  style: Theme.of(context).textTheme.labelMedium?.copyWith(
                    color: foreground,
                    fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Consistent tiny heading above art-heavy music sections.
class OdifySectionTitle extends StatelessWidget {
  const OdifySectionTitle({
    super.key,
    required this.title,
    this.subtitle,
    this.onMore,
    this.moreLabel = '더 보기',
  });

  final String title;
  final String? subtitle;
  final VoidCallback? onMore;
  final String moreLabel;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: Theme.of(context).textTheme.titleLarge?.copyWith(
                  fontWeight: FontWeight.w800,
                  letterSpacing: -0.55,
                ),
              ),
              if (subtitle != null && subtitle!.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Text(
                    subtitle!,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: colors.onSurfaceVariant,
                    ),
                  ),
                ),
            ],
          ),
        ),
        if (onMore != null)
          TextButton(
            onPressed: onMore,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(moreLabel),
                const SizedBox(width: 2),
                const Icon(Icons.chevron_right_rounded, size: 16),
              ],
            ),
          ),
      ],
    );
  }
}
