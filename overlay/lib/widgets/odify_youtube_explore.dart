import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:spotiflac_android/providers/music_player_provider.dart';
import 'package:spotiflac_android/providers/local_library_provider.dart';
import 'package:spotiflac_android/services/odify_youtube.dart';

/// An Odify entry point in the existing SpotiFLAC Home, rather than a browser.
class OdifyYouTubeExploreCard extends StatelessWidget {
  const OdifyYouTubeExploreCard({super.key});

  @override
  Widget build(BuildContext context) => Card.filled(
    child: ListTile(
      leading: const Icon(Icons.headphones_rounded),
      title: const Text('Discover music & covers'),
      subtitle: const Text('YouTube search · acoustic covers · live performances'),
      trailing: const Icon(Icons.arrow_forward_ios_rounded, size: 16),
      onTap: () => Navigator.of(context, rootNavigator: true).push(
        MaterialPageRoute<void>(builder: (_) => const OdifyYouTubeExplorer()),
      ),
    ),
  );
}

class OdifyYouTubeExplorer extends ConsumerStatefulWidget {
  const OdifyYouTubeExplorer({super.key});

  @override
  ConsumerState<OdifyYouTubeExplorer> createState() => _OdifyYouTubeExplorerState();
}

class _OdifyYouTubeExplorerState extends ConsumerState<OdifyYouTubeExplorer> {
  final _searchController = TextEditingController();
  final _results = <OdifyVideo>[];
  String _category = 'All';
  String? _busyVideo;
  String? _error;
  bool _loading = false;

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final input = _searchController.text.trim();
    if (input.isEmpty || _loading) return;
    setState(() { _loading = true; _error = null; });
    try {
      final isPlaylist = input.startsWith('https://') && input.contains('list=');
      final query = _category == 'Covers'
          ? '$input cover 歌ってみた 커버'
          : _category == 'Live' ? '$input live performance' : input;
      final result = isPlaylist
          ? await OdifyYouTube.playlist(input)
          : await OdifyYouTube.search(query);
      if (!mounted) return;
      setState(() { _results..clear()..addAll(result); });
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _play(OdifyVideo video) async {
    if (_busyVideo != null) return;
    setState(() => _busyVideo = video.id);
    try {
      final media = OdifyYouTube.playable(video);
      await ref.read(musicPlayerControllerProvider).playSingle(media);
    } catch (error) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('재생에 실패했습니다: $error')));
    } finally {
      if (mounted) setState(() => _busyVideo = null);
    }
  }

  Future<void> _download(OdifyVideo video) async {
    if (_busyVideo != null) return;
    setState(() => _busyVideo = video.id);
    try {
      await OdifyYouTube.downloadMp3(video);
      if (!mounted) return;
      await ref.read(localLibraryProvider.notifier).reloadFromStorage();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('${video.title} · MP3가 라이브러리에 추가되었습니다.')));
    } catch (error) {
      if (mounted) ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('MP3 저장에 실패했습니다: $error')));
    } finally {
      if (mounted) setState(() => _busyVideo = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Odify Discover')),
      body: SafeArea(child: Column(children: [
        Padding(padding: const EdgeInsets.fromLTRB(16, 10, 16, 2), child: TextField(
          controller: _searchController,
          textInputAction: TextInputAction.search,
          onSubmitted: (_) => _search(),
          decoration: InputDecoration(
            hintText: '곡·가수·커버곡 또는 공개 플레이리스트 URL',
            prefixIcon: const Icon(Icons.search),
            suffixIcon: IconButton(
              tooltip: '검색',
              onPressed: _loading ? null : _search,
              icon: const Icon(Icons.arrow_forward_rounded),
            ),
            border: const OutlineInputBorder(),
          ),
        )),
        SizedBox(height: 50, child: ListView(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          scrollDirection: Axis.horizontal,
          children: [for (final name in const ['All','Covers','Live'])
            Padding(padding: const EdgeInsets.only(right: 8), child: ChoiceChip(
              label: Text(name),
              selected: _category == name,
              onSelected: (_) { setState(() => _category = name); if (_searchController.text.trim().isNotEmpty) _search(); },
            )),
          ],
        )),
        if (_loading) const LinearProgressIndicator(),
        if (_error != null) Padding(
          padding: const EdgeInsets.all(16),
          child: Text(_error!, style: TextStyle(color: colors.error)),
        ),
        Expanded(child: ListView.builder(
          itemCount: _results.length,
          itemBuilder: (context, index) {
            final song = _results[index];
            final running = _busyVideo == song.id;
            return ListTile(
              contentPadding: const EdgeInsets.fromLTRB(16, 4, 8, 4),
              leading: ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: Image.network(song.thumbnail, width: 64, height: 52,
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => const SizedBox(
                    width: 64, child: Icon(Icons.music_note))),
              ),
              title: Text(song.title, maxLines: 2, overflow: TextOverflow.ellipsis),
              subtitle: Text('${song.artist}${song.isCover ? ' · Cover' : ''}',
                maxLines: 1, overflow: TextOverflow.ellipsis),
              onTap: running ? null : () => _play(song),
              trailing: running
                ? const SizedBox(width: 48, height: 24, child: Center(
                    child: CircularProgressIndicator(strokeWidth: 2)))
                : SizedBox(width: 102, child: Row(children: [
                    IconButton(tooltip: 'Odify에서 재생',
                      icon: const Icon(Icons.play_circle), onPressed: _busyVideo == null ? () => _play(song) : null),
                    IconButton(tooltip: 'MP3로 저장',
                      icon: const Icon(Icons.download_rounded), onPressed: _busyVideo == null ? () => _download(song) : null),
                  ])),
            );
          },
        )),
      ])),
    );
  }
}
