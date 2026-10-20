import 'dart:async';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mornye/models/equalizer_settings.dart';
import 'package:mornye/services/music_playback_deck.dart';
import 'package:mornye/services/playback_equalizer.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final calls = <MethodCall>[];
  bool reject = false;
  Completer<void>? effectGate;

  setUp(() {
    calls.clear();
    reject = false;
    effectGate = null;
    playbackEqualizer.value = const EqualizerSettings();
    for (final name in [
      'xyz.luan/audioplayers.global',
      'xyz.luan/audioplayers.global/events',
      'com.zarz.mornye/usb_pcm/events',
    ]) {
      messenger.setMockMethodCallHandler(
        MethodChannel(name),
        (_) async => null,
      );
    }
    messenger.setMockMethodCallHandler(
      const MethodChannel('com.zarz.mornye/usb_pcm'),
      (call) async => call.method == 'prepare'
          ? {
              'ready': true,
              'duration': 60000,
              'bitDepth': 24,
              'sampleRate': 96000,
            }
          : null,
    );
    messenger.setMockMethodCallHandler(
      const MethodChannel('xyz.luan/audioplayers'),
      (call) async {
        calls.add(call);
        final args = call.arguments as Map<Object?, Object?>;
        final id = args['playerId']! as String;
        if (call.method == 'create') {
          messenger.setMockMethodCallHandler(
            MethodChannel('xyz.luan/audioplayers/events/$id'),
            (_) async => null,
          );
        } else if (call.method == 'setSourceUrl') {
          unawaited(
            messenger.handlePlatformMessage(
              'xyz.luan/audioplayers/events/$id',
              const StandardMethodCodec().encodeSuccessEnvelope({
                'event': 'audio.onPrepared',
                'value': true,
              }),
              (_) {},
            ),
          );
        } else if (call.method == 'getDuration') {
          return 60000;
        } else if (call.method == 'setEqualizer') {
          await effectGate?.future;
          if (reject) {
            throw PlatformException(
              code: 'effect_unavailable',
              message: 'Unsupported output',
            );
          }
        }
        return null;
      },
    );
  });

  test(
    'all AutoMix decks receive live EQ and recreated sources restore it',
    () async {
      final outgoing = MusicPlaybackDeck(
        playerId: 'eq-outgoing',
        equalizerAvailable: true,
      );
      final incoming = MusicPlaybackDeck(
        playerId: 'eq-incoming',
        equalizerAvailable: true,
      );
      await outgoing.setSource(DeviceFileSource('/outgoing.flac'));
      await incoming.setSource(DeviceFileSource('/incoming.flac'));
      expect(calls.where((c) => c.method == 'setEqualizer'), isEmpty);
      playbackEqualizer.value = const EqualizerSettings(enabled: true, bass: 3);
      await Future<void>.delayed(const Duration(milliseconds: 80));
      final effects = calls.where((c) => c.method == 'setEqualizer').toList();
      expect(effects.length, 2);
      expect(effects.map((c) => (c.arguments as Map)['playerId']).toSet(), {
        'eq-outgoing',
        'eq-incoming',
      });
      final beforeDragging = calls
          .where((c) => c.method == 'setEqualizer')
          .length;
      for (var i = 1; i <= 6; i++) {
        playbackEqualizer.value = EqualizerSettings(
          enabled: true,
          bass: i.toDouble(),
        );
        await Future<void>.delayed(const Duration(milliseconds: 20));
      }
      expect(
        calls.where((c) => c.method == 'setEqualizer').length,
        greaterThan(beforeDragging + 2),
      );
      await outgoing.setSource(DeviceFileSource('/next.flac'));
      expect(
        (calls.last.arguments as Map)['configuration'],
        containsPair('enabled', true),
      );
      playbackEqualizer.value = const EqualizerSettings();
      await Future<void>.delayed(const Duration(milliseconds: 80));
      for (final id in ['eq-outgoing', 'eq-incoming']) {
        final update = calls.lastWhere(
          (c) =>
              c.method == 'setEqualizer' &&
              (c.arguments as Map)['playerId'] == id,
        );
        expect(
          (update.arguments as Map)['configuration'],
          containsPair('enabled', false),
        );
      }
      await outgoing.dispose();
      await incoming.dispose();
    },
  );

  test(
    'bit-perfect PCM bypasses EQ and returns to EQ on ordinary playback',
    () async {
      final deck = MusicPlaybackDeck(
        playerId: 'eq-usb',
        nativeAvailable: true,
        equalizerAvailable: true,
      );
      playbackEqualizer.value = const EqualizerSettings(enabled: true);
      await deck.setSource(
        DeviceFileSource('/usb.flac'),
        preferBitPerfect: true,
        directUsb: true,
      );
      await Future<void>.delayed(const Duration(milliseconds: 80));
      for (final call in calls.where((c) => c.method == 'setEqualizer')) {
        expect(
          (call.arguments as Map)['configuration'],
          containsPair('enabled', false),
        );
      }
      await deck.setSource(DeviceFileSource('/ordinary.flac'));
      expect(
        (calls.last.arguments as Map)['configuration'],
        containsPair('enabled', true),
      );
      await deck.dispose();
    },
  );

  test('a late native error cannot outlive a disposed deck', () async {
    final deck = MusicPlaybackDeck(
      playerId: 'eq-disposed',
      equalizerAvailable: true,
    );
    effectGate = Completer<void>();
    reject = true;
    playbackEqualizer.value = const EqualizerSettings(enabled: true);
    final preparing = deck.setSource(DeviceFileSource('/song.flac'));
    await Future<void>.delayed(const Duration(milliseconds: 60));
    expect(calls.where((c) => c.method == 'setEqualizer'), isNotEmpty);
    await deck.dispose();
    effectGate!.complete();
    await preparing;
    expect(playbackEqualizerError.value, isNull);
  });

  test(
    'unsupported effects report an error without breaking playback',
    () async {
      final deck = MusicPlaybackDeck(
        playerId: 'eq-error',
        equalizerAvailable: true,
      );
      reject = true;
      playbackEqualizer.value = const EqualizerSettings(enabled: true);
      await deck.setSource(DeviceFileSource('/song.flac'));
      expect(playbackEqualizerError.value, contains('Unsupported output'));
      await deck.resume();
      expect(calls.last.method, 'resume');
      await deck.dispose();
      expect(playbackEqualizerError.value, isNull);
      final count = calls.length;
      playbackEqualizer.value = const EqualizerSettings(bass: 6);
      await Future<void>.delayed(const Duration(milliseconds: 80));
      expect(calls.length, count);
    },
  );
}
