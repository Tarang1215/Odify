import CryptoKit
import Foundation
@preconcurrency import GoogleSignIn
import SwiftUI

/// Google owns token storage in the platform Keychain. No token is written to
/// the music library, preferences, URLs, logs, or the public build artifacts.
@MainActor final class DriveAccount: ObservableObject {
    static let shared = DriveAccount()
    static let scope = "https://www.googleapis.com/auth/drive.readonly"
    @Published private(set) var email: String?
    @Published private(set) var accountHash: String?
    @Published private(set) var connecting = false
    @Published var error: String?
    private var restored = false
    private var tokenTask: Task<String, Error>?
    var connected: Bool { accountHash != nil }

    init() {
        if let id = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String, !id.isEmpty {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: id)
        }
    }
    nonisolated static func hash(_ identifier: String) -> String {
        SHA256.hash(data: Data(identifier.utf8)).map { String(format: "%02x", $0) }.joined()
    }
    private func publish(_ user: GIDGoogleUser) {
        guard user.grantedScopes?.contains(Self.scope) == true, let id = user.userID else { return }
        email = user.profile?.email; accountHash = Self.hash(id)
    }
    func restore() async {
        guard !restored else { return }
        restored = true
        do {
            let user: GIDGoogleUser = try await withCheckedThrowingContinuation { continuation in
                GIDSignIn.sharedInstance.restorePreviousSignIn { user, error in
                    if let user { continuation.resume(returning: user) }
                    else { continuation.resume(throwing: error ?? OdifyError.message("Google 계정에 다시 연결해 주세요.")) }
                }
            }
            publish(user)
        } catch { /* No stored account is normal on first launch. Never open consent automatically. */ }
    }
    func connect() async {
        guard !connecting else { return }
        guard let controller = Self.presentingController() else { error = "로그인 창을 열 수 없습니다. 다시 시도해 주세요."; return }
        connecting = true; error = nil
        defer { connecting = false }
        do {
            let result: GIDSignInResult = try await withCheckedThrowingContinuation { continuation in
                GIDSignIn.sharedInstance.signIn(withPresenting: controller, hint: nil, additionalScopes: [Self.scope]) { result, error in
                    if let result { continuation.resume(returning: result) }
                    else { continuation.resume(throwing: error ?? OdifyError.message("Google 로그인이 취소되었습니다.")) }
                }
            }
            guard result.user.grantedScopes?.contains(Self.scope) == true else { throw OdifyError.message("Drive 음악을 탐색하려면 읽기 권한이 필요합니다.") }
            publish(result.user)
        } catch { self.error = error.localizedDescription }
    }
    func signOut() {
        tokenTask?.cancel(); tokenTask = nil
        GIDSignIn.sharedInstance.signOut(); email = nil; accountHash = nil
    }
    func token(expectedAccount: String) async throws -> String {
        await restore()
        guard accountHash == expectedAccount, let user = GIDSignIn.sharedInstance.currentUser else {
            throw OdifyError.message("이 곡을 담은 Google 계정으로 Drive에 다시 연결해 주세요.")
        }
        if let tokenTask { return try await tokenTask.value }
        let request = Task<String, Error> {
            let refreshed: GIDGoogleUser = try await withCheckedThrowingContinuation { continuation in
                user.refreshTokensIfNeeded { user, error in
                    if let user { continuation.resume(returning: user) }
                    else { continuation.resume(throwing: error ?? OdifyError.message("Google 로그인이 만료되었습니다. 다시 연결해 주세요.")) }
                }
            }
            try Task.checkCancellation()
            guard self.accountHash == expectedAccount, let identifier = refreshed.userID, Self.hash(identifier) == expectedAccount else {
                throw OdifyError.message("Google 계정이 변경되었습니다.")
            }
            return refreshed.accessToken.tokenString
        }
        tokenTask = request
        defer { tokenTask = nil }
        return try await request.value
    }
    private static func presentingController() -> UIViewController? {
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        var controller = windows.first(where: \.isKeyWindow)?.rootViewController
        while let presented = controller?.presentedViewController { controller = presented }
        return controller
    }
}
