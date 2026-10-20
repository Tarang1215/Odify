/// Portable and deterministic Odify metadata merge for offline devices.
///
/// Stage A of Drive sync: pure Dart (no Google account, network, or database).
/// Drive transport and a durable local outbox are added in later stages.
/// Events MUST be immutable and get unique identifiers before uploading.

class OdifySourceRef {
  const OdifySourceRef({
    required this.kind,
    required this.title,
    this.artist = '',
    this.videoId,
    this.fingerprint,
    this.durationMs,
  });

  final String kind;
  final String title;
  final String artist;
  final String? videoId;
  final String? fingerprint;
  final int? durationMs;

  factory OdifySourceRef.fromJson(Map<String, Object?> json) {
    final kind = json['kind'];
    final title = json['title'];
    if (title is! String || title.isEmpty || title.length > 1024) {
      throw const FormatException('Invalid sync track title');
    }
    final artist = json['artist'];
    if (artist != null && (artist is! String || artist.length > 1024)) {
      throw const FormatException('Invalid sync artist');
    }
    final length = json['durationMs'];
    if (length != null && (length is! int || length < 0)) {
      throw const FormatException('Invalid track duration');
    }
    if (kind == 'youtube') {
      final id = json['videoId'];
      if (id is! String ||
          !RegExp(r'^[A-Za-z0-9_-]{11}$').hasMatch(id)) {
        throw const FormatException('Invalid case-sensitive YouTube video ID');
      }
      return OdifySourceRef(
        kind: 'youtube',
        title: title,
        artist: artist as String? ?? '',
        videoId: id,
        durationMs: length as int?,
      );
    }
    if (kind == 'local') {
      final fingerprint = json['fingerprint'];
      if (fingerprint != null &&
          (fingerprint is! String || fingerprint.length > 160)) {
        throw const FormatException('Invalid local track fingerprint');
      }
      return OdifySourceRef(
        kind: 'local',
        title: title,
        artist: artist as String? ?? '',
        fingerprint: fingerprint as String?,
        durationMs: length as int?,
      );
    }
    throw const FormatException('Unsupported sync media source');
  }

  Map<String, Object?> toJson() => {
    'kind': kind,
    'title': title,
    'artist': artist,
    if (videoId != null) 'videoId': videoId,
    if (fingerprint != null) 'fingerprint': fingerprint,
    if (durationMs != null) 'durationMs': durationMs,
  };
}

class OdifySyncEvent {
  const OdifySyncEvent({
    required this.eventId,
    required this.deviceId,
    required this.hlc,
    required this.op,
    required this.payload,
  });

  final String eventId;
  final String deviceId;
  final String hlc;
  final String op;
  final Map<String, Object?> payload;

  factory OdifySyncEvent.fromJson(Map<String, Object?> json) {
    final eventId = json['eventId'];
    final deviceId = json['deviceId'];
    final hlc = json['hlc'];
    final op = json['op'];
    final payload = json['payload'];
    if (eventId is! String || eventId.trim().isEmpty ||
        eventId.length > 128 || deviceId is! String ||
        deviceId.trim().isEmpty || deviceId.length > 128 ||
        hlc is! String || !RegExp(
          r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z#\d{4}$',
        ).hasMatch(hlc) ||
        op is! String || op.length > 60 ||
        payload is! Map<String, Object?>) {
      throw const FormatException('Invalid Odify sync event');
    }
    return OdifySyncEvent(
      eventId: eventId,
      deviceId: deviceId,
      hlc: hlc,
      op: op,
      payload: Map.unmodifiable(payload),
    );
  }

  Map<String, Object?> toJson() => {
    'schemaVersion': 1,
    'eventId': eventId,
    'deviceId': deviceId,
    'hlc': hlc,
    'op': op,
    'payload': payload,
  };
}

class OdifySyncEntry {
  OdifySyncEntry({
    required this.id,
    required this.position,
    required this.source,
  });

  final String id;
  String position;
  final OdifySourceRef source;
}

class OdifySyncPlaylist {
  OdifySyncPlaylist(this.id, this.name);

  final String id;
  String name;
  bool deleted = false;
  final Map<String, OdifySyncEntry> entries = {};

  List<OdifySyncEntry> get orderedEntries {
    if (deleted) return const [];
    final items = entries.values.toList();
    items.sort((a, b) {
      final order = a.position.compareTo(b.position);
      return order == 0 ? a.id.compareTo(b.id) : order;
    });
    return items;
  }
}

class OdifySyncState {
  OdifySyncState._(this.playlists, this.likes, this.eventCount);

  final Map<String, OdifySyncPlaylist> playlists;
  final Map<String, bool> likes;
  final int eventCount;

  /// Order-independent deterministic event union for immutable Drive batches.
  /// Adds on different devices survive; entry/playlist removes always win.
  static OdifySyncState reduce(Iterable<OdifySyncEvent> incoming) {
    final unique = <String, OdifySyncEvent>{};
    for (final event in incoming) {
      final previous = unique[event.eventId];
      if (previous != null &&
          !deepEventEquals(previous.toJson(), event.toJson())) {
        throw const FormatException('Conflicting duplicate sync event ID');
      }
      unique[event.eventId] = event;
    }
    final events = unique.values.toList()
      ..sort((a, b) {
        final clock = a.hlc.compareTo(b.hlc);
        if (clock != 0) return clock;
        final device = a.deviceId.compareTo(b.deviceId);
        return device == 0 ? a.eventId.compareTo(b.eventId) : device;
      });
    final deletedPlaylists = <String>{};
    final removedEntries = <String>{};
    for (final event in events) {
      final p = event.payload;
      if (event.op == 'playlist.delete' && p['playlistId'] is String) {
        deletedPlaylists.add(p['playlistId']! as String);
      }
      if (event.op == 'playlist.entry.remove' &&
          p['playlistId'] is String && p['entryId'] is String) {
        removedEntries.add("${p['playlistId']}|${p['entryId']}");
      }
    }
    final playlists = <String, OdifySyncPlaylist>{};
    final likes = <String, bool>{};
    for (final event in events) {
      final p = event.payload;
      final playlistId = p['playlistId'];
      if (event.op == 'like.set') {
        final key = p['sourceKey'];
        final liked = p['liked'];
        if (key is String && key.isNotEmpty && key.length <= 2048 &&
            liked is bool) {
          likes[key] = liked;
        }
        continue;
      }
      if (playlistId is! String || playlistId.isEmpty ||
          playlistId.length > 128) continue;
      switch (event.op) {
        case 'playlist.create':
          final name = p['name'];
          if (name is! String || name.isEmpty || name.length > 200) break;
          playlists.putIfAbsent(
            playlistId, () => OdifySyncPlaylist(playlistId, name),
          );
        case 'playlist.rename':
          final name = p['name'];
          if (name is String && name.isNotEmpty && name.length <= 200 &&
              playlists.containsKey(playlistId) &&
              !deletedPlaylists.contains(playlistId)) {
            playlists[playlistId]!.name = name;
          }
        case 'playlist.delete':
          final playlist = playlists[playlistId];
          if (playlist != null) playlist.deleted = true;
        case 'playlist.entry.add':
          final playlist = playlists[playlistId];
          final entryId = p['entryId'];
          final position = p['positionKey'];
          final source = p['sourceRef'];
          if (playlist == null || deletedPlaylists.contains(playlistId) ||
              entryId is! String || entryId.isEmpty ||
              position is! String || position.isEmpty ||
              position.length > 128 ||
              removedEntries.contains('$playlistId|$entryId') ||
              source is! Map<String, Object?>) break;
          playlist.entries.putIfAbsent(
            entryId,
            () => OdifySyncEntry(
              id: entryId,
              position: position,
              source: OdifySourceRef.fromJson(source),
            ),
          );
        case 'playlist.entry.move':
          final playlist = playlists[playlistId];
          final entryId = p['entryId'];
          final position = p['positionKey'];
          if (entryId is String && position is String &&
              position.isNotEmpty && position.length <= 128 &&
              !deletedPlaylists.contains(playlistId) &&
              !removedEntries.contains('$playlistId|$entryId')) {
            final entry = playlist?.entries[entryId];
            if (entry != null) entry.position = position;
          }
        case 'playlist.entry.remove':
          final entryId = p['entryId'];
          if (entryId is String) playlists[playlistId]?.entries.remove(entryId);
      }
    }
    return OdifySyncState._(playlists, likes, events.length);
  }

  static bool deepEventEquals(
    Map<String, Object?> a,
    Map<String, Object?> b,
  ) {
    if (a.length != b.length) return false;
    for (final key in a.keys) {
      if (!b.containsKey(key)) return false;
      final x = a[key];
      final y = b[key];
      if (x is Map && y is Map) {
        if (!deepEventEquals(
          Map<String, Object?>.from(x),
          Map<String, Object?>.from(y),
        )) return false;
      } else if (x != y) {
        return false;
      }
    }
    return true;
  }

  Map<String, Object?> toJson() => {
    'playlists': [
      for (final key in playlists.keys.toList()..sort())
        {
          'id': key,
          'name': playlists[key]!.name,
          'deleted': playlists[key]!.deleted,
          'entries': [
            for (final item in playlists[key]!.orderedEntries)
              {
                'entryId': item.id,
                'positionKey': item.position,
                'sourceRef': item.source.toJson(),
              },
          ],
        },
    ],
    'likes': {for (final key in likes.keys.toList()..sort()) key: likes[key]},
  };
}
