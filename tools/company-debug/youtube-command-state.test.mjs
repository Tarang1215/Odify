import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

// No browser, network or audio API exists in this sandbox. The source is the
// real HTML exported by OdifyYoutubeWebPageTest during the JVM test run.
function player(name = 'playing', ready = true) {
  const path = new URL(`../../native-android/app/build/reports/youtube-player/${name}.html`, import.meta.url);
  const html = readFileSync(fileURLToPath(path), 'utf8');
  const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
  let options, state = -1, now = 0, position = 0;
  const calls = [];
  const context = vm.createContext({ window: {}, Date: { now: () => now }, YT: {
    Player: function (_element, config) {
      options = config;
      return { playVideo() { calls.push('play'); }, pauseVideo() { calls.push('pause'); },
        seekTo(seconds) { position = seconds; calls.push('seek'); },
        getPlayerState: () => state, getCurrentTime: () => position, getDuration: () => 180 };
    }
  }});
  vm.runInContext(script, context, { timeout: 1000 });
  context.onYouTubeIframeAPIReady();
  const onReady = () => options.events.onReady();
  if (ready) onReady();
  return { context, calls, onReady, snapshot: () => context.window.odifySnapshot(),
    state(value, notify = true) { state = value; if (notify) options.events.onStateChange({ data: value }); },
    time(value) { now = value; } };
}

test('buffering preserves play intent and document identity', () => {
  const p = player(); p.state(3);
  assert.equal(p.snapshot().videoId, 'cmpRLQZkTb8');
  assert.equal(p.snapshot().documentVersion, 0);
  assert.equal(p.snapshot().buffering, true);
  assert.equal(p.snapshot().playing, false);
});
test('a late playing event cannot undo the user pause', () => {
  const p = player(); p.state(1); p.context.odifyPause(); p.state(1);
  assert.equal(p.snapshot().wantsPlayback, false);
  assert.equal(p.snapshot().playing, false);
  assert.equal(p.snapshot().buffering, false);
  assert.equal(p.calls.at(-1), 'pause');
});
test('a stale pause acknowledgement cannot cancel a newer resume', () => {
  const p = player(); p.context.odifyPause(); p.state(2);
  p.context.odifyPlay(); p.state(2);
  assert.equal(p.snapshot().wantsPlayback, true);
  assert.equal(p.snapshot().buffering, true);
  p.state(1);
  assert.equal(p.snapshot().playing, true);
});
test('pause before player readiness prevents autoplay', () => {
  const p = player('playing', false); p.context.odifyPause(); p.onReady();
  assert.equal(p.calls.includes('play'), false);
  assert.equal(p.snapshot().wantsPlayback, false);
});
test('recovery can restore position without starting a paused song', () => {
  const p = player('paused');
  assert.equal(p.snapshot().videoId, 'abcdefghijk');
  assert.equal(p.snapshot().position, 42);
  assert.equal(p.calls.includes('play'), false);
});
test('ordinary end continues but an old end after pause does not', () => {
  const p = player(); p.state(1); p.state(0);
  assert.equal(p.snapshot().wantsPlayback, true);
  p.context.odifyPause(); p.state(0);
  assert.equal(p.snapshot().wantsPlayback, false);
});
test('a rejected resume eventually returns to a real paused state', () => {
  const p = player(); p.context.odifyPlay(); p.state(2); p.time(4000);
  assert.equal(p.snapshot().wantsPlayback, false);
  assert.equal(p.snapshot().buffering, false);
});

test('a late player readiness starts its own command acknowledgement window', () => {
  const p = player('playing', false); p.time(10000); p.onReady(); p.state(2);
  assert.equal(p.snapshot().buffering, true);
  assert.equal(p.snapshot().wantsPlayback, true);
});

test('a pending resume retries a stale pause with a bound and respects a new pause', () => {
  const p = player(); p.state(1); p.context.odifyPause(); p.state(2);
  p.context.odifyPlay(); p.state(2); const before = p.calls.length;
  p.time(600); p.snapshot(); assert.equal(p.calls.length, before + 1);
  assert.equal(p.calls.at(-1), 'play');
  p.snapshot(); assert.equal(p.calls.length, before + 1);
  p.context.odifyPause(); const paused = p.calls.length;
  p.time(1500); p.snapshot(); assert.equal(p.calls.length, paused);
  assert.equal(p.snapshot().wantsPlayback, false);
});
