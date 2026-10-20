import 'package:flutter/material.dart';
import 'package:material_3_expressive/material_3_expressive.dart';
import 'package:mornye/theme/material_expressive.dart';

/// Pull-to-refresh with the expressive shape-morphing indicator on Material.
/// Mornye and reduced motion keep Flutter's [RefreshIndicator].
class AppRefreshIndicator extends StatelessWidget {
  const AppRefreshIndicator({
    super.key,
    required this.onRefresh,
    required this.child,
    this.notificationPredicate = defaultScrollNotificationPredicate,
  });

  final RefreshCallback onRefresh;
  final ScrollNotificationPredicate notificationPredicate;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    if (!materialExpressiveEnabled(context)) {
      return RefreshIndicator(
        onRefresh: onRefresh,
        notificationPredicate: notificationPredicate,
        child: child,
      );
    }
    return MaterialExpressiveScope(
      child: M3ERefreshIndicator.contained(
        onRefresh: onRefresh,
        notificationPredicate: notificationPredicate,
        child: child,
      ),
    );
  }
}
