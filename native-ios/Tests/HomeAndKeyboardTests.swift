import SwiftUI
import UIKit
import XCTest
@testable import Odify

final class HomeAndKeyboardTests: XCTestCase {
    func testListeningRequiresActualPlayingSamplesAndReportsEachOccurrenceOnce() {
        var meter = ListeningMeter(), reports = 0
        for second in 0...20 { if meter.sample(occurrence: "a", playing: false, uptime: Double(second)) { reports += 1 } }
        for second in 21...51 { if meter.sample(occurrence: "a", playing: true, uptime: Double(second)) { reports += 1 } }
        XCTAssertEqual(reports, 1)
        XCTAssertFalse(meter.sample(occurrence: "a", playing: true, uptime: 52))
        XCTAssertFalse(meter.sample(occurrence: "b", playing: true, uptime: 53))
        XCTAssertFalse(meter.sample(occurrence: "b", playing: true, uptime: 10000))
    }
    func testDailyMixUsesLikedRecordingAcrossSourcesAndCapsEachArtist() throws {
        let tracks = (0..<8).map { Track(id: "owned:\($0)", title: "Song \($0)", artist: $0 < 6 ? "A" : "B", source: .drive, duration: 180) }
        var linked = tracks[0]; linked.id = "catalog:0"; linked.source = .youtube
        let mixes = PersonalMixes.build(tracks: tracks + [linked], liked: [linked.id], events: [])
        let daily = try XCTUnwrap(mixes.first(where: { $0.id == "daily" }))
        XCTAssertEqual(daily.tracks.count, 3)
        XCTAssertTrue(daily.tracks.allSatisfy { $0.artist == "A" })
        XCTAssertEqual(mixes.first(where: { $0.id == "liked" })?.tracks.first?.recordingKey, linked.recordingKey)
        XCTAssertFalse(mixes.contains { $0.id == "repeat" })
    }
    func testRepeatMixIgnoresOldHistoryAndDiscoveryIsStableForTheDay() throws {
        let now = Date(timeIntervalSince1970: 1800000000)
        let tracks = (0..<10).map { Track(id: "\($0)", title: "Song \($0)", artist: "A", duration: 180) }
        let events = [ListenEvent(occurrence: "a", recordingKey: tracks[0].recordingKey, at: now),
                      ListenEvent(occurrence: "b", recordingKey: tracks[1].recordingKey, at: now.addingTimeInterval(-61 * 86400))]
        let first = PersonalMixes.build(tracks: tracks, liked: [], events: events, now: now)
        let second = PersonalMixes.build(tracks: Array(tracks.reversed()), liked: [], events: events, now: now)
        XCTAssertEqual(first.first(where: { $0.id == "repeat" })?.tracks.map(\.id), [tracks[0].id])
        XCTAssertEqual(first.first(where: { $0.id == "unheard" })?.tracks.map(\.id), second.first(where: { $0.id == "unheard" })?.tracks.map(\.id))
    }
    func testStableListIdentityRejectsDuplicateCatalogIDsAndRecordingAliases() {
        let a = Track(id: "same", title: "A", artist: "Artist")
        let duplicate = Track(id: "same", title: "Localized A", artist: "Artist")
        let alias = Track(id: "other", title: "A", artist: "Artist")
        XCTAssertEqual(Track.unique([a, duplicate, alias]).map(\.id), ["same"])
    }
    func testKeyboardTransportUsesExpectedModifiersAndKeepsAccessibilityChords() {
        XCTAssertEqual(MusicKeyAction.action(input: " ", modifiers: []), .toggle)
        XCTAssertEqual(MusicKeyAction.action(input: UIKeyCommand.inputRightArrow, modifiers: []), .seek(5))
        XCTAssertEqual(MusicKeyAction.action(input: UIKeyCommand.inputLeftArrow, modifiers: .shift), .seek(-15))
        XCTAssertEqual(MusicKeyAction.action(input: UIKeyCommand.inputRightArrow, modifiers: .command), .next)
        XCTAssertEqual(MusicKeyAction.action(input: "f", modifiers: .command), .search)
        XCTAssertEqual(MusicKeyAction.action(input: "4", modifiers: .command), .page(3))
        XCTAssertNil(MusicKeyAction.action(input: UIKeyCommand.inputRightArrow, modifiers: [.control, .alternate]))
        XCTAssertNil(MusicKeyAction.action(input: "x", modifiers: .command))
    }
    @MainActor func testOriginalMarkAndAndroidPaletteRemainWithinTheirBounds() {
        let bounds = OdifyMark().path(in: CGRect(x: 0, y: 0, width: 40, height: 40)).boundingRect
        XCTAssertGreaterThan(bounds.width, 25); XCTAssertLessThanOrEqual(bounds.maxX, 40.01); XCTAssertLessThanOrEqual(bounds.maxY, 40.01)
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0
        UIColor(OdifyPalette.background).getRed(&red, green: &green, blue: &blue, alpha: nil)
        XCTAssertEqual(red, 12.0 / 255, accuracy: 0.001); XCTAssertEqual(blue, 18.0 / 255, accuracy: 0.001)
    }
}
