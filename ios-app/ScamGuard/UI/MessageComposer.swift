import MessageUI
import SwiftUI

/// A text for the family contact, ready in Messages. The user taps Send; nothing is sent by the app.
struct MessageDraft: Identifiable {
    let phone: String
    let body: String
    var id: String { phone + body }
}

/// MFMessageComposeViewController in SwiftUI (no SMS permission needed). Where it isn't available (the Simulator,
/// an iPad without Messages) the app opens an sms: link instead.
struct MessageComposer: UIViewControllerRepresentable {
    let draft: MessageDraft
    let onDone: () -> Void

    static var canSend: Bool { MFMessageComposeViewController.canSendText() }

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    func makeUIViewController(context: Context) -> MFMessageComposeViewController {
        let vc = MFMessageComposeViewController()
        vc.recipients = [draft.phone]
        vc.body = draft.body
        vc.messageComposeDelegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ vc: MFMessageComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMessageComposeViewControllerDelegate {
        let onDone: () -> Void
        init(onDone: @escaping () -> Void) { self.onDone = onDone }

        func messageComposeViewController(_ controller: MFMessageComposeViewController,
                                          didFinishWith result: MessageComposeResult) {
            onDone()
        }
    }
}
