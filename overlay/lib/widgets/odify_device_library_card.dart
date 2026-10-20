import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:spotiflac_android/providers/local_library_provider.dart';
import 'package:spotiflac_android/services/odify_device_library.dart';
import 'package:spotiflac_android/widgets/odify_device_music_browser.dart';
import 'package:spotiflac_android/services/odify_device_catalog.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:device_info_plus/device_info_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Device MediaStore is browseable regardless of existing SpotiFLAC library sources.
class OdifyDeviceLibraryCard extends ConsumerStatefulWidget {
  const OdifyDeviceLibraryCard({super.key});

  @override
  ConsumerState<OdifyDeviceLibraryCard> createState() =>
      _OdifyDeviceLibraryCardState();
}

class _OdifyDeviceLibraryCardState extends ConsumerState<OdifyDeviceLibraryCard> {
  bool _busy = false;
  bool _syncBusy = false;
  static const _syncFlag = 'odify_media_sync_opted_in_v1';

  @override
  void initState() {
    super.initState();
    _autoSync();
  }

  Future<void> _autoSync() async {
    final prefs = await SharedPreferences.getInstance();
    if (prefs.getBool(_syncFlag) != true || !mounted) return;
    // Permission may have been revoked in Android settings.
    final android = await DeviceInfoPlugin().androidInfo;
    final permission = android.version.sdkInt >= 33
        ? Permission.audio : Permission.storage;
    if (await permission.isGranted && mounted) await _sync(requestPermission: false);
  }

  Future<void> _sync({bool requestPermission = true}) async {
    if (_syncBusy) return;
    setState(() => _syncBusy = true);
    try {
      final android = await DeviceInfoPlugin().androidInfo;
      final permission = android.version.sdkInt >= 33
          ? Permission.audio : Permission.storage;
      final status = requestPermission ? await permission.request()
          : await permission.status;
      if (!status.isGranted) throw StateError('음악 접근 권한이 필요합니다.');
      final count = await OdifyDeviceCatalog.syncToLibrary();
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool(_syncFlag, true);
      if (!mounted) return;
      ref.invalidate(localLibraryProvider);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Odify 라이브러리에 음악 $count곡 동기화 완료')),
      );
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('동기화 실패: $error')),
        );
      }
    } finally {
      if (mounted) setState(() => _syncBusy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!Platform.isAndroid) return const SizedBox.shrink();
    final library = ref.watch(localLibraryProvider);
    return Card.filled(
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Column(children: [
          ListTile(
            leading: const Icon(Icons.library_music_outlined),
            title: const Text('내 휴대폰 음악'),
            subtitle: const Text('Samsung Music에서 보이는 로컬 음악을 Odify에서도 재생하고 취향에 반영합니다.'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => showModalBottomSheet<void>(
              context: context,
              isScrollControlled: true,
              useSafeArea: true,
              builder: (context) => const FractionallySizedBox(
                heightFactor: 0.88,
                child: OdifyDeviceMusicBrowser(),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                onPressed: _syncBusy ? null : () => _sync(),
                icon: const Icon(Icons.sync_outlined),
                label: Text(_syncBusy ? '음악 동기화 중...' : 'Odify 라이브러리에 동기화'),
              ),
            ),
          ),
          if (library.sources.isEmpty && !library.isLoading)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: _busy ? null : _connectFolders,
                  icon: const Icon(Icons.folder_open_outlined),
                  label: Text(_busy ? '라이브러리 연결 중...' : '공용 음악 폴더를 라이브러리에 등록'),
                ),
              ),
            ),
        ]),
      ),
    );
  }

  Future<void> _connectFolders() async {
    setState(() => _busy = true);
    try {
      final folders = await OdifyDeviceMusicImport.connect(
        ref.read(localLibraryProvider.notifier),
      );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('연결 완료: ${folders.join(', ')}')),
      );
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('$error')),
      );
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}
