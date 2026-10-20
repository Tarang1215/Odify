import 'package:flutter_test/flutter_test.dart';
import 'package:spotiflac_android/services/odify_youtube.dart';

void main() {
  test('official Topic channel uses track title rather than video headline', () {
    final result = OdifyVideo.fromMap(const {
      'id': 'AbCdEfGh_12',
      'title': 'Aimer - Brave Shine (Official Music Video)',
      'channel': 'Aimer - Topic',
      'musicArtist': '',
      'thumbnail': '',
      'isCover': false,
      'duration': 230,
    });
    expect(result.artist, 'Aimer');
    expect(result.title, 'Brave Shine');
    expect(result.originalTitle, contains('Official'));
    expect(result.uploader, 'Aimer - Topic');
  });

  test('explicit artist metadata takes precedence over an unrelated channel', () {
    final result = OdifyVideo.fromMap(const {
      'id': 'AbCdEfGh_12',
      'title': 'YOASOBI - アイドル (Official Music Video)',
      'channel': 'Label Archive',
      'musicArtist': 'YOASOBI',
      'isCover': false,
    });
    expect(result.artist, 'YOASOBI');
    expect(result.title, 'アイドル');
  });

  test('cover attributed to performer, not the song original artist', () {
    final result = OdifyVideo.fromMap(const {
      'id': 'AbCdEfGh_12',
      'title': 'Aimer - Brave Shine (Cover)',
      'channel': 'Singer B',
      'musicArtist': 'Aimer',
      'isCover': true,
    });
    expect(result.artist, 'Singer B');
    expect(result.title, 'Aimer - Brave Shine (Cover)');
  });

  test('arbitrary video channels are never rewritten as official artists', () {
    final result = OdifyVideo.fromMap(const {
      'id': 'AbCdEfGh_12',
      'title': 'Aimer - Brave Shine',
      'channel': 'Random Fan Reupload',
      'musicArtist': '',
      'isCover': false,
    });
    expect(result.artist, 'Random Fan Reupload');
    expect(result.title, 'Aimer - Brave Shine');
  });

  test('missing music metadata is safely preserved', () {
    final result = OdifyVideo.fromMap(const {
      'id': 'AbCdEfGh_12',
      'title': 'Live Concert - 東京',
      'isCover': false,
    });
    expect(result.title, 'Live Concert - 東京');
    expect(result.artist, isEmpty);
  });
}
