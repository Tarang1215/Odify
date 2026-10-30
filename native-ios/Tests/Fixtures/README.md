# Generated audio fixture

`silence-320k.mp3` contains four seconds of generated silence, 44.1 kHz stereo,
CBR 320 kbit/s MPEG Layer III, with ID3v2 and Xing/LAME headers. It contains no
recorded music, user media or external copyrighted recording. It is a test
resource only and is not bundled in the Odify application target.

Reproduce with FFmpeg and its libmp3lame encoder:

```
ffmpeg -f lavfi -i anullsrc=r=44100:cl=stereo -t 4 -c:a libmp3lame -b:a 320k -id3v2_version 3 -metadata title="Odify generated silence fixture" silence-320k.mp3
```
