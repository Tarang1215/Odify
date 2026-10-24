# Native Android player reference, preview 12.13

The supplied Apple Music reference uses the album's spatial colors across the
whole screen, with diffuse image shapes behind the controls. A single dark tint
or a blurred square replacing the cover does not produce that effect.

The native player now samples up to three separated cover hues off the main
thread, keeps a bounded 64-cover palette cache, and combines those colors with
an enlarged, blurred original image and a restrained lower reflection. The
background preserves color instead of desaturating the cover. The lower dark
gradient keeps transport and waveform controls readable.

Lyrics switch to a small cover/title header and a wide, transparent viewport over
the same background. Timed lyrics emphasize the current line, dim surrounding
lines, and fade at the viewport edges. Text remains centered as requested for
Odify. Untimed lyrics remain manually scrollable; no timing is invented. Holding
the lyrics control still opens the existing private lyrics editor. Missing
lyrics do not replace the cover with an empty panel.

Mini-player, navigation and queue surfaces continue to share the primary cover
hue and recorded backdrop blur. The existing drag-to-mini transition and direct
touch response remain in place. No music files or embedded tags are rewritten.

Local validation: 30 JVM tests and Android instrumentation source compilation
passed. Device screenshots and installed-package checks are recorded separately
in V46_PROGRESS.md after installing the signed build.
