import Foundation
import XCTest
@testable import Odify

private final class MusicFolderProtocol: URLProtocol {
    static var repeatingPage = false
    static var queries: [String] = []
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "www.googleapis.com" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let items = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems ?? []
        let query = items.first(where: { $0.name == "q" })?.value ?? ""
        Self.queries.append(query)
        let page = items.first(where: { $0.name == "pageToken" })?.value
        var result: [String: Any]
        if query.contains("mimeType =") {
            result = ["files": [["id": "music-folder", "name": "Odify_Music_Test", "mimeType": "application/vnd.google-apps.folder"]]]
        } else if query.contains("'music-folder'") {
            if page == nil {
                result = ["files": [
                    ["id": "nested-folder", "name": "Nested", "mimeType": "application/vnd.google-apps.folder"],
                    ["id": "song1", "name": "Artist - One.mp3", "mimeType": "audio/mpeg", "size": "163090"],
                    ["id": "notes", "name": "notes.txt", "mimeType": "text/plain"]
                ], "nextPageToken": "page2"]
            } else {
                result = ["files": [["id": "song1", "name": "Artist - One.mp3", "mimeType": "audio/mpeg", "size": "163090"]]]
                if Self.repeatingPage { result["nextPageToken"] = "page2" }
            }
        } else {
            result = ["files": [
                ["id": "music-folder", "name": "Duplicate parent", "mimeType": "application/vnd.google-apps.folder"],
                ["id": "song2", "name": "Artist - Two.mp3", "mimeType": "audio/mpeg", "size": "163090"],
                ["id": "blocked", "name": "Blocked.mp3", "mimeType": "audio/mpeg", "size": "42", "capabilities": ["canDownload": false]]
            ]]
        }
        do {
            let data = try JSONSerialization.data(withJSONObject: result)
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() { }
}

final class DriveLibraryTests: XCTestCase {
    private func client() -> DriveClient {
        MusicFolderProtocol.queries = []; MusicFolderProtocol.repeatingPage = false
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [MusicFolderProtocol.self]
        return DriveClient(session: URLSession(configuration: config), token: { _ in "fixture-token" })
    }
    func testFindsNamedRootsOutsideMyDriveRootAndIndexesNestedAudioOnce() async throws {
        let value = try await client().musicLibrary(account: "fixture-account")
        XCTAssertEqual(value.map(\.id), ["drive:fixture-account:song1", "drive:fixture-account:song2"])
        XCTAssertTrue(value.allSatisfy { $0.source == .drive && $0.localFile == nil && $0.videoID == nil })
        XCTAssertTrue(MusicFolderProtocol.queries[0].contains("name = 'Odify_Music_Test'"))
        XCTAssertFalse(MusicFolderProtocol.queries.contains { $0.contains("'root' in parents") })
        XCTAssertEqual(MusicFolderProtocol.queries.filter { $0.contains("'nested-folder'") }.count, 1)
    }
    func testRepeatedPageStopsWithoutPublishingAPartialLibrary() async throws {
        let service = client(); MusicFolderProtocol.repeatingPage = true
        do { _ = try await service.musicLibrary(account: "fixture-account"); XCTFail("pagination loop was accepted") }
        catch { XCTAssertTrue(error.localizedDescription.contains("반복")) }
    }
    @MainActor func testBulkMergeRetainsReadTagsAndUsesFreshFileBinding() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(documents: root)
        defer { store.engine.shutdown(); try? FileManager.default.removeItem(at: root) }
        let old = Track(id: "drive:one", title: "Embedded title", artist: "Embedded artist", artwork: "Artwork/local.jpg", source: .drive,
                        driveFileID: "one", driveAccountHash: "fixture", fileSize: 100, mimeType: "audio/mpeg", fileExtension: "mp3", driveModifiedTime: "same")
        store.remember(old)
        var fresh = old; fresh.title = "Filename"; fresh.artist = "Google Drive"; fresh.artwork = nil; fresh.fileSize = 200
        store.mergeDriveLibrary([fresh, fresh])
        XCTAssertEqual(store.tracks.count, 1); XCTAssertEqual(store.tracks[0].title, "Embedded title")
        XCTAssertEqual(store.tracks[0].fileSize, 200); XCTAssertEqual(store.tracks[0].artwork, old.artwork)
    }
}
