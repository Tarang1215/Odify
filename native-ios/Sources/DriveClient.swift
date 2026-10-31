import Foundation
import UniformTypeIdentifiers
import OSLog

struct DriveFile: Identifiable, Decodable, Hashable {
    var id: String
    var name: String
    var mimeType: String
    var size: String?
    var capabilities: Capabilities?
    var modifiedTime: String?
    struct Capabilities: Decodable, Hashable { var canDownload: Bool? }
    var isFolder: Bool { mimeType == "application/vnd.google-apps.folder" }
    var playable: Bool {
        !isFolder && capabilities?.canDownload != false &&
        (mimeType.hasPrefix("audio/") || ["mp3", "m4a", "aac", "wav", "aiff", "aif", "flac", "caf", "alac", "mp4"].contains((name as NSString).pathExtension.lowercased()))
    }
    func track(account: String) -> Track {
        let stem = (name as NSString).deletingPathExtension
        let parts = stem.components(separatedBy: " - ")
        return Track(id: "drive:\(account):\(id)", title: parts.count == 2 ? parts[1] : stem,
                     artist: parts.count == 2 ? parts[0] : "Google Drive", source: .drive,
                     driveFileID: id, driveAccountHash: account, fileSize: size.flatMap(Int64.init), mimeType: mimeType,
                     fileExtension: (name as NSString).pathExtension.lowercased(), driveModifiedTime: modifiedTime, originalFilename: name)
    }
}

struct DrivePage: Decodable { var files: [DriveFile]; var nextPageToken: String? }
private struct DriveAbout: Decodable { struct User: Decodable { var emailAddress: String? }; var user: User? }

struct DriveByteRange: Equatable {
    let start: Int64
    let end: Int64
    let total: Int64
    static func parse(_ value: String) -> DriveByteRange? {
        let parts = value.replacingOccurrences(of: "bytes ", with: "").split(whereSeparator: { $0 == "-" || $0 == "/" })
        guard value.hasPrefix("bytes "), parts.count == 3, let start = Int64(parts[0]), let end = Int64(parts[1]), let total = Int64(parts[2]),
              start >= 0, end >= start, total > end else { return nil }
        return DriveByteRange(start: start, end: end, total: total)
    }
}

/// A redirect never carries a user's bearer token to another host.
final class DriveRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(Self.safeRedirect(from: response.url, request: request))
    }
    static func safeRedirect(from previous: URL?, request: URLRequest) -> URLRequest? {
        guard let url = request.url, url.scheme == "https", let host = url.host,
              host == "www.googleapis.com" || host.hasSuffix(".googleusercontent.com") else { return nil }
        var safe = request
        if previous?.host != host { safe.setValue(nil, forHTTPHeaderField: "Authorization") }
        return safe
    }
}

actor DriveClient {
    static let shared = DriveClient()
    private var masterCache: [String: (Date, [MasterRecording])] = [:]
    typealias Token = @Sendable (String) async throws -> String
    private let session: URLSession
    private let token: Token
    private static let log = Logger(subsystem: "com.odify.music.ios", category: "Drive")
    static let libraryRootNames = ["Odify_Music_Test", "odify_music_test", "Odify_Auto_Upload", "odify_auto_upload", "Odify_Cloud_Uploads"]
    init(session: URLSession? = nil, token: @escaping Token = { try await DriveAccount.shared.token(expectedAccount: $0) }) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 25; configuration.timeoutIntervalForResource = 90
        configuration.urlCache = nil
        self.session = session ?? URLSession(configuration: configuration, delegate: DriveRedirects(), delegateQueue: nil)
        self.token = token
    }
    static func validID(_ id: String) -> Bool { id.range(of: #"^[A-Za-z0-9_-]{1,200}$"#, options: .regularExpression) != nil }
    static func listingURL(parent: String, page: String? = nil, shared: Bool = false, musicRoots: Bool = false, allMusic: Bool = false) throws -> URL {
        guard validID(parent) else { throw OdifyError.message("올바른 Drive 폴더가 아닙니다.") }
        let query: String
        if allMusic { query = "mimeType contains 'audio/'" }
        else if musicRoots {
            let names = libraryRootNames.map { "name = '\($0)'" }.joined(separator: " or ")
            query = "mimeType = 'application/vnd.google-apps.folder' and (\(names))"
        } else { query = shared ? "sharedWithMe" : "'\(parent)' in parents" }
        var parameters = ["q": query + " and trashed=false",
                          "fields": "nextPageToken,files(id,name,mimeType,size,modifiedTime,capabilities(canDownload))",
                          "pageSize": "100", "orderBy": "folder,name_natural", "spaces": "drive", "corpora": "user", "supportsAllDrives": "true", "includeItemsFromAllDrives": "true"]
        if let page { parameters["pageToken"] = page }
        return MusicHTTP.url("https://www.googleapis.com/drive/v3/files", parameters)
    }
    func list(parent: String = "root", page: String? = nil, account: String, shared: Bool = false, musicRoots: Bool = false, allMusic: Bool = false) async throws -> DrivePage {
        var request = URLRequest(url: try Self.listingURL(parent: parent, page: page, shared: shared, musicRoots: musicRoots, allMusic: allMusic), cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("Bearer " + (try await token(account)), forHTTPHeaderField: "Authorization")
        let (data, response) = try await session.data(for: request)
        try Self.check(response)
        guard data.count < 4_000_000 else { throw OdifyError.message("폴더 목록이 너무 큽니다.") }
        let result = try JSONDecoder().decode(DrivePage.self, from: data)
        Self.log.notice("[OdifyDrive] listing all-audio=\(allMusic, privacy: .public) roots=\(musicRoots, privacy: .public) shared=\(shared, privacy: .public) files=\(result.files.count, privacy: .public) folders=\(result.files.filter(\.isFolder).count, privacy: .public) audio=\(result.files.filter(\.playable).count, privacy: .public) more=\(result.nextPageToken != nil, privacy: .public)")
        return result
    }
    func masterRecordings(account: String, refresh: Bool = false) async throws -> [MasterRecording] {
        if !refresh, let (date, rows) = masterCache[account], Date().timeIntervalSince(date) < 3600 { return rows }
        var page: String?, seen = Set<String>(), sheets: [DriveFile] = []
        repeat {
            try Task.checkCancellation()
            guard seen.count < 10, seen.insert(page ?? "").inserted else { throw OdifyError.message("MASTER 표 조회 범위를 초과했습니다.") }
            var parameters = ["q": "mimeType = 'application/vnd.google-apps.spreadsheet' and name contains 'Odify' and name contains '음악 마스터' and trashed = false",
                              "fields": "nextPageToken,files(id,name,mimeType)", "pageSize": "100", "spaces": "drive",
                              "supportsAllDrives": "true", "includeItemsFromAllDrives": "true"]
            if let page { parameters["pageToken"] = page }
            var request = URLRequest(url: MusicHTTP.url("https://www.googleapis.com/drive/v3/files", parameters), timeoutInterval: 20)
            request.setValue("Bearer " + (try await token(account)), forHTTPHeaderField: "Authorization")
            let (data, response) = try await session.data(for: request); try Self.check(response)
            guard data.count < 1_000_000 else { throw OdifyError.message("MASTER 표 목록이 너무 큽니다.") }
            let result = try JSONDecoder().decode(DrivePage.self, from: data)
            sheets += result.files.filter { $0.mimeType == "application/vnd.google-apps.spreadsheet" && $0.name.contains("음악 마스터") }
            page = result.nextPageToken
        } while page != nil
        guard sheets.count <= 1 else { throw OdifyError.message("Odify 음악 마스터 표가 여러 개입니다. 사용할 표를 하나로 정리한 뒤 다시 연결해 주세요.") }
        guard let sheet = sheets.first else { masterCache[account] = (Date(), []); return [] }
        guard Self.validID(sheet.id) else { throw OdifyError.message("MASTER 표 주소를 확인하지 못했습니다.") }
        var request = URLRequest(url: MusicHTTP.url("https://www.googleapis.com/drive/v3/files/\(sheet.id)/export", ["mimeType": "text/csv"]), timeoutInterval: 25)
        request.setValue("Bearer " + (try await token(account)), forHTTPHeaderField: "Authorization")
        let (data, response) = try await session.data(for: request); try Self.check(response)
        try Task.checkCancellation()
        let rows = try MasterCatalog.parse(data)
        if masterCache.count >= 4 { masterCache.removeAll() }
        masterCache[account] = (Date(), rows)
        return rows
    }
    func confirmAccount(account: String, expectedEmail: String?) async throws {
        guard let expectedEmail, !expectedEmail.isEmpty else { return }
        var request = URLRequest(url: MusicHTTP.url("https://www.googleapis.com/drive/v3/about", ["fields": "user(emailAddress)"]), cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("Bearer " + (try await token(account)), forHTTPHeaderField: "Authorization")
        let (data, response) = try await session.data(for: request); try Self.check(response)
        guard data.count < 20_000, let actual = try JSONDecoder().decode(DriveAbout.self, from: data).user?.emailAddress,
              actual.caseInsensitiveCompare(expectedEmail) == .orderedSame else {
            Self.log.error("[OdifyDrive] account-verification-mismatch")
            throw OdifyError.message("Google 로그인 계정과 Drive 연결 계정이 일치하지 않습니다. 연결을 해제한 뒤 음악이 있는 계정으로 다시 연결해 주세요.")
        }
        Self.log.notice("[OdifyDrive] account-verified")
    }
    private func allAudio(account: String) async throws -> [Track] {
        var tracks: [Track] = [], page: String?, pages = Set<String>(), ids = Set<String>()
        repeat {
            try Task.checkCancellation()
            guard pages.insert(page ?? "").inserted else { throw OdifyError.message("Drive 음원 페이지가 반복되었습니다.") }
            let result = try await list(page: page, account: account, allMusic: true)
            tracks += result.files.filter { $0.playable && ids.insert($0.id).inserted }.map { $0.track(account: account) }
            guard tracks.count <= 50_000 else { throw OdifyError.message("보관함의 음원 수 제한을 초과했습니다.") }
            page = result.nextPageToken
        } while page != nil
        return tracks
    }
    /// Match Android's explicit Odify music roots. Never recursively scan the
    /// rest of the account; only folder metadata and playable audio are indexed.
    func musicLibrary(account: String) async throws -> [Track] {
        var folders: [String] = []
        var rootPage: String?, rootPages = Set<String>()
        repeat {
            try Task.checkCancellation()
            guard rootPages.insert(rootPage ?? "").inserted else { throw OdifyError.message("Drive 폴더 페이지가 반복되었습니다.") }
            let result = try await list(page: rootPage, account: account, musicRoots: true)
            folders += result.files.filter(\.isFolder).map(\.id)
            guard folders.count <= 4_000 else { throw OdifyError.message("음악 폴더 수가 너무 많습니다.") }
            rootPage = result.nextPageToken
        } while rootPage != nil
        if folders.isEmpty { return try await allAudio(account: account) }
        var visited = Set<String>(), songs = Set<String>(), tracks: [Track] = [], cursor = 0
        while cursor < folders.count {
            let parent = folders[cursor]; cursor += 1
            guard visited.insert(parent).inserted else { continue }
            guard visited.count <= 4_000 else { throw OdifyError.message("음악 폴더 수가 너무 많습니다.") }
            var page: String?, pages = Set<String>()
            repeat {
                try Task.checkCancellation()
                guard pages.insert(page ?? "").inserted else { throw OdifyError.message("Drive 음원 페이지가 반복되었습니다.") }
                let result = try await list(parent: parent, page: page, account: account)
                for file in result.files {
                    if file.isFolder { folders.append(file.id) }
                    else if file.playable, songs.insert(file.id).inserted { tracks.append(file.track(account: account)) }
                }
                guard tracks.count <= 50_000 else { throw OdifyError.message("보관함의 음원 수 제한을 초과했습니다.") }
                page = result.nextPageToken
            } while page != nil
        }
        Self.log.notice("[OdifyDrive] library-scan-complete folders=\(visited.count, privacy: .public) audio=\(tracks.count, privacy: .public)")
        return tracks.isEmpty ? try await allAudio(account: account) : tracks
    }
    func range(fileID: String, account: String, start: Int64, length: Int64, expectedSize: Int64) async throws -> Data {
        guard Self.validID(fileID), start >= 0, length > 0, length <= 524_288, expectedSize > start else {
            throw OdifyError.message("올바른 음원 구간이 아닙니다.")
        }
        let end = min(expectedSize - 1, start + length - 1)
        var request = URLRequest(url: MusicHTTP.url("https://www.googleapis.com/drive/v3/files/\(fileID)", ["alt": "media", "supportsAllDrives": "true"]))
        request.setValue("Bearer " + (try await token(account)), forHTTPHeaderField: "Authorization")
        request.setValue("bytes=\(start)-\(end)", forHTTPHeaderField: "Range")
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        let (bytes, response) = try await session.bytes(for: request)
        try Self.check(response)
        guard let http = response as? HTTPURLResponse else { throw OdifyError.message("Drive 음원 응답이 없습니다.") }
        if http.statusCode == 206 {
            guard let value = http.value(forHTTPHeaderField: "Content-Range"), let returned = DriveByteRange.parse(value),
                  returned.start == start, returned.end == end, returned.total == expectedSize else {
                throw OdifyError.message("Drive 파일이 변경되었거나 구간 정보를 확인하지 못했습니다. 목록을 새로고침해 주세요.")
            }
        } else if !(http.statusCode == 200 && start == 0 && expectedSize <= length) {
            throw OdifyError.message("이 파일은 구간 스트리밍 응답을 지원하지 않습니다.")
        }
        let expected = Int(end - start + 1)
        var data = Data(); data.reserveCapacity(expected)
        for try await byte in bytes {
            try Task.checkCancellation()
            guard data.count < expected else { throw OdifyError.message("잘못된 Drive 음원 응답입니다.") }
            data.append(byte)
        }
        guard data.count == expected else { throw OdifyError.message("Drive 음원 연결이 중단되었습니다. 다시 시도해 주세요.") }
        return data
    }
    private static func check(_ response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { throw OdifyError.message("Drive에 연결하지 못했습니다.") }
        switch http.statusCode {
        case 200..<300: return
        case 401: throw OdifyError.message("Google 로그인이 만료되었습니다. Drive에서 다시 연결해 주세요.")
        case 403: throw OdifyError.message("Drive 읽기 권한이나 이 파일의 재생 권한을 확인해 주세요.")
        case 404: throw OdifyError.message("Drive 파일이 이동되었거나 삭제되었습니다.")
        default: throw OdifyError.message("Drive 연결 오류 (\(http.statusCode)). 잠시 후 다시 시도해 주세요.")
        }
    }
}
