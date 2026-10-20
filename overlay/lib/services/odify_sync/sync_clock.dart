import 'dart:convert';
import 'dart:math';

/// Hybrid logical timestamp: UTC milliseconds + 4-digit logical counter.
/// Generated before saving an operation; persists across app restarts.
class OdifySyncClock {
  static String next(String? last, {DateTime? now}) {
    final wallMs = (now ?? DateTime.now()).toUtc().millisecondsSinceEpoch;
    var millis = wallMs;
    var logical = 0;
    if (last != null) {
      final pieces = last.split('#');
      if (pieces.length != 2) {
        throw const FormatException('Invalid previous sync clock');
      }
      final priorMs = DateTime.parse(pieces.first).millisecondsSinceEpoch;
      final priorLogical = int.parse(pieces.last);
      if (priorMs >= millis) {
        millis = priorMs;
        logical = priorLogical + 1;
      }
    }
    if (logical > 9999) {
      millis++;
      logical = 0;
    }
    return '${DateTime.fromMillisecondsSinceEpoch(millis, isUtc: true).toIso8601String()}#${logical.toString().padLeft(4, '0')}';
  }

  /// Random installation/event ID. Does not use Android identifiers or IMEI.
  static String newId() {
    final random = Random.secure();
    final bytes = List<int>.generate(16, (_) => random.nextInt(256));
    return base64Url.encode(bytes).replaceAll('=', '');
  }
}
