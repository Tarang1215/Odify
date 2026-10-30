import AVFoundation
import UniformTypeIdentifiers

/// AVFoundation asks for only the byte ranges needed for playback, metadata or
/// seeking. Each HTTP request gets a fresh SDK-managed authorization header.
final class DriveResourceLoader: NSObject, AVAssetResourceLoaderDelegate {
    let queue = DispatchQueue(label: "com.odify.drive.media")
    typealias Reader = @Sendable (Int64, Int64) async throws -> Data
    private let total: Int64
    private let contentType: String
    private let read: Reader
    private var pending: [ObjectIdentifier: Task<Void, Never>] = [:]
    convenience init(track: Track, client: DriveClient = .shared) {
        self.init(length: track.fileSize ?? 0,
                  contentType: UTType(filenameExtension: track.fileExtension ?? "")?.identifier
                    ?? UTType(mimeType: track.mimeType ?? "audio/mpeg")?.identifier ?? UTType.mp3.identifier) { start, count in
            guard let file = track.driveFileID, let account = track.driveAccountHash, let size = track.fileSize else {
                throw OdifyError.message("Drive에서 곡을 다시 선택해 주세요.")
            }
            return try await client.range(fileID: file, account: account, start: start, length: count, expectedSize: size)
        }
    }
    init(length: Int64, contentType: String, read: @escaping Reader) {
        total = length; self.contentType = contentType; self.read = read
    }
    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest) -> Bool {
        guard total > 0 else {
            request.finishLoading(with: OdifyError.message("Drive에서 파일 목록을 새로고침한 뒤 다시 선택해 주세요.")); return false
        }
        let key = ObjectIdentifier(request)
        let dataRequest = request.dataRequest
        let start = max(dataRequest?.requestedOffset ?? 0, dataRequest?.currentOffset ?? 0)
        let remaining = max(0, total - start)
        let length = dataRequest?.requestsAllDataToEndOfResource == true ? remaining : min(remaining, Int64(dataRequest?.requestedLength ?? 0))
        let end = start + length
        let total = self.total, contentType = self.contentType
        pending[key] = Task { [weak self] in
            guard let self else { return }
            do {
                if dataRequest == nil {
                    _ = try await self.read(0, min(2, total))
                }
                try Task.checkCancellation()
                let ready = await self.deliver(request, key: key) {
                    request.contentInformationRequest?.contentType = contentType
                    request.contentInformationRequest?.contentLength = total
                    request.contentInformationRequest?.isByteRangeAccessSupported = true
                }
                guard ready else { return }
                var cursor = start
                while cursor < end {
                    try Task.checkCancellation()
                    let count = min(262_144, end - cursor)
                    let bytes = try await self.read(cursor, count)
                    guard !bytes.isEmpty, bytes.count <= Int(count) else { throw OdifyError.message("음원 구간 연결이 중단되었습니다.") }
                    guard await self.deliver(request, key: key, action: { request.dataRequest?.respond(with: bytes) }) else { return }
                    cursor += Int64(bytes.count)
                }
                await self.finish(request, key: key, error: nil)
            } catch is CancellationError { await self.finish(request, key: key, error: nil) }
            catch { await self.finish(request, key: key, error: error) }
        }
        return true
    }
    func resourceLoader(_ resourceLoader: AVAssetResourceLoader, didCancel loadingRequest: AVAssetResourceLoadingRequest) {
        pending.removeValue(forKey: ObjectIdentifier(loadingRequest))?.cancel()
    }
    private func deliver(_ request: AVAssetResourceLoadingRequest, key: ObjectIdentifier, action: @escaping () -> Void) async -> Bool {
        await withCheckedContinuation { continuation in
            queue.async {
                guard self.pending[key] != nil, !request.isCancelled, !request.isFinished else { continuation.resume(returning: false); return }
                action(); continuation.resume(returning: true)
            }
        }
    }
    private func finish(_ request: AVAssetResourceLoadingRequest, key: ObjectIdentifier, error: Error?) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            queue.async {
                if self.pending.removeValue(forKey: key) != nil, !request.isCancelled, !request.isFinished {
                    if let error { request.finishLoading(with: error) } else { request.finishLoading() }
                }
                continuation.resume()
            }
        }
    }
    func cancel() { queue.async { self.pending.values.forEach { $0.cancel() }; self.pending.removeAll() } }
}
