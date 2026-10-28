"""Observe genuine muted, screen-off playback without holding the app alive.

Start music through the app/widget first. This script never seeks or skips.
Only Odify sessions may be paused during cleanup; no global media keys.
USB/charging + ADB observation is not a battery/deep-Doze qualification.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time

PKG = 'com.odify.music.nativepreview.v11.debug'
parser = argparse.ArgumentParser()
parser.add_argument('--minutes', type=int, default=60)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if not 1 <= args.minutes <= 240:
    raise SystemExit('Choose 1–240 minutes')
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:
    raise SystemExit('Set ANDROID_HOME before starting the observation')
adb = str(Path(sdk) / 'platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb'))
args.output.mkdir(parents=True, exist_ok=True)
status_path = args.output / 'status.json'
if status_path.exists():
    raise SystemExit('Use a new output directory; do not overwrite another observation')


def call(*words, timeout=12, stdin=None):
    return subprocess.run([adb, *words], input=stdin, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, encoding='utf-8',
                          errors='replace', timeout=timeout, check=True,
                          creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0)).stdout


def now():
    return dt.datetime.now(dt.timezone(dt.timedelta(hours=9))).isoformat()


def own_sessions():
    output = call('shell', 'dumpsys', 'media_session')
    results = []
    owner = False
    row = None
    for line in output.splitlines():
        if 'package=' in line:
            owner = re.search(r'package=' + re.escape(PKG) + r'(?:\s|,|$)', line) is not None
            row = None
        if not owner:
            continue
        state = re.search(r'state=PlaybackState \{state=(\w+)\((\d+)\), position=(-?\d+)', line)
        if state:
            row = {'state': state[1], 'code': int(state[2]), 'position': int(state[3]), 'track': ''}
            results.append(row)
        if row is not None and 'metadata:' in line and 'description=' in line:
            row['track'] = line.split('description=', 1)[1].strip()
    return results


def pause_own():
    listing = call('shell', 'cmd', 'media_session', 'list-sessions')
    all_tags = re.findall(r'^\s*tag=(.*), package=(\S+)\s*$', listing, re.M)
    for tag, package in all_tags:
        if package == PKG and sum(t == tag for t, _ in all_tags) == 1:
            call('shell', 'cmd media_session monitor ' + shlex.quote(tag), stdin='pause\nq\n')
    time.sleep(1)
    if any(s['code'] == 3 for s in own_sessions()):
        # Last resort is limited to this app, never another app's media focus.
        call('shell', 'am', 'force-stop', '--user', '0', PKG)
        return 'own-app-force-stop-fallback'
    return 'own-sessions-paused'


def write_status(report):
    temp = status_path.with_suffix('.tmp')
    temp.write_text(json.dumps(report, ensure_ascii=True, indent=2), encoding='utf-8')
    temp.replace(status_path)


devices = re.findall(r'^(\S+)\s+device\s*$', call('devices'), re.M)
if len(devices) != 1:
    raise SystemExit('Exactly one authorized USB phone is required')
awake = call('shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in').strip()
if awake not in set('01234567'):
    raise SystemExit('Unable to read original screen setting')
report = {'state': 'STARTING', 'startedAt': now(), 'minutes': args.minutes,
          'condition': 'USB charging, music volume zero, screen off; external ADB polling',
          'samples': 0, 'screenOffSamples': 0, 'transitions': [], 'incidents': []}
write_status(report)
started = time.monotonic()
last_progress = started
last_track = None
last_position = None
initial_pid = None
try:
    call('shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--set', '0')
    call('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '0')
    if not any(s['code'] == 3 for s in own_sessions()):
        raise RuntimeError('No actual Odify playback: start it in the app/widget first')
    call('shell', 'input', 'keyevent', '223')  # Sleep only; never toggle a sleeping screen on.
    report['state'] = 'RUNNING'
    while time.monotonic() - started < args.minutes * 60:
        elapsed = time.monotonic() - started
        volume = call('shell', 'cmd', 'media_session', 'volume', '--stream', '3', '--get')
        if not re.search(r'volume is 0 in range', volume):
            raise RuntimeError('Music volume changed: stopped to preserve muted-test permission')
        pid = call('shell', 'pidof', PKG).strip()
        if not pid.isdigit():
            raise RuntimeError('Odify main process disappeared')
        if initial_pid is None:
            initial_pid = pid
        if pid != initial_pid:
            raise RuntimeError('Odify main PID changed during observation')
        power = call('shell', 'dumpsys', 'power')
        off = bool(re.search(r'mWakefulness=(?:Asleep|Dozing)\b', power))
        sessions = own_sessions()
        active = next((s for s in sessions if s['code'] == 3),
                      next((s for s in sessions if s['track']), None))
        sample = {'at': now(), 'elapsed': round(elapsed, 1), 'pid': pid,
                  'screenOff': off, 'session': active}
        with (args.output / 'samples.jsonl').open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(sample, ensure_ascii=True) + '\n')
        if active is not None:
            track, position = active['track'], active['position']
            if (last_track is not None and track and track != last_track
                    and last_position is not None and position < last_position - 2000):
                report['transitions'].append({'at': now(), 'elapsed': round(elapsed, 1),
                                              'from': last_track, 'to': track, 'screenOff': off})
            if (track and track != last_track) or (position != last_position and active['code'] == 3):
                last_progress = time.monotonic()
            last_track, last_position = track, position
        if time.monotonic() - last_progress > 60:
            raise RuntimeError('No actual media-position progress for over 60 seconds')
        report.update(samples=report['samples'] + 1,
                      screenOffSamples=report['screenOffSamples'] + int(off),
                      elapsedSeconds=round(elapsed, 1), lastSample=sample)
        write_status(report)
        time.sleep(min(15, max(0, args.minutes * 60 - (time.monotonic() - started))))
    if not any(t['screenOff'] for t in report['transitions']):
        raise RuntimeError('No natural track transition observed while the screen was off')
    if report['screenOffSamples'] < report['samples'] * .95:
        raise RuntimeError('Screen-off coverage was below 95%; observation is incomplete')
    report['state'] = 'PASSED'
except BaseException as error:
    report['state'] = 'FAILED'
    report['incidents'].append({'at': now(), 'reason': str(error)})
    if initial_pid:
        try:
            (args.output / 'own-crash-log.txt').write_text(
                call('shell', 'logcat', '-d', '-b', 'crash', '--pid=' + initial_pid, '-t', '160'), encoding='utf-8')
        except Exception:
            pass
finally:
    try:
        report['cleanup'] = pause_own()
    except Exception as error:
        report['cleanupError'] = str(error)
        report['state'] = 'FAILED'
    try:
        call('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', awake)
    except Exception as error:
        report['settingRestoreError'] = str(error)
        report['state'] = 'FAILED'
    report.update(finishedAt=now(), elapsedSeconds=round(time.monotonic() - started, 1))
    write_status(report)
print(json.dumps(report, ensure_ascii=True))
