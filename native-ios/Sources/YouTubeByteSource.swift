import Foundation
import OdifyStreaming

struct AudioHTTPFailure: Error, LocalizedError, CustomNSError {
    var statusCode: Int
    static var errorDomain: String { "com.odify.audio.http" }
    var errorCode: Int { statusCode }
    var errorDescription: String? { "YouTube가 오디오 연결을 거절했습니다 (HTTP \(statusCode)). YouTube에서 직접 재생을 확인해 주세요." }
    var errorUserInfo: [String: Any] { [NSLocalizedDescriptionKey: errorDescription ?? "YouTube 연결 오류"] }
}

/// Read the same granted public URL with one consistent HTTP client. Never
/// rewrites signatures, receives a Drive token, uses cookies or a proxy, or
/// retries an explicit authorization denial through another endpoint.
actor YouTubeByteSource {
    let length: Int64
    private let url: URL
    private let session: URLSession
    private var prefix: Data
    private let ownsSession: Bool
    private let blockSize: Int64 = 131_072
    private let cacheLimit = 8
    private var cache: [Int64: Data] = [:]
    private var recent: [Int64] = []
    private var pending: [Int64: (UUID, Task<Data, Error>)] = [:]
    private var closed = false
    private var denied: AudioHTTPFailure?

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
            let (bytes, total) = try await fetch(url, session: session, start: 0, length: 32_768, expectedSize: nil)
            return YouTubeByteSource(url: url, session: session, length: total, prefix: bytes, ownsSession: injected == nil)
        } catch { if injected == nil { session.invalidateAndCancel() }; throw error }
    }
    func close() {
        guard !closed else { return }; closed = true
        pending.values.forEach { $0.1.cancel() }; pending.removeAll()
        cache.removeAll(); recent.removeAll(); prefix = Data()
        if ownsSession { session.invalidateAndCancel() }
    }
    /// Prepare both decoding metadata and the start of a successor while the
    /// current song still provides the app's background-audio lifetime.
    func warmStart() async throws {
        _ = try await range(start: 0, length: min(blockSize, length))
        let tail = max(0, length - min(65_536, length))
        _ = try await range(start: tail, length: length - tail)
    }
    var bufferedByteCount: Int { prefix.count + cache.values.reduce(0) { $0 + $1.count } }
    func range(start: Int64, length: Int64) async throws -> Data {
        try Task.checkCancellation()
        guard !closed else { throw CancellationError() }
        if let denied { throw denied }
        guard start >= 0, length > 0, length <= 262_144, start < self.length else { throw OdifyError.message("올바른 YouTube 음원 구간이 아닙니다.") }
        let count = min(length, self.length - start)
        if start + count <= Int64(prefix.count) { return prefix.subdata(in: Int(start)..<Int(start + count)) }
        var result = Data(); result.reserveCapacity(Int(count))
        var cursor = start
        while cursor < start + count {
            let offset = cursor / blockSize * blockSize
            let bytes = try await block(at: offset)
            try Task.checkCancellation()
            guard !closed else { throw CancellationError() }
            if let denied { throw denied }
            let lower = Int(cursor - offset)
            let take = min(bytes.count - lower, Int(start + count - cursor))
            guard take > 0 else { throw OdifyError.message("YouTube 음원 구간이 비어 있습니다.") }
            result.append(bytes.subdata(in: lower..<(lower + take)))
            cursor += Int64(take)
        }
        return result
    }
    private func block(at offset: Int64) async throws -> Data {
        guard !closed else { throw CancellationError() }
        if let denied { throw denied }
        if let bytes = cache[offset] { touch(offset); return bytes }
        if let (_, task) = pending[offset] { return try await task.value }
        // At most two network blocks per source, regardless of overlapping
        // metadata/seek requests. Shared reads survive one caller's cancellation.
        while pending.count >= 2 {
            if let task = pending.values.first?.1 { _ = try? await task.value }
            try Task.checkCancellation()
            guard !closed else { throw CancellationError() }
            if let denied { throw denied }
            if let bytes = cache[offset] { touch(offset); return bytes }
            if let (_, task) = pending[offset] { return try await task.value }
            // Let the completed block's owner commit/clear its pending entry.
            await Task.yield()
        }
        let token = UUID(), count = min(blockSize, length - offset)
        let seed = offset == 0 ? prefix : Data()
        let url = self.url, session = self.session, total = length
        let task = Task<Data, Error> {
            if Int64(seed.count) == count { return seed }
            let rest = try await Self.fetch(url, session: session, start: offset + Int64(seed.count),
                                            length: count - Int64(seed.count), expectedSize: total).0
            var bytes = seed; bytes.append(rest); return bytes
        }
        pending[offset] = (token, task)
        do {
            let bytes = try await task.value
            guard !closed else { throw CancellationError() }
            if let denied { throw denied }
            if pending[offset]?.0 == token { pending.removeValue(forKey: offset) }
            cache[offset] = bytes; touch(offset)
            while recent.count > cacheLimit { cache.removeValue(forKey: recent.removeFirst()) }
            return bytes
        } catch {
            if pending[offset]?.0 == token { pending.removeValue(forKey: offset) }
            if let failure = error as? AudioHTTPFailure {
                denied = failure
                pending.values.forEach { $0.1.cancel() }; pending.removeAll()
                if ownsSession { session.invalidateAndCancel() }
            }
            if let denied { throw denied }
            throw error
        }
    }
    private func touch(_ offset: Int64) {
        recent.removeAll { $0 == offset }; recent.append(offset)
    }
    private static func fetch(_ url: URL, session: URLSession, start: Int64, length: Int64, expectedSize: Int64?) async throws -> (Data, Int64) {
        try Task.checkCancellation()
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 8)
        request.httpShouldHandleCookies = false
        request.setValue(YouTubeAudio.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("bytes=\(start)-\(start + length - 1)", forHTTPHeaderField: "Range")
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        for attempt in 0..<2 {
            do { return try await AudioRangeTransfer(start: start, length: length, expectedSize: expectedSize).receive(request, session: session) }
            catch {
                try Task.checkCancellation()
                guard attempt == 0, let network = error as? URLError,
                      [.timedOut, .networkConnectionLost, .cannotConnectToHost].contains(network.code) else { throw error }
                try await Task.sleep(for: .milliseconds(250))
            }
        }
        throw URLError(.timedOut)
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
