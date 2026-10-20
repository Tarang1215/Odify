import 'package:flutter/material.dart';
import 'package:spotiflac_android/widgets/odify_glass.dart';

/// Four functional, readable shortcuts instead of Mornye's long row dividers.
/// The Library owns its one vertical scroll view; this only lays out a grid.
class OdifyLibraryShortcuts extends StatelessWidget {
  const OdifyLibraryShortcuts({
    super.key,
    required this.playlistsLabel,
    required this.artistsLabel,
    required this.albumsLabel,
    required this.songsLabel,
    required this.onPlaylists,
    required this.onArtists,
    required this.onAlbums,
    required this.onSongs,
  });

  final String playlistsLabel;
  final String artistsLabel;
  final String albumsLabel;
  final String songsLabel;
  final VoidCallback onPlaylists;
  final VoidCallback onArtists;
  final VoidCallback onAlbums;
  final VoidCallback onSongs;

  @override
  Widget build(BuildContext context) {
    final items = <({String text, IconData icon, VoidCallback onTap})>[
      (text: playlistsLabel, icon: Icons.queue_music_rounded, onTap: onPlaylists),
      (text: artistsLabel, icon: Icons.person_rounded, onTap: onArtists),
      (text: albumsLabel, icon: Icons.album_rounded, onTap: onAlbums),
      (text: songsLabel, icon: Icons.music_note_rounded, onTap: onSongs),
    ];
    final accent = Theme.of(context).colorScheme.primary;
    return LayoutBuilder(
      builder: (context, constraints) {
        // Max two columns even on wide layouts; four natural touch targets.
        // Text scales without hard-coded 1-line widths at accessibility sizes.
        final textScale = MediaQuery.textScalerOf(context).scale(14) / 14;
        final twoColumns = constraints.maxWidth >= 285 &&
            textScale < 1.35;
        final width = twoColumns
            ? (constraints.maxWidth - 12) / 2
            : constraints.maxWidth;
        return Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            for (var index = 0; index < items.length; index++)
              SizedBox(
                width: width,
                child: OdifyGlassSurface(
                  key: ValueKey('odify-library-shortcut-$index'),
                  accent: index == 0 ? accent : null,
                  radius: 21,
                  onTap: items[index].onTap,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 14,
                  ),
                  child: Row(
                    children: [
                      Container(
                        width: 43,
                        height: 43,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          color: accent.withValues(
                            alpha: index == 0 ? 0.17 : 0.09,
                          ),
                          borderRadius: BorderRadius.circular(14),
                        ),
                        child: Icon(
                          items[index].icon,
                          color: accent,
                          size: 23,
                        ),
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          items[index].text,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.labelLarge
                              ?.copyWith(fontWeight: FontWeight.w700),
                        ),
                      ),
                      const Icon(Icons.chevron_right_rounded, size: 17),
                    ],
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}
