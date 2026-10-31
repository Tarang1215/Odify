import XCTest

final class PlayerInteractionTests: XCTestCase {
    private func launch() -> XCUIApplication {
        XCUIDevice.shared.orientation = .portrait
        let app = XCUIApplication()
        app.launchArguments = ["--odify-preview", "--odify-player"]
        app.launch()
        XCTAssertTrue(app.buttons["player.collapse"].waitForExistence(timeout: 15))
        return app
    }
    private func capture(_ name: String, app: XCUIApplication) {
        // Application-region screenshots can retain portrait clipping after an
        // iPad rotation. Capture the composed display, including the inspector.
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); attachment.name = name
        attachment.lifetime = .keepAlways; add(attachment)
    }
    func testOneTapCollapsesAndReopensRepeatedly() {
        let app = launch()
        capture("full-player-glass", app: app)
        for _ in 0..<3 {
            app.buttons["player.collapse"].tap()
            XCTAssertTrue(app.buttons["player.collapse"].waitForNonExistence(timeout: 4))
            XCTAssertTrue(app.buttons["player.expand"].waitForExistence(timeout: 4))
            app.buttons["player.expand"].tap()
            XCTAssertTrue(app.buttons["player.collapse"].waitForExistence(timeout: 4))
        }
        app.buttons["player.collapse"].tap()
        XCTAssertTrue(app.buttons["player.expand"].waitForExistence(timeout: 4))
        capture("mini-player-glass", app: app)
    }
    func testDownwardDragCollapsesPlayer() {
        let app = launch()
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.27))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.69))
        start.press(forDuration: 0.05, thenDragTo: end)
        XCTAssertTrue(app.buttons["player.collapse"].waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.buttons["player.expand"].waitForExistence(timeout: 4))
    }
    func testLyricsAndQueueOverlayDoNotMoveTheSongHeadingOrBlockCollapse() {
        let app = launch()
        let heading = app.staticTexts["player.title"]
        XCTAssertTrue(heading.waitForExistence(timeout: 5))
        let original = heading.frame.minY
        app.buttons["player.lyrics.button"].tap()
        XCTAssertTrue(app.buttons["player.panel.close"].waitForExistence(timeout: 4))
        XCTAssertEqual(heading.frame.minY, original, accuracy: 2)
        capture("lyrics-floating-panel", app: app)
        app.buttons["player.queue.button"].tap()
        XCTAssertTrue(app.buttons["player.panel.close"].exists)
        XCTAssertEqual(heading.frame.minY, original, accuracy: 2)
        capture("queue-floating-panel", app: app)
        app.buttons["player.collapse"].tap()
        XCTAssertTrue(app.buttons["player.collapse"].waitForNonExistence(timeout: 4))
    }
    func testInspectorLaunchAndLandscapePresentation() {
        XCUIDevice.shared.orientation = .landscapeLeft
        let app = XCUIApplication(); app.launchArguments = ["--odify-preview"]; app.launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        let landscape = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.windows.firstMatch.frame.width > app.windows.firstMatch.frame.height
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [landscape], timeout: 10), .completed)
        let expand = app.buttons["player.inspector.expand"]
        XCTAssertTrue(expand.waitForExistence(timeout: 15))
        XCTAssertLessThanOrEqual(expand.frame.maxX, app.windows.firstMatch.frame.maxX)
        XCTAssertTrue(expand.isHittable); expand.tap()
        XCTAssertTrue(app.buttons["player.collapse"].waitForExistence(timeout: 5))
        let full = app.otherElements["player.full"]
        XCTAssertGreaterThanOrEqual(full.frame.width, app.windows.firstMatch.frame.width - 1)
        XCTAssertGreaterThanOrEqual(full.frame.maxY, app.windows.firstMatch.frame.maxY - 40)
        capture("inspector-expanded-landscape", app: app)
        app.buttons["player.collapse"].tap()
        XCTAssertTrue(expand.waitForExistence(timeout: 5))
        capture("home-landscape-integrated-inspector", app: app)
        let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "player.inspector.queue.")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5)); row.tap()
        XCTAssertTrue(app.buttons["player.collapse"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["player.title"].label, "Soft Focus")
        capture("inspector-queue-expanded-landscape", app: app)
        app.buttons["player.collapse"].tap()
    }
    func testHomeEndClearsTheFloatingMiniPlayer() {
        let app = launch(); app.buttons["player.collapse"].tap()
        let end = app.staticTexts["home.end"]
        let mini = app.buttons["player.expand"]
        for _ in 0..<8 {
            if end.isHittable && end.frame.maxY <= mini.frame.minY - 8 { break }
            app.swipeUp()
        }
        XCTAssertTrue(end.isHittable)
        XCTAssertLessThanOrEqual(end.frame.maxY, mini.frame.minY)
        capture("home-bottom-clearance", app: app)
    }
    func testArtistRowsRemainVisibleAfterScrollingBack() {
        let app = launch(); app.buttons["player.collapse"].tap()
        let artist = app.buttons["home.artist.preview:1"]
        for _ in 0..<4 { if artist.isHittable { break }; app.swipeUp() }
        XCTAssertTrue(artist.waitForExistence(timeout: 5)); artist.tap()
        let first = app.buttons["track.play.preview:1"]
        XCTAssertTrue(first.waitForExistence(timeout: 8)); XCTAssertTrue(first.isHittable)
        let play = app.buttons["artist.play"]
        XCTAssertLessThanOrEqual(play.frame.width, 48); XCTAssertLessThanOrEqual(play.frame.height, 48)
        for _ in 0..<4 { app.swipeUp() }
        for _ in 0..<7 { if first.isHittable { break }; app.swipeDown() }
        XCTAssertTrue(first.isHittable)
        capture("artist-rows-after-scroll", app: app)
    }
    func testHardwareKeyboardTransportAndTextEntry() {
        let app = launch()
        let seek = app.descendants(matching: .any).matching(identifier: "player.seek").firstMatch
        app.typeKey(XCUIKeyboardKey.rightArrow, modifierFlags: [])
        XCTAssertTrue(seek.value as? String == "0:47 / 4:04")
        app.typeKey(XCUIKeyboardKey.leftArrow, modifierFlags: .shift)
        XCTAssertTrue(seek.value as? String == "0:32 / 4:04")
        app.typeKey(" ", modifierFlags: [])
        let transport = app.buttons["player.transport"]
        let playing = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "일시 정지"), object: transport)
        XCTAssertEqual(XCTWaiter.wait(for: [playing], timeout: 5), .completed)
        app.typeKey(" ", modifierFlags: [])
        let paused = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "재생"), object: transport)
        XCTAssertEqual(XCTWaiter.wait(for: [paused], timeout: 5), .completed)
        app.buttons["player.lyrics.button"].tap(); app.buttons["가사 검색"].firstMatch.tap()
        let field = app.textFields["lyrics.title"]
        XCTAssertTrue(field.waitForExistence(timeout: 5)); field.tap()
        field.typeText(" test words")
        XCTAssertTrue((field.value as? String)?.contains("test words") == true)
    }
    func testDockSeparatesAndRejoinsWithoutLosingControls() {
        let app = launch(); app.buttons["player.collapse"].tap()
        let transport = app.buttons["player.mini.transport"]
        XCTAssertTrue(transport.waitForExistence(timeout: 5))
        capture("dock-joined-paused", app: app)
        transport.tap()
        let playing = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "일시 정지"), object: transport)
        XCTAssertEqual(XCTWaiter.wait(for: [playing], timeout: 5), .completed)
        XCTAssertTrue(app.buttons["player.expand"].isHittable)
        capture("dock-separated-playing", app: app)
        transport.tap()
        let paused = XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "재생"), object: transport)
        XCTAssertEqual(XCTWaiter.wait(for: [paused], timeout: 5), .completed)
        app.buttons["player.expand"].tap()
        XCTAssertTrue(app.buttons["player.collapse"].waitForExistence(timeout: 5))
        app.typeKey(XCUIKeyboardKey.escape, modifierFlags: [])
        XCTAssertTrue(transport.waitForExistence(timeout: 5))
        app.typeKey(" ", modifierFlags: [])
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "일시 정지"), object: transport)], timeout: 5), .completed)
    }
    func testNativeAudioKeepsPlayingInBackgroundAndExplicitPauseSurvivesReturn() {
        let app = launch(), transport = app.buttons["player.transport"]
        transport.tap()
        XCTAssertEqual(XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "label == %@", "일시 정지"), object: transport)], timeout: 5), .completed)
        XCUIDevice.shared.press(.home)
        Thread.sleep(forTimeInterval: 3)
        app.activate()
        XCTAssertTrue(transport.waitForExistence(timeout: 5))
        XCTAssertEqual(transport.label, "일시 정지")
        let seek = app.descendants(matching: .any).matching(identifier: "player.seek").firstMatch
        let position = (seek.value as? String)?.components(separatedBy: " / ").first ?? ""
        let parts = position.split(separator: ":").compactMap { Int($0) }
        XCTAssertEqual(parts.count, 2)
        if parts.count == 2 { XCTAssertGreaterThanOrEqual(parts[0] * 60 + parts[1], 44) }
        transport.tap(); XCUIDevice.shared.press(.home)
        Thread.sleep(forTimeInterval: 2); app.activate()
        XCTAssertTrue(transport.waitForExistence(timeout: 5))
        XCTAssertEqual(transport.label, "재생")
        capture("native-background-return", app: app)
    }
}
