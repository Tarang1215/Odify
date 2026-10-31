import Foundation

struct MasterRecording: Hashable {
    var code: String
    var title: String
    var artist: String
    var album: String
    var driveID: String?
}

/// Matches Android's read-only MASTER correction, using an exact file ID or
/// bounded ODF code. An arbitrary matching title never establishes ownership.
enum MasterCatalog {
    static func code(in name: String) -> String? {
        guard let range = name.range(of: #"^ODF[0-9]{6}(?=$|[ ._\-])"#, options: [.regularExpression, .caseInsensitive]) else { return nil }
        return String(name[range]).uppercased()
    }
    static func parse(_ data: Data) throws -> [MasterRecording] {
        guard data.count <= 9_000_000, var text = String(data: data, encoding: .utf8) else { throw OdifyError.message("MASTER 표를 읽지 못했습니다.") }
        if text.hasPrefix("\u{FEFF}") { text.removeFirst() }
        let chars = Array(text)
        var rows: [[String]] = [], row: [String] = [], cell = "", quoted = false, i = 0
        while i < chars.count {
            let c = chars[i]
            if c == "\"" {
                if quoted, i + 1 < chars.count, chars[i + 1] == "\"" { cell.append("\""); i += 1 }
                else { quoted.toggle() }
            } else if c == ",", !quoted { row.append(cell); cell = "" }
            else if (c == "\n" || c == "\r" || c == "\r\n"), !quoted {
                row.append(cell); rows.append(row); row = []; cell = ""
                if c == "\r", i + 1 < chars.count, chars[i + 1] == "\n" { i += 1 }
            } else { cell.append(c) }
            i += 1
        }
        guard !quoted else { throw OdifyError.message("MASTER CSV의 따옴표를 확인해 주세요.") }
        if !cell.isEmpty || !row.isEmpty { row.append(cell); rows.append(row) }
        guard let headers = rows.first, ["Track ID", "곡명", "아티스트"].allSatisfy(headers.contains) else {
            throw OdifyError.message("음악 마스터의 첫 번째 시트가 MASTER인지 확인해 주세요.")
        }
        func value(_ cells: [String], _ header: String) -> String {
            guard let column = headers.firstIndex(of: header), cells.indices.contains(column) else { return "" }
            return cells[column].trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return rows.dropFirst().prefix(100_000).compactMap { cells in
            let code = value(cells, "Track ID").uppercased(), title = value(cells, "곡명"), artist = value(cells, "아티스트")
            guard code.range(of: #"^ODF[0-9]{6}$"#, options: .regularExpression) != nil, !title.isEmpty, !artist.isEmpty else { return nil }
            let address = value(cells, "Drive 파일 URL")
            var fileID: String?
            if let url = URLComponents(string: address), ["drive.google.com", "docs.google.com"].contains(url.host ?? "") {
                let parts = url.path.split(separator: "/")
                if let d = parts.firstIndex(of: "d"), parts.indices.contains(d + 1) { fileID = String(parts[d + 1]) }
                else { fileID = url.queryItems?.first(where: { $0.name == "id" })?.value }
            }
            if fileID.map(DriveClient.validID) != true { fileID = nil }
            return MasterRecording(code: code, title: title, artist: artist, album: value(cells, "앨범"), driveID: fileID)
        }
    }
    static func apply(_ records: [MasterRecording], to tracks: [Track], account: String) -> [Track] {
        func unambiguous(_ groups: [String: [MasterRecording]]) -> [String: MasterRecording] {
            groups.compactMapValues { list in let unique = Set(list); return unique.count == 1 ? unique.first : nil }
        }
        let byCode = unambiguous(Dictionary(grouping: records, by: \.code))
        let byID = unambiguous(Dictionary(grouping: records.filter { $0.driveID != nil }, by: { $0.driveID! }))
        return tracks.map { original in
            guard original.metadataOrigin != "user", original.playbackSource != .youtube else { return original }
            if original.driveFileID != nil, original.driveAccountHash != account { return original }
            let filename = original.originalFilename ?? original.title
            let coded = code(in: filename).flatMap { byCode[$0] }
            let identified = original.driveFileID.flatMap { byID[$0] }
            if let coded, let identified, coded != identified { return original }
            guard let record = identified ?? coded else { return original }
            var result = original.routedForPlayback
            if result.originalFilename == nil, code(in: filename) != nil { result.originalFilename = filename }
            result.title = record.title; result.artist = record.artist; result.album = record.album
            result.metadataOrigin = "master"
            return result
        }
    }
}
