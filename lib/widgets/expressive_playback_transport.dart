import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:material_3_expressive/material_3_expressive.dart';
import 'package:mornye/theme/app_motion.dart';
import 'package:mornye/theme/material_expressive.dart';
import 'package:mornye/widgets/app_icon.dart';
import 'package:mornye/widgets/app_loading_indicator.dart';

/// Material 3 Expressive previous / play-pause / next row.
///
/// Play-pause rests round while paused and square while playing. Pressing a
/// button widens it and squishes its neighbours, keeping the row width fixed.
/// Callers use the standard transport row when expressive motion is off.
class ExpressivePlaybackTransport extends StatefulWidget {
  const ExpressivePlaybackTransport({
    super.key,
    required this.colorScheme,
    required this.playing,
    required this.loading,
    required this.playTooltip,
    required this.previousTooltip,
    required this.nextTooltip,
    required this.onPlayPause,
    required this.onPrevious,
    required this.onNext,
    this.leading,
    this.trailing,
    this.compact = false,
  });

  final ColorScheme colorScheme;
  final bool playing;
  final bool loading;
  final String playTooltip;
  final String previousTooltip;
  final String nextTooltip;
  final VoidCallback onPlayPause;
  final VoidCallback onPrevious;
  final VoidCallback onNext;

  /// 48dp actions beside the group, such as shuffle and repeat.
  final Widget? leading;
  final Widget? trailing;
  final bool compact;

  @override
  State<ExpressivePlaybackTransport> createState() =>
      _ExpressivePlaybackTransportState();
}

class _ExpressivePlaybackTransportState
    extends State<ExpressivePlaybackTransport> {
  static const _gap = 8.0;
  static const _sideActionWidth = 48.0;
  static const _minTarget = 48.0;
  static const _squishRatio = 0.15;
  static final _pressSpring = SpringCurve(M3EMotion.expressiveSpatialPress);

  int? _pressed;

  void _press(int? index) {
    if (_pressed != index) setState(() => _pressed = index);
  }

  @override
  Widget build(BuildContext context) {
    final compact = widget.compact;
    final height = compact ? 56.0 : 72.0;
    final restingWidths = compact ? [56.0, 80.0, 56.0] : [68.0, 104.0, 68.0];
    return LayoutBuilder(
      builder: (context, constraints) {
        final sideActions =
            (widget.leading == null ? 0 : 1) +
            (widget.trailing == null ? 0 : 1);
        final groupWidth = restingWidths.reduce((a, b) => a + b);
        final available =
            constraints.maxWidth -
            sideActions * (_sideActionWidth + _gap) -
            2 * _gap;
        var widths = [...restingWidths];
        if (constraints.hasBoundedWidth && available < groupWidth) {
          // Narrow phones: previous/next keep a 48dp target and play-pause
          // absorbs the remaining shortfall.
          final side = math.max(
            _minTarget,
            restingWidths.first * available / groupWidth,
          );
          widths = [side, math.max(_minTarget, available - 2 * side), side];
        }
        final pressed = _pressed;
        if (pressed != null) {
          // Neighbours give up exactly what the pressed button gains.
          final growth = widths[pressed] * _squishRatio;
          final neighbours = [
            pressed - 1,
            pressed + 1,
          ].where((index) => index >= 0 && index < widths.length).toList();
          widths[pressed] += growth;
          for (final index in neighbours) {
            widths[index] -= growth / neighbours.length;
          }
        }
        final scheme = widget.colorScheme;
        final playIcon = widget.loading
            ? AppLoadingIndicator(color: scheme.onSurfaceVariant)
            : null;
        return Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            if (widget.leading case final leading?) ...[
              leading,
              const SizedBox(width: _gap),
            ],
            _slot(
              index: 0,
              width: widths[0],
              height: height,
              child: _button(
                tooltip: widget.previousTooltip,
                icon: const AppIcon(Icons.skip_previous),
                iconSize: compact ? 28 : 32,
                round: false,
                background: scheme.secondaryContainer,
                foreground: scheme.onSecondaryContainer,
                onPressed: widget.onPrevious,
              ),
            ),
            const SizedBox(width: _gap),
            _slot(
              index: 1,
              width: widths[1],
              height: height,
              child: _button(
                tooltip: widget.playTooltip,
                icon: playIcon ?? const AppIcon(Icons.play_arrow),
                selectedIcon: playIcon ?? const AppIcon(Icons.pause),
                selected: widget.playing,
                iconSize: compact ? 32 : 40,
                round: true,
                background: scheme.primary,
                foreground: scheme.onPrimary,
                onPressed: widget.loading ? null : widget.onPlayPause,
              ),
            ),
            const SizedBox(width: _gap),
            _slot(
              index: 2,
              width: widths[2],
              height: height,
              child: _button(
                tooltip: widget.nextTooltip,
                icon: const AppIcon(Icons.skip_next),
                iconSize: compact ? 28 : 32,
                round: false,
                background: scheme.secondaryContainer,
                foreground: scheme.onSecondaryContainer,
                onPressed: widget.onNext,
              ),
            ),
            if (widget.trailing case final trailing?) ...[
              const SizedBox(width: _gap),
              trailing,
            ],
          ],
        );
      },
    );
  }

  Widget _slot({
    required int index,
    required double width,
    required double height,
    required Widget child,
  }) => Listener(
    onPointerDown: (_) => _press(index),
    onPointerUp: (_) => _press(null),
    onPointerCancel: (_) => _press(null),
    // One curve for every slot keeps the summed width constant mid-flight.
    child: TweenAnimationBuilder<double>(
      tween: Tween(end: width),
      duration: _pressSpring.duration,
      curve: _pressSpring,
      builder: (context, width, child) =>
          SizedBox(width: math.max(0, width), height: height, child: child),
      child: child,
    ),
  );

  Widget _button({
    required String tooltip,
    required Widget icon,
    Widget? selectedIcon,
    bool? selected,
    required double iconSize,
    required bool round,
    required Color background,
    required Color foreground,
    required VoidCallback? onPressed,
  }) {
    final scheme = Theme.of(context).colorScheme;
    Widget sized(Widget icon) => IconTheme.merge(
      data: IconThemeData(size: iconSize),
      child: icon,
    );
    return Tooltip(
      message: tooltip,
      excludeFromSemantics: true,
      // One labeled node instead of a label plus an unnamed tap target.
      child: MergeSemantics(
        child: MaterialExpressiveScope(
          child: M3EIconButton(
            icon: sized(icon),
            selectedIcon: selectedIcon == null ? null : sized(selectedIcon),
            isSelected: selected,
            semanticLabel: tooltip,
            onPressed: onPressed,
            size: widget.compact ? M3EIconButtonSize.md : M3EIconButtonSize.lg,
            shape: round
                ? M3EIconButtonShapeVariant.round
                : M3EIconButtonShapeVariant.square,
            variant: M3EIconButtonVariant.filled,
            matchParentConstraints: true,
            inflateHitTarget: false,
            decoration: M3EIconButtonDecoration(
              foregroundColor: WidgetStateProperty.resolveWith(
                (states) => states.contains(WidgetState.disabled)
                    ? scheme.onSurface.withValues(alpha: 0.38)
                    : foreground,
              ),
              backgroundColor: WidgetStateProperty.resolveWith(
                (states) => states.contains(WidgetState.disabled)
                    ? scheme.onSurface.withValues(alpha: 0.12)
                    : background,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
