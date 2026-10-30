import Foundation
import OdifyStreaming

struct AudioHTTPFailure: Error, LocalizedError, CustomNSError {
    var statusCode: Int
    static var errorDomain: String { "com.odify.audio.http" }
    var errorCode: Int { statusCode }
    var errorDescription: String? { "YouTube 음원 서버가 연결을 허용하지 않았습니다 (HTTP \(statusCode))." }
    var errorUserInfo: [String: Any] { [NSLocalizedDescriptionKey: errorDescription ?? "YouTube 연결 오류"] }
}

/// Read the same granted public URL with one consistent HTTP client. Never
/// rewrites signatures, receives a Drive token, uses cookies or a proxy, or
/// retries an explicit authorization denial through another endpoint.
actor YouTubeByteSource {
    let length: Int64
    private let url: URL
    private let session: URLSession
    private let prefix: Data
    private let ownsSession: Bool

    private init(url: URL, session: URLSession, length: Int64, prefix: Data, ownsSession: Bool) {
        self.url = url; self.session = session; self.length = length; self.prefix = prefix; self.ownsSession = ownsSession
    }
    static func trusted(_ url: URL) -> Bool {
        url.scheme == "https" && url.host?.hasSuffix(".googlevideo.com") == true && url.user == nil && url.password == nil
    }
    static func open(_ url: URL, session injected: URLSession? = nil) async throws -> YouTubeByteSource {
        guard trusted(url) else { throw OdifyError.message("올바른 YouTube 음원 주소가 아닙니다.") }
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil; config.httpShouldSetCookies = false; config.urlCache = nil
        config.timeoutIntervalForRequest = 15; config.timeoutIntervalForResource = 30
        let session = injected ?? URLSession(configuration: config, delegate: AudioRedirects(), delegateQueue: nil)
        do {
            let (bytes, total) = try await fetch(url, session: session, start: 0, length: 1024, expectedSize: nil)
            return YouTubeByteSource(url: url, session: session, length: total, prefix: bytes, ownsSession: injected == nil)
        } catch { if injected == nil { session.invalidateAndCancel() }; throw error }
    }
    func close() { if ownsSession { session.invalidateAndCancel() } }
    func range(start: Int64, length: Int64) async throws -> Data {
        guard start >= 0, length > 0, length <= 262_144, start < self.length else { throw OdifyError.message("올바른 YouTube 음원 구간이 아닙니다.") }
        let count = min(length, self.length - start)
        if start + count <= Int64(prefix.count) { return prefix.subdata(in: Int(start)..<Int(start + count)) }
        return try await Self.fetch(url, session: session, start: start, length: count, expectedSize: self.length).0
    }
    private static func fetch(_ url: URL, session: URLSession, start: Int64, length: Int64, expectedSize: Int64?) async throws -> (Data, Int64) {
        try Task.checkCancellation()
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        request.httpShouldHandleCookies = false
        request.setValue(YouTubeAudio.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("bytes=\(start)-\(start + length - 1)", forHTTPHeaderField: "Range")
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        let (stream, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw OdifyError.message("YouTube 음원 응답이 없습니다.") }
        if [401, 403, 429].contains(http.statusCode) {
            throw AudioHTTPFailure(statusCode: http.statusCode)
        }
        guard http.statusCode == 206,
              let header = http.value(forHTTPHeaderField: "Content-Range"), let range = DriveByteRange.parse(header),
              range.start == start, range.end == min(range.total - 1, start + length - 1),
              expectedSize == nil || range.total == expectedSize else {
            throw OdifyError.message("YouTube 음원 구간 응답을 확인하지 못했습니다 (HTTP \(http.statusCode)).")
        }
        let mime = http.mimeType?.lowercased() ?? ""
        guard mime == "audio/mp4" || mime == "audio/x-m4a" || mime == "application/octet-stream" else {
            throw OdifyError.message("YouTube 서버가 오디오 대신 다른 응답을 보냈습니다.")
        }
        let count = Int(range.end - range.start + 1)
        var data = Data(); data.reserveCapacity(count)
        for try await byte in stream {
            try Task.checkCancellation()
            guard data.count < count else { throw OdifyError.message("YouTube 음원 구간 길이가 일치하지 않습니다.") }
            data.append(byte)
        }
        guard data.count == count else { throw OdifyError.message("YouTube 음원 연결이 중단되었습니다.") }
        return (data, range.total)
    }
}

private final class AudioRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard let url = request.url, YouTubeByteSource.trusted(url) else { completionHandler(nil); return }
        var safe = request
        safe.setValue(nil, forHTTPHeaderField: "Authorization"); safe.setValue(nil, forHTTPHeaderField: "Cookie")
        safe.setValue(YouTubeAudio.userAgent, forHTTPHeaderField: "User-Agent")
        completionHandler(safe)
    }
}
