import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:spotiflac_android/providers/local_library_provider.dart';
import 'package:spotiflac_android/services/odify_device_library.dart';

class OdifyDeviceLibraryCard extends ConsumerStatefulWidget {
  const OdifyDeviceLibraryCard({super.key});

  @override
  ConsumerState<OdifyDeviceLibraryCard> createState() =>
      _OdifyDeviceLibraryCardState();
}

class _OdifyDeviceLibraryCardState extends ConsumerState<OdifyDeviceLibraryCard> {
  bool _busy = false;

  @override
  Widget build(BuildContext context) {
    if (!Platform.isAndroid) return const SizedBox.shrink();
    final library = ref.watch(localLibraryProvider);
    if (_busy || library.sources.isNotEmpty || library.isLoading) {
      return const SizedBox.shrink();
    }
    return Card.filled(
      child: ListTile(
        leading: const Icon(Icons.library_music_outlined),
        title: const Text('Connect your phone music'),
        subtitle: const Text(
          'Import Samsung Music files without copying them. '
          'Import Samsung playlists separately as M3U.'
        ),
        isThreeLine: true,
        trailing: const Icon(Icons.chevron_right),
        onTap: () async {
          setState(() => _busy = true);
          try {
            final folders = await OdifyDeviceMusicImport.connect(
              ref.read(localLibraryProvider.notifier),
            );
            if (!context.mounted) return;
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text('Connected: ${folders.join(', ')}')),
            );
          } catch (e) {
            if (!context.mounted) return;
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text('$e')),
            );
          } finally {
            if (mounted) setState(() => _busy = false);
          }
        },
      ),
    );
  }
}
