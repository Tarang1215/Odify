import SwiftUI
import UIKit

enum MusicKeyAction: Equatable {
    case toggle, seek(Double), next, previous, volume(Float), close, search, page(Int)
    static func action(input: String, modifiers: UIKeyModifierFlags) -> MusicKeyAction? {
        guard modifiers.intersection([.control, .alternate]).isEmpty else { return nil }
        if modifiers.contains(.command) {
            if input == UIKeyCommand.inputRightArrow { return .next }
            if input == UIKeyCommand.inputLeftArrow { return .previous }
            if input.lowercased() == "f" { return .search }
            if let page = Int(input), (1...4).contains(page) { return .page(page - 1) }
            return nil
        }
        let distance: Double = modifiers.contains(.shift) ? 15 : 5
        switch input {
        case " ": return .toggle
        case UIKeyCommand.inputLeftArrow: return .seek(-distance)
        case UIKeyCommand.inputRightArrow: return .seek(distance)
        case UIKeyCommand.inputUpArrow: return .volume(0.05)
        case UIKeyCommand.inputDownArrow: return .volume(-0.05)
        case UIKeyCommand.inputEscape: return .close
        default: return nil
        }
    }
}

/// Hardware commands have a real UIKit responder. Editing fields and presented
/// dialogs keep their own keyboard behavior, including spaces and arrow keys.
struct KeyboardControls: UIViewControllerRepresentable {
    var perform: (MusicKeyAction) -> Void
    func makeUIViewController(context: Context) -> MusicKeyboardController { MusicKeyboardController(perform: perform) }
    func updateUIViewController(_ controller: MusicKeyboardController, context: Context) { controller.performAction = perform; controller.arm() }
}

final class MusicKeyboardController: UIViewController {
    var performAction: (MusicKeyAction) -> Void
    private var observers: [NSObjectProtocol] = []
    init(perform: @escaping (MusicKeyAction) -> Void) { performAction = perform; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func loadView() { view = UIView(); view.backgroundColor = .clear; view.isUserInteractionEnabled = false }
    override var canBecomeFirstResponder: Bool { true }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated); arm()
        if observers.isEmpty {
            for name in [UITextField.textDidEndEditingNotification, UITextView.textDidEndEditingNotification] {
                observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                    DispatchQueue.main.async { self?.arm() }
                })
            }
        }
    }
    deinit { for observer in observers { NotificationCenter.default.removeObserver(observer) } }
    private var textOrModalActive: Bool {
        guard let window = viewIfLoaded?.window else { return true }
        if let root = window.rootViewController {
            var front = root
            while let presented = front.presentedViewController { front = presented }
            var owner: UIViewController? = self
            var belongsToFront = false
            while let current = owner {
                if current === front { belongsToFront = true; break }
                owner = current.parent
            }
            if !belongsToFront { return true }
        }
        func editing(_ view: UIView) -> Bool {
            if view.isFirstResponder, view is UITextInput { return true }
            return view.subviews.contains(where: editing)
        }
        return editing(window)
    }
    func arm() {
        guard isViewLoaded, view.window != nil, !textOrModalActive, !isFirstResponder else { return }
        becomeFirstResponder()
    }
    override var keyCommands: [UIKeyCommand]? {
        guard !textOrModalActive else { return [] }
        let specs: [(String, UIKeyModifierFlags, String)] = [
            (" ", [], "재생 / 일시정지"), (UIKeyCommand.inputLeftArrow, [], "5초 뒤로"),
            (UIKeyCommand.inputRightArrow, [], "5초 앞으로"),
            (UIKeyCommand.inputLeftArrow, .shift, "15초 뒤로"), (UIKeyCommand.inputRightArrow, .shift, "15초 앞으로"),
            (UIKeyCommand.inputLeftArrow, .command, "이전 곡"), (UIKeyCommand.inputRightArrow, .command, "다음 곡"),
            (UIKeyCommand.inputUpArrow, [], "음량 높이기"), (UIKeyCommand.inputDownArrow, [], "음량 낮추기"),
            (UIKeyCommand.inputEscape, [], "플레이어 접기"), ("f", .command, "음악 검색"),
            ("1", .command, "홈"), ("2", .command, "탐색"), ("3", .command, "검색"), ("4", .command, "보관함")
        ]
        return specs.map { input, flags, title in
            let command = UIKeyCommand(input: input, modifierFlags: flags, action: #selector(handle(_:)))
            command.discoverabilityTitle = title; command.wantsPriorityOverSystemBehavior = true
            return command
        }
    }
    @objc private func handle(_ command: UIKeyCommand) {
        guard !textOrModalActive, let input = command.input,
              let action = MusicKeyAction.action(input: input, modifiers: command.modifierFlags) else { return }
        performAction(action)
    }
}

extension View {
    @ViewBuilder func playerArtworkTransition(_ id: String, namespace: Namespace.ID?, source: Bool) -> some View {
        if let namespace { matchedTransitionSource(id: id, in: namespace) }
        else { self }
    }
}
