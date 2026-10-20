import 'package:flutter_test/flutter_test.dart';
import 'package:mornye/services/odify_sync/playlist_track_link.dart';

void main() {
  test('accepts trusted YouTube video links and preserves ID case', () {
    for (final candidate in [
      'aBcDeFgHiJ1',
      'https://www.youtube.com/watch?v=aBcDeFgHiJ1&t=42',
      'https://music.youtube.com/watch?v=aBcDeFgHiJ1',
      'https://youtu.be/aBcDeFgHiJ1',
      'https://m.youtube.com/shorts/aBcDeFgHiJ1',
      'https://youtube.com/live/aBcDeFgHiJ1',
    ]) {
      expect(OdifyPlaylistTrackLink.parseYoutubeId(candidate), 'aBcDeFgHiJ1');
    }
  });

  test('rejects lookalike, credentials and unrelated URLs', () {
    for (final candidate in [
      'https://youtube.com.evil.test/watch?v=aBcDeFgHiJ1',
      'https://evil.test/watch?v=aBcDeFgHiJ1',
      'https://user:pass@youtube.com/watch?v=aBcDeFgHiJ1',
      'http://youtube.com/watch?v=aBcDeFgHiJ1',
      'content://media/external/audio/1',
      'file:///home/private.mp3',
      'https://youtu.be/not-valid',
      'https://youtube.com/watch?v=aBcDeFgHiJ1&v=AAAAAAAAAAA',
      'https://youtube.com/playlist?list=PL123',
    ]) {
      expect(OdifyPlaylistTrackLink.parseYoutubeId(candidate), isNull);
    }
  });

  test('portable source omits expiring stream and filesystem fields', () {
    final source = OdifyPlaylistTrackLink.asSource(
      link: 'https://youtu.be/aBcDeFgHiJ1',
      title: '  My Cover  ',
      artist: '  Performer  ',
    );
    expect(source.toJson(), {
      'kind': 'youtube',
      'title': 'My Cover',
      'artist': 'Performer',
      'videoId': 'aBcDeFgHiJ1',
    });
    expect(() => OdifyPlaylistTrackLink.asSource(
      link: '/sdcard/Music/private.mp3', title: 'Private',
    ), throwsFormatException);
  });

  test('playlist append key is stable width and time ordered', () {
    final first = OdifyPlaylistTrackLink.appendPosition(
      'one', now: DateTime.utc(2026, 10, 21, 10),
    );
    final second = OdifyPlaylistTrackLink.appendPosition(
      'two', now: DateTime.utc(2026, 10, 21, 11),
    );
    expect(first.compareTo(second), lessThan(0));
    expect(first.length, lessThan(128));
  });
}
