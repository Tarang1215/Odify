import Foundation
import OdifyStreaming

/// Collect bounded HTTP chunks instead of scheduling Swift work for each byte.
/// A task-specific delegate keeps injected test sessions and normal sessions on
/// the same code path. Header/body checks happen before retaining response data.
final class AudioRangeTransfer: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let start: Int64
    private let length: Int64
    private let expectedSize: Int64?
    private let lock = NSLock()
    private var continuation: CheckedContinuation<(Data, Int64), Error>?
    private var task: URLSessionDataTask?
    private var finished = false
    private var data = Data()
    private var total: Int64?
    private var expectedCount = 0

    init(start: Int64, length: Int64, expectedSize: Int64?) {
        self.start = start; self.length = length; self.expectedSize = expectedSize
    }
    func receive(_ request: URLRequest, session: URLSession) async throws -> (Data, Int64) {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                begin(request, session: session, continuation: continuation)
            }
        } onCancel: { self.finish(.failure(CancellationError()), cancel: true) }
    }
    private func begin(_ request: URLRequest, session: URLSession,
                       continuation: CheckedContinuation<(Data, Int64), Error>) {
        lock.lock()
        guard !finished else { lock.unlock(); continuation.resume(throwing: CancellationError()); return }
        self.continuation = continuation
        let task = session.dataTask(with: request)
        task.delegate = self
        task.countOfBytesClientExpectsToReceive = length
        self.task = task
        lock.unlock()
        task.resume()
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        do {
            let range = try validated(response)
            lock.lock()
            guard !finished else { lock.unlock(); completionHandler(.cancel); return }
            total = range.total; expectedCount = Int(range.end - range.start + 1)
            data.reserveCapacity(expectedCount)
            lock.unlock()
            completionHandler(.allow)
        } catch {
            finish(.failure(error), cancel: true); completionHandler(.cancel)
        }
    }
    private func validated(_ response: URLResponse) throws -> DriveByteRange {
        guard let http = response as? HTTPURLResponse else { throw OdifyError.message("YouTube 음원 응답이 없습니다.") }
        if [401, 403, 429].contains(http.statusCode) { throw AudioHTTPFailure(statusCode: http.statusCode) }
        guard http.statusCode == 206,
              let header = http.value(forHTTPHeaderField: "Content-Range"), let range = DriveByteRange.parse(header),
              range.start == start, range.end == min(range.total - 1, start + length - 1),
              expectedSize == nil || range.total == expectedSize,
              range.end - range.start + 1 <= 262_144 else {
            throw OdifyError.message("YouTube 음원 구간 응답을 확인하지 못했습니다 (HTTP \(http.statusCode)).")
        }
        let mime = http.mimeType?.lowercased() ?? ""
        guard ["audio/mp4", "audio/x-m4a", "application/octet-stream"].contains(mime) else {
            throw OdifyError.message("YouTube 서버가 오디오 대신 다른 응답을 보냈습니다.")
        }
        let count = range.end - range.start + 1
        guard response.expectedContentLength < 0 || response.expectedContentLength == count else {
            throw OdifyError.message("YouTube 음원 구간 길이가 일치하지 않습니다.")
        }
        return range
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive bytes: Data) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        guard total != nil, bytes.count <= expectedCount - data.count else {
            lock.unlock()
            finish(.failure(OdifyError.message("YouTube 음원 구간 길이가 일치하지 않습니다.")), cancel: true)
            return
        }
        data.append(bytes)
        lock.unlock()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { finish(.failure(error)); return }
        lock.lock()
        let bytes = data, total = self.total, complete = data.count == expectedCount
        lock.unlock()
        guard let total, complete else {
            finish(.failure(OdifyError.message("YouTube 음원 연결이 중단되었습니다."))); return
        }
        finish(.success((bytes, total)))
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard let url = request.url, YouTubeByteSource.trusted(url) else { completionHandler(nil); return }
        var safe = request
        safe.setValue(nil, forHTTPHeaderField: "Authorization"); safe.setValue(nil, forHTTPHeaderField: "Cookie")
        safe.setValue(YouTubeAudio.userAgent, forHTTPHeaderField: "User-Agent")
        safe.setValue("bytes=\(start)-\(start + length - 1)", forHTTPHeaderField: "Range")
        safe.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        completionHandler(safe)
    }
    private func finish(_ result: Result<(Data, Int64), Error>, cancel: Bool = false) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        let continuation = self.continuation, task = self.task
        self.continuation = nil; self.task = nil; data = Data()
        lock.unlock()
        if cancel { task?.cancel() }
        continuation?.resume(with: result)
    }
}
