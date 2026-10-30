import XCTest
@testable import Odify

@MainActor final class HomeLoadingTests: XCTestCase {
    func testSuccessfulHomeCacheSurvivesRestartAndFailedRefreshWithoutGlobalAlert() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let original = LibraryStore(documents: root, homeProvider: {
            [Track(id: "catalog:fixture", title: "Public fixture", artist: "Test")]
        })
        await original.loadHome()
        XCTAssertEqual(original.chart.count, 1); XCTAssertNil(original.homeError)
        original.engine.shutdown()
        let restored = LibraryStore(documents: root, homeProvider: { throw URLError(.timedOut) })
        defer { restored.engine.shutdown() }
        XCTAssertEqual(restored.chart.map(\.id), ["catalog:fixture"], "saved discovery must be available before any new network response")
        await restored.loadHome(refresh: true)
        XCTAssertEqual(restored.chart.map(\.id), ["catalog:fixture"])
        XCTAssertTrue(restored.homeError?.contains("저장된 추천") == true)
        XCTAssertNil(restored.notice, "a discovery timeout must not present an app-wide alert")
        XCTAssertFalse(restored.homeLoading)
    }
    func testFirstHomeTimeoutKeepsLibraryUsableAndRecordsOnlySafeErrorCodes() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = LibraryStore(documents: root, homeProvider: {
            throw NSError(domain: NSURLErrorDomain, code: -1001,
                userInfo: [NSLocalizedDescriptionKey: "private-name https://private.example/?token=secret"])
        })
        defer { store.engine.shutdown() }
        store.remember(Track(id: "local", title: "Local fixture", artist: "Test", source: .local))
        await store.loadHome()
        XCTAssertEqual(store.tracks.map(\.id), ["local"])
        XCTAssertTrue(store.homeError?.contains("보관함") == true)
        XCTAssertFalse(store.homeError?.contains("private") == true); XCTAssertNil(store.notice)
        XCTAssertFalse(store.homeLoading)
        let trace = try String(contentsOf: root.appendingPathComponent("odify-playback-diagnostics.json"), encoding: .utf8)
        XCTAssertTrue(trace.contains("home-catalog-failed")); XCTAssertTrue(trace.contains("-1001"))
        XCTAssertFalse(trace.contains("private")); XCTAssertFalse(trace.contains("secret"))
    }
    func testCancelledHomeRequestStopsLoadingWithoutShowingAnError() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let store = LibraryStore(documents: root, homeProvider: { throw URLError(.cancelled) })
        defer { store.engine.shutdown(); try? FileManager.default.removeItem(at: root) }
        await store.loadHome()
        XCTAssertNil(store.homeError); XCTAssertNil(store.notice); XCTAssertFalse(store.homeLoading)
    }
}
