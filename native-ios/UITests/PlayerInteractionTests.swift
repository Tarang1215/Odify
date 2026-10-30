import XCTest

final class PlayerInteractionTests: XCTestCase {
    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--odify-preview", "--odify-player"]
        app.launch()
        XCTAssertTrue(app.buttons["player.collapse"].waitForExistence(timeout: 15))
        return app
    }
    private func capture(_ name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = name
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
}
