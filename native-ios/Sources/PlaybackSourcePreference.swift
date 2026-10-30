import Foundation

enum PlaybackSourcePreference {
    static func resolve(_ requested: Track, library: [Track], account: String?, documents: URL) -> Track {
        guard requested.source == .youtube, !Track.normalized(requested.title).isEmpty,
              !Track.normalized(requested.artist).isEmpty else { return requested }
        let candidates = library.filter { track in
            guard track.recordingKey == requested.recordingKey,
                  requested.duration <= 0 || track.duration <= 0 || abs(track.duration - requested.duration) <= 6 else { return false }
            if let file = track.localFile {
                let root = documents.standardizedFileURL
                let url = root.appendingPathComponent(file).standardizedFileURL
                return url.path.hasPrefix(root.path + "/") && FileManager.default.fileExists(atPath: url.path)
            }
            return track.source == .drive && account != nil && track.driveAccountHash == account &&
                track.driveFileID.map(DriveClient.validID) == true && (track.fileSize ?? 0) > 0
        }
        let exactAlbum = candidates.filter { !requested.album.isEmpty && Track.normalized($0.album) == Track.normalized(requested.album) }
        let pool = exactAlbum.isEmpty ? candidates : exactAlbum
        let local = pool.filter { $0.localFile != nil }
        let preferred = local.isEmpty ? pool : local
        // Multiple editions must not silently replace the chosen recording.
        guard preferred.count == 1, var result = preferred.first else { return requested }
        if result.artwork == nil { result.artwork = requested.artwork }
        if result.lyrics?.isEmpty != false { result.lyrics = requested.lyrics }
        return result
    }
}
