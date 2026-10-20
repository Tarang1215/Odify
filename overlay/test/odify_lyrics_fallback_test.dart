import 'package:flutter_test/flutter_test.dart';
import 'package:mornye/services/odify_lyrics_fallback.dart';

void main() {
  test('cleans official video suffix and artist topic attribution', () {
    expect(OdifyLyricsFallback.cleanTitle('Song (Official MV)'), 'Song');
    expect(OdifyLyricsFallback.cleanTitle('Song [Lyrics]'), 'Song');
    expect(OdifyLyricsFallback.cleanArtist('Artist - Topic'), 'Artist');
  });

  test('matches artists and titles across typography but not other performers', () {
    const candidate = <String, dynamic>{
      'trackName': 'LULU!',
      'artistName': 'Mrs. GREEN APPLE',
      'syncedLyrics': '[00:01.00]sample',
    };
    expect(OdifyLyricsFallback.matches(
      candidate, 'lulu', 'Mrs. Green Apple',
    ), isTrue);
    expect(OdifyLyricsFallback.matches(candidate, 'lulu', 'Cover singer'), isFalse);
    expect(OdifyLyricsFallback.matches(candidate, 'Other song', 'Mrs. Green Apple'), isFalse);
  });
}
