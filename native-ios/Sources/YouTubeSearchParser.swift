import Foundation

/// Public search metadata has different renderers on desktop, iPad and mobile.
/// Parse recording cards only; arbitrary watch endpoints include ads/playlists.
enum YouTubeSearchParser {
    static func queries(for track: Track) -> [String] {
        let base = String((track.artist + " " + track.title).trimmingCharacters(in: .whitespacesAndNewlines).prefix(160))
        return [base + " official audio", base + " lyrics"]
    }

    static func videos(in json: Any) -> [VideoResult] {
        // Mobile ytInitialData can itself be a serialized JSON string.
        if let value = json as? String, let data = value.data(using: .utf8),
           let object = try? JSONSerialization.jsonObject(with: data), !(object is String) {
            return videos(in: object)
        }
        var results: [VideoResult] = [], seen = Set<String>()
        func text(_ node: Any?) -> String {
            guard let dict = node as? [String: Any] else { return "" }
            return dict["simpleText"] as? String ?? dict["content"] as? String ??
                (dict["runs"] as? [[String: Any]])?.compactMap { $0["text"] as? String }.joined() ?? ""
        }
        func append(id: String?, title: String, channel: String) {
            guard let id, id.range(of: #"^[A-Za-z0-9_-]{11}$"#, options: .regularExpression) != nil,
                  !title.isEmpty, seen.insert(id).inserted else { return }
            results.append(VideoResult(id: id, title: title, channel: channel))
        }
        let rendererKeys = ["videoRenderer", "videoWithContextRenderer", "compactVideoRenderer", "gridVideoRenderer"]
        func visit(_ node: Any) {
            guard results.count < 30 else { return }
            if let rows = node as? [Any] { rows.forEach(visit) }
            else if let dict = node as? [String: Any] {
                // These cards all expose videoId, but mobile uses headline and
                // shortBylineText rather than title and ownerText.
                for key in rendererKeys {
                    if let row = dict[key] as? [String: Any] {
                        append(id: row["videoId"] as? String,
                               title: text(row["title"] ?? row["headline"]),
                               channel: text(row["ownerText"] ?? row["longBylineText"] ?? row["shortBylineText"]))
                    }
                }
                if let row = dict["lockupViewModel"] as? [String: Any],
                   row["contentType"] as? String == "LOCKUP_CONTENT_TYPE_VIDEO",
                   let metadata = (row["metadata"] as? [String: Any])?["lockupMetadataViewModel"] as? [String: Any] {
                    let container = (metadata["metadata"] as? [String: Any])?["contentMetadataViewModel"] as? [String: Any]
                    let rows = container?["metadataRows"] as? [[String: Any]] ?? []
                    let parts = rows.first?["metadataParts"] as? [[String: Any]] ?? []
                    append(id: row["contentId"] as? String, title: text(metadata["title"]), channel: text(parts.first?["text"]))
                }
                // Never crawl menus or ads inside an already parsed card.
                for key in dict.keys.sorted() where !rendererKeys.contains(key) && key != "lockupViewModel" &&
                    key != "adSlotRenderer" && key != "promotedSparklesWebRenderer" {
                    if let child = dict[key] { visit(child) }
                }
            }
        }
        visit(json)
        return results
    }
}
