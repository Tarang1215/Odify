import Foundation
import UniformTypeIdentifiers

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
                     fileExtension: (name as NSString).pathExtension.lowercased(), driveModifiedTime: modifiedTime)
    }
}

struct DrivePage: Decodable { var files: [DriveFile]; var nextPageToken: String? }

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
    typealias Token = @Sendable (String) async throws -> String
    private let session: URLSession
    private let token: Token
    init(session: URLSession? = nil, token: @escaping Token = { try await DriveAccount.shared.token(expectedAccount: $0) }) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 25; configuration.timeoutIntervalForResource = 90
        configuration.urlCache = nil
        self.session = session ?? URLSession(configuration: configuration, delegate: DriveRedirects(), delegateQueue: nil)
        self.token = token
    }
    static func validID(_ id: String) -> Bool { id.range(of: #"^[A-Za-z0-9_-]{1,200}$"#, options: .regularExpression) != nil }
    static func listingURL(parent: String, page: String? = nil, shared: Bool = false) throws -> URL {
        guard validID(parent) else { throw OdifyError.message("올바른 Drive 폴더가 아닙니다.") }
        var parameters = ["q": (shared ? "sharedWithMe" : "'\(parent)' in parents") + " and trashed=false",
                          "fields": "nextPageToken,files(id,name,mimeType,size,modifiedTime,capabilities(canDownload))",
                          "pageSize": "100", "orderBy": "folder,name_natural", "supportsAllDrives": "true", "includeItemsFromAllDrives": "true"]
        if let page { parameters["pageToken"] = page }
        return MusicHTTP.url("https://www.googleapis.com/drive/v3/files", parameters)
    }
    func list(parent: String = "root", page: String? = nil, account: String, shared: Bool = false) async throws -> DrivePage {
        var request = URLRequest(url: try Self.listingURL(parent: parent, page: page, shared: shared))
        request.setValue("Bearer " + (try await token(account)), forHTTPHeaderField: "Authorization")
        let (data, response) = try await session.data(for: request)
        try Self.check(response)
        guard data.count < 4_000_000 else { throw OdifyError.message("폴더 목록이 너무 큽니다.") }
        return try JSONDecoder().decode(DrivePage.self, from: data)
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
