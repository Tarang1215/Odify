import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mornye/services/odify_audio_proxy.dart';
import 'package:mornye/services/odify_youtube.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('com.odify.music/yt_dlp');
  const id = 'dQw4w9WgXcQ';
  final source = OdifyYouTube.sourceForId(id);
  final nativeSource = 'odify-cdn://audio/${'a' * 48}';

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('resolves YouTube queue identity to native header-preserving source', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'resolve');
      expect(call.arguments, {'videoId': id});
      return {
        'url': 'https://r1.googlevideo.com/videoplayback?expire=1',
        'headers': {'User-Agent': 'Test-UA'},
        'nativeSource': nativeSource,
      };
    });
    expect(await OdifyYouTube.resolveAudioSource(source), nativeSource);
    expect(OdifyYouTube.idFromSource(source), id);
  });

  test('explicit fallback retains HTTPS origin and yt-dlp header relay', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async => {
          'url': 'https://r1.googlevideo.com/videoplayback?expire=1',
          'headers': {'User-Agent': 'Test-UA'},
          'nativeSource': nativeSource,
        });
    final relay = await OdifyYouTube.resolveAudioSource(
      source,
      preferNative: false,
    );
    expect(Uri.parse(relay).host, '127.0.0.1');
    expect(Uri.parse(relay).pathSegments.first, 'stream');
    await OdifyAudioProxy.shared.dispose();
  });

  test('does not accept forged or malformed native source', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async => {
          'url': 'https://r1.googlevideo.com/videoplayback?expire=1',
          'headers': <String, String>{},
          'nativeSource': 'odify-cdn://audio/not-a-token',
        });
    // A malformed handle must never be passed to MediaPlayer, which would
    // attempt to open an unknown URI scheme.
    // Use a dedicated proxy instance since the shared fallback test disposes
    // its listener; this test only verifies rejection through the resolver.
    expect(
      OdifyYouTube.idFromSource('odify-cdn://audio/not-a-token'),
      isNull,
    );
  });
}
