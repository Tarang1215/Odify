import Foundation
import XCTest
@testable import Odify

private final class MasterSheetProtocol: URLProtocol {
    static var requests: [URLRequest] = []
    static var multiple = false
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "www.googleapis.com" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        let text: String
        if request.url!.path.hasSuffix("/export") {
            text = "Track ID,곡명,아티스트,앨범\nODF000001,Song,Artist,Album\n"
        } else {
            text = Self.multiple ? #"{"files":[{"id":"sheetA","name":"Odify 음악 마스터 A","mimeType":"application/vnd.google-apps.spreadsheet"},{"id":"sheetB","name":"Odify 음악 마스터 B","mimeType":"application/vnd.google-apps.spreadsheet"}]}"# : #"{"files":[{"id":"sheetA","name":"Odify 음악 마스터","mimeType":"application/vnd.google-apps.spreadsheet"}]}"#
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(text.utf8)); client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class MasterCatalogTests: XCTestCase {
    func testCSVHandlesBOMQuotedCommasEscapedQuotesAndNewlines() throws {
        let csv = "\u{FEFF}Track ID,곡명,아티스트,앨범,Drive 파일 URL\r\nODF000001,\"Song, \"\"Two\"\"\nAgain\",Artist,Album,https://drive.google.com/file/d/file123/view\r\n"
        let rows = try MasterCatalog.parse(Data(csv.utf8))
        XCTAssertEqual(rows.count, 1); XCTAssertEqual(rows[0].title, "Song, \"Two\"\nAgain")
        XCTAssertEqual(rows[0].driveID, "file123")
        XCTAssertThrowsError(try MasterCatalog.parse(Data("wrong,headers\nvalue,here".utf8)))
        XCTAssertThrowsError(try MasterCatalog.parse(Data("Track ID,곡명,아티스트\n\"unfinished".utf8)))
    }
    func testODFAndExactDriveIDRepairNamesWithoutChangingPlaybackReferences() {
        let records = [MasterRecording(code: "ODF000001", title: "Song", artist: "Artist", album: "Album", driveID: "file123")]
        let drive = Track(id: "drive:account:file123", title: "ODF000001", artist: "Google Drive", source: .drive, driveFileID: "file123", driveAccountHash: "account", fileSize: 10000)
        let local = Track(id: "local", title: "ODF000001", artist: "Unknown Artist", source: .local, localFile: "Music/owned.mp3")
        let result = MasterCatalog.apply(records, to: [drive, local], account: "account")
        XCTAssertEqual(result.map(\.title), ["Song", "Song"])
        XCTAssertEqual(result[0].driveFileID, drive.driveFileID); XCTAssertEqual(result[1].localFile, local.localFile)
        XCTAssertEqual(result[0].metadataOrigin, "master"); XCTAssertEqual(result[1].originalFilename, "ODF000001")
        let requested = Track(id: "catalog", title: "Song", artist: "Artist")
        XCTAssertEqual(PlaybackSourcePreference.resolve(requested, library: [result[0]], account: "account", documents: FileManager.default.temporaryDirectory).id, drive.id)
    }
    func testOtherAccountUserEditsAmbiguityAndCodePrefixCollisionsRemainUnchanged() {
        let a = MasterRecording(code: "ODF000001", title: "Song", artist: "Artist", album: "Album", driveID: nil)
        var track = Track(id: "drive", title: "ODF0000010", artist: "Google Drive", source: .drive, driveFileID: "file123", driveAccountHash: "account", fileSize: 10000)
        XCTAssertNil(MasterCatalog.code(in: track.title))
        XCTAssertEqual(MasterCatalog.apply([a], to: [track], account: "account"), [track])
        track.title = "ODF000001"
        XCTAssertEqual(MasterCatalog.apply([a], to: [track], account: "other"), [track])
        var b = a; b.title = "Different edition"
        XCTAssertEqual(MasterCatalog.apply([a, b], to: [track], account: "account"), [track])
        track.metadataOrigin = "user"
        XCTAssertEqual(MasterCatalog.apply([a], to: [track], account: "account"), [track])
    }
    func testMasterExportOnlyReadsAndCachesWithinTheMatchingAccount() async throws {
        MasterSheetProtocol.requests = []; MasterSheetProtocol.multiple = false
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [MasterSheetProtocol.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let client = DriveClient(session: session, token: { "fixture-" + $0 })
        let first = try await client.masterRecordings(account: "accountA")
        let cached = try await client.masterRecordings(account: "accountA")
        XCTAssertEqual(first, cached); XCTAssertEqual(MasterSheetProtocol.requests.count, 2)
        _ = try await client.masterRecordings(account: "accountB")
        XCTAssertEqual(MasterSheetProtocol.requests.count, 4)
        XCTAssertTrue(MasterSheetProtocol.requests.allSatisfy { $0.httpMethod == "GET" })
        XCTAssertEqual(MasterSheetProtocol.requests.last?.value(forHTTPHeaderField: "Authorization"), "Bearer fixture-accountB")
        let q = URLComponents(url: MasterSheetProtocol.requests[0].url!, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "q" })?.value ?? ""
        XCTAssertTrue(q.contains("Odify")); XCTAssertTrue(q.contains("음악 마스터"))
    }
    func testMultipleMasterSheetsAreNotSelectedArbitrarily() async {
        MasterSheetProtocol.requests = []; MasterSheetProtocol.multiple = true
        defer { MasterSheetProtocol.multiple = false }
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [MasterSheetProtocol.self]
        let session = URLSession(configuration: config); defer { session.invalidateAndCancel() }
        let client = DriveClient(session: session, token: { _ in "fixture" })
        do { _ = try await client.masterRecordings(account: "account"); XCTFail("Ambiguous master must not be selected") }
        catch { XCTAssertTrue(error.localizedDescription.contains("여러 개")) }
        XCTAssertEqual(MasterSheetProtocol.requests.count, 1)
    }
}
