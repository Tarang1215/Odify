# Native Android player reference, preview 12.15

The supplied Apple Music reference uses the album's spatial colors across the
whole screen, with diffuse image shapes behind the controls. A single dark tint
or a blurred square replacing the cover does not produce that effect.

The native player now uses the actual original cover across the full backdrop,
with a restrained 1.18x expansion and 36dp blur. There is no palette-color gradient
or partial-height reflection: those layers flattened the image and introduced
visible bands in preview12.13. Stretching the already-blurred complete composition
preserves its spatial colors instead of cropping a small central region. A
continuous lower dark scrim keeps controls readable. The separately cached cover
palette still supplies the mini-player/navigation hue.

Lyrics switch to a small cover/title header and a wide, transparent viewport over
the same background. Timed lyrics emphasize the current line, dim surrounding
lines, and fade at the viewport edges. Text remains centered as requested for
Odify. Untimed lyrics remain manually scrollable; no timing is invented. Holding
the lyrics control still opens the existing private lyrics editor. While lyrics
are open, only the top handle drags the player down; body scrolling cannot
collapse it. Missing
lyrics do not replace the cover with an empty panel.

Mini-player, navigation and queue surfaces continue to share the primary cover
hue and recorded backdrop blur. Tapping either mini-player cover opens the full
player; tapping its remaining body toggles width. The drag-to-mini transition and direct
touch response remain in place. No music files or embedded tags are rewritten.

Local validation: 31 JVM tests and Android instrumentation source compilation
passed. Device screenshots and installed-package checks are recorded separately
in V46_PROGRESS.md after installing the signed build.
