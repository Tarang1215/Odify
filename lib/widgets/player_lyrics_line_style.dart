part of 'player_lyrics.dart';

Iterable<(String, TextStyle, List<LyricWord>, bool)> _lyricSupplements(
  BuildContext context,
  LyricLine line,
) sync* {
  final base = Theme.of(context).textTheme.bodyMedium ?? const TextStyle();
  for (final (text, translation) in [
    (line.romanization, false),
    (line.translation, true),
  ]) {
    if (text == null || text.trim().isEmpty) continue;
    yield (
      text,
      base.copyWith(
        fontSize:
            (context.isMornye
                ? (translation ? 18.0 : 22.0)
                : (translation ? 14.0 : 16.0)) *
            (line.isBackground ? 0.8 : 1),
        height: 1.35,
        fontWeight: context.isMornye
            ? (translation ? FontWeight.w600 : FontWeight.bold)
            : FontWeight.w500,
      ),
      translation ? const <LyricWord>[] : line.romanizationWords,
      translation,
    );
  }
}

TextStyle _mornyeLyricStyle(BuildContext context, {bool background = false}) =>
    (Theme.of(context).textTheme.headlineSmall ?? const TextStyle()).copyWith(
      fontSize: _mornyeLyricFontSize * (background ? 0.68 : 1),
      height: context.tokens.lyricsLineHeight,
      fontWeight: FontWeight.bold,
    );

TextAlign _lyricTextAlign(BuildContext context, LyricLine line) {
  final voice = line.voice;
  if (voice == null) {
    return context.isMornye ? TextAlign.start : TextAlign.center;
  }
  if (voice.isGroup) return TextAlign.left;
  // Provider declarations can be missing or ordered by first appearance.
  // The standard V labels retain their meaning regardless of that order.
  switch (voice.id.toLowerCase()) {
    case 'v1':
    case 'v3':
      return TextAlign.left;
    case 'v2':
      return TextAlign.right;
  }
  return voice.index.isEven ? TextAlign.left : TextAlign.right;
}

Widget _withLyricSupplements(
  BuildContext context,
  LyricLine line,
  Widget primary,
  Color color, {
  required Offset visibility,
  required LyricPronunciationLayout? pronunciationLayout,
  required TextAlign textAlign,
  Widget Function(String, List<LyricWord>, TextStyle)? timedText,
  Widget Function(String, List<LyricWord>, TextStyle)? timedSupplementText,
}) {
  if (line.romanization == null && line.translation == null) return primary;
  final alignment = switch (textAlign) {
    TextAlign.right => Alignment.topRight,
    TextAlign.center => Alignment.topCenter,
    _ => Alignment.topLeft,
  };
  final supplements = _lyricSupplements(context, line).toList();
  Widget withSupplements(Widget primary, {bool aligned = false}) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      primary,
      for (final (text, style, words, translation) in supplements)
        if ((translation || !aligned) &&
            (translation ? visibility.dy : visibility.dx) > 0)
          LyricSupplementTransition(
            alignment: alignment,
            visibility: translation ? visibility.dy : visibility.dx,
            child: Padding(
              padding: const EdgeInsets.only(top: 6),
              child: words.isNotEmpty && timedSupplementText != null
                  ? timedSupplementText(text, words, style)
                  : Text(
                      text,
                      textAlign: textAlign,
                      style: style.copyWith(
                        color: color.withValues(alpha: color.a * 0.8),
                      ),
                    ),
            ),
          ),
    ],
  );
  if (!context.isMornye ||
      line.romanizationWords.isEmpty ||
      line.romanization?.trim().isNotEmpty != true) {
    return withSupplements(primary);
  }
  final primaryStyle = _mornyeLyricStyle(
    context,
    background: line.isBackground,
  );
  final pronunciationStyle = supplements
      .firstWhere((supplement) => !supplement.$4)
      .$2;
  final layout = pronunciationLayout;
  if (layout == null) return withSupplements(primary);
  return withSupplements(
    AlignedLyricPronunciation(
      layout: layout,
      visibility: visibility.dx,
      primaryStyle: primaryStyle,
      pronunciationStyle: pronunciationStyle,
      textAlign: textAlign,
      pronunciationBuilder: timedSupplementText,
      textBuilder:
          timedText ??
          (text, words, style) => Text(
            text,
            textAlign: textAlign,
            style: style.copyWith(color: color),
          ),
    ),
    aligned: true,
  );
}
