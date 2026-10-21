import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mornye/services/odify_audio_proxy.dart';
import 'package:mornye/services/odify_youtube.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('com.odify.music/yt_dlp');
  const id = 'dQw4w9WgXcQ';
  final source = OdifyYouTube.sourceForId(id);
  final nativeSource = 'odify-cdn://audio/${List.filled(48, 'a').join()}';

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

  test('diagnostics expose only stable timing and failure categories', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'diagnose');
          expect(call.arguments, {'videoId': id});
          return {
            'ytDlpInitialized': true,
            'playableCdnProbe': true,
            'client': 'android',
            'extractMs': 2450,
            'cdnProbeMs': 95,
            'audioCodec': 'mp4a.40.2',
            'audioContainer': 'm4a',
            'androidPrepare': 'MEDIA_ERROR',
            'androidErrorWhat': 1,
            'androidErrorExtra': -1005,
            'attempts': <String>[],
          };
        });
    final report = await OdifyYouTube.diagnoseVideo(id);
    expect(report, contains('extractMs: 2450'));
    expect(report, contains('cdnProbeMs: 95'));
    expect(report, contains('client: android'));
    expect(report, contains('audioCodec: mp4a.40.2'));
    expect(report, contains('audioContainer: m4a'));
    expect(report, contains('androidPrepare: MEDIA_ERROR'));
    expect(report, contains('androidErrorExtra: -1005'));
    expect(report, isNot(contains('googlevideo.com')));
    expect(report, isNot(contains(id)));
  });

  test('diagnostic plugin failure returns only controlled error code', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          throw PlatformException(
            code: 'odify_yt_dlp_JS_CHALLENGE',
            message: 'sensitive URL must be discarded',
          );
        });
    final report = await OdifyYouTube.diagnoseVideo(id);
    expect(report, contains('JS_CHALLENGE'));
    expect(report, isNot(contains('sensitive URL')));
  });

  test('prefetch and invalidate refer only to stable video ID', () async {
    final methods = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          methods.add(call.method);
          expect(call.arguments, {'videoId': id});
          return true;
        });
    await OdifyYouTube.prefetchAudioSource(source);
    await OdifyYouTube.invalidateAudioSource(source);
    expect(methods, ['prefetch', 'expire']);
    expect(OdifyYouTube.idFromSource(source), id);
  });

  test('Android open failure refreshes once and uses ranged fallback', () async {
    final methods = <String>[];
    final prepared = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      methods.add(call.method);
      if (call.method == 'expire') return null;
      return {
        'url': 'https://r1.googlevideo.com/videoplayback?fresh=1',
        'headers': {'User-Agent': 'Test-UA'},
        'nativeSource': nativeSource,
      };
    });
    await OdifyYouTube.prepareAudioSource(
      source: source,
      resolved: nativeSource,
      isCurrent: () => true,
      prepare: (path) async {
        prepared.add(path);
        if (prepared.length == 1) {
          throw PlatformException(code: 'AndroidAudioError');
        }
      },
    );
    expect(methods, ['expire', 'resolve']);
    expect(prepared.length, 2);
    expect(Uri.parse(prepared.last).host, '127.0.0.1');
    await OdifyAudioProxy.shared.dispose();
  });

  test('Next during refresh does not prepare the superseded track', () async {
    var current = true;
    var prepared = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'expire');
      current = false;
      return null;
    });
    await OdifyYouTube.prepareAudioSource(
      source: source,
      resolved: nativeSource,
      isCurrent: () => current,
      prepare: (_) async {
        prepared++;
        throw PlatformException(code: 'AndroidAudioError');
      },
    );
    expect(prepared, 1);
  });

  test('second open failure propagates without another retry', () async {
    var prepared = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'expire') return null;
      return {'url': 'https://r1.googlevideo.com/videoplayback'};
    });
    await expectLater(OdifyYouTube.prepareAudioSource(
      source: source,
      resolved: nativeSource,
      isCurrent: () => true,
      prepare: (_) async {
        prepared++;
        throw PlatformException(code: 'AndroidAudioError');
      },
    ), throwsA(isA<PlatformException>()));
    expect(prepared, 2);
    await OdifyAudioProxy.shared.dispose();
  });

  test('local files and unrelated errors never trigger YouTube recovery', () async {
    var calls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async { calls++; return null; });
    for (final item in [
      ('/music/song.flac', 'AndroidAudioError'),
      (source, 'UsbOutputUnavailable'),
    ]) {
      await expectLater(OdifyYouTube.prepareAudioSource(
        source: item.$1,
        resolved: item.$1,
        isCurrent: () => true,
        prepare: (_) async => throw PlatformException(code: item.$2),
      ), throwsA(isA<PlatformException>()));
    }
    expect(calls, 0);
  });

  test('late Android media error invalidates and prepares a ranged relay', () async {
    final methods = <String>[];
    final prepared = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      methods.add(call.method);
      if (call.method == 'expire') return null;
      expect(call.method, 'resolve');
      return {
        'url': 'https://r1.googlevideo.com/videoplayback?renewed=true',
        'headers': {'User-Agent': 'OdifyTest/2'},
        'nativeSource': nativeSource,
      };
    });
    await OdifyYouTube.recoverAfterMediaError(
      source: source,
      isCurrent: () => true,
      prepare: (relay) async => prepared.add(relay),
    );
    expect(methods, ['expire', 'resolve']);
    expect(prepared, hasLength(1));
    expect(Uri.parse(prepared.single).host, '127.0.0.1');
    await OdifyAudioProxy.shared.dispose();
  });

  test('late recovery never starts previous song after Next', () async {
    var current = true;
    var prepares = 0;
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call.method);
      if (call.method == 'expire') {
        current = false;
        return null;
      }
      return {
        'url': 'https://r1.googlevideo.com/videoplayback',
      };
    });
    await OdifyYouTube.recoverAfterMediaError(
      source: source,
      isCurrent: () => current,
      prepare: (_) async => prepares++,
    );
    expect(calls, ['expire']);
    expect(prepares, 0);
  });

  test('late recovery ignores local file without invoking extractor', () async {
    var nativeCalls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async {
      nativeCalls++;
      return null;
    });
    await OdifyYouTube.recoverAfterMediaError(
      source: '/sdcard/Music/track.flac',
      isCurrent: () => true,
      prepare: (_) async => fail('Local file must not be retried'),
    );
    expect(nativeCalls, 0);
  });

  test('prefetch ignores local media paths', () async {
    var calls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls++;
          return true;
        });
    await OdifyYouTube.prefetchAudioSource('/music/song.flac');
    await OdifyYouTube.invalidateAudioSource('content://media/other');
    expect(calls, 0);
  });

}

