import 'dart:io';

import 'package:flutter/material.dart';
import 'package:spotiflac_android/services/odify_device_artwork.dart';
import 'package:spotiflac_android/services/odify_device_catalog.dart';

/// Bounded-size, lazy Android MP3 cover shown for visible MediaStore rows.
/// It never decodes the original full-resolution ID3 image in Dart.
class OdifyDeviceArtworkTile extends StatefulWidget {
  const OdifyDeviceArtworkTile({
    super.key,
    required this.track,
    this.size = 48,
  });

  final OdifyDeviceTrack track;
  final double size;

  @override
  State<OdifyDeviceArtworkTile> createState() =>
      _OdifyDeviceArtworkTileState();
}

class _OdifyDeviceArtworkTileState extends State<OdifyDeviceArtworkTile> {
  late Future<String?> _cover;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    _cover = OdifyDeviceArtwork.forId(
      widget.track.id,
      modifiedSeconds: widget.track.modifiedSeconds,
      nativeCachedCover: widget.track.cachedCoverPath,
    );
  }

  @override
  void didUpdateWidget(covariant OdifyDeviceArtworkTile oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.track.id != widget.track.id ||
        oldWidget.track.modifiedSeconds != widget.track.modifiedSeconds ||
        oldWidget.track.cachedCoverPath != widget.track.cachedCoverPath) {
      _load();
    }
  }

  @override
  Widget build(BuildContext context) {
    final size = widget.size;
    final placeholder = SizedBox(
      width: size,
      height: size,
      child: const Icon(Icons.music_note_outlined),
    );
    return ClipRRect(
      borderRadius: BorderRadius.circular(8),
      child: FutureBuilder<String?>(
        future: _cover,
        builder: (context, snapshot) {
          final path = snapshot.data;
          if (path == null || path.isEmpty) return placeholder;
          return Image.file(
            File(path),
            width: size,
            height: size,
            cacheWidth: (size * MediaQuery.devicePixelRatioOf(context))
                .round()
                .clamp(48, 256),
            fit: BoxFit.cover,
            gaplessPlayback: true,
            errorBuilder: (_, _, _) => placeholder,
          );
        },
      ),
    );
  }
}
