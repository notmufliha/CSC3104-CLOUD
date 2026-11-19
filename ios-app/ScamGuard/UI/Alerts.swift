import AVFoundation
import UIKit

/// What to say: the warning, in English.
nonisolated struct WarningSpeech: Equatable, Sendable {
    let text: String
}

/// The red warning's sound, haptics and speech, and the yellow banner's single tap. A protocol so tests record
/// what would play instead of making noise.
@MainActor
protocol WarningAlerts: AnyObject {
    func red(_ speech: WarningSpeech?)
    func yellow()
    func stop()
}

/// Tests and the app's own unit-test host: nothing plays.
@MainActor
final class SilentAlerts: WarningAlerts {
    func red(_ speech: WarningSpeech?) {}
    func yellow() {}
    func stop() {}
}

/// Same behaviour as Android ui/Alerts.kt. Red: three .error haptics and three beeps, played with the audio
/// session's .playback category (heard even with the silent switch on), then the warning is read aloud with an
/// English voice (AVSpeechSynthesizer). Nothing is spoken while VoiceOver is on: VoiceOver announces the warning,
/// and two voices at once can't be understood.
@MainActor
final class AlertPlayer: NSObject, WarningAlerts, AVSpeechSynthesizerDelegate {
    private let synth = AVSpeechSynthesizer()
    private var player: AVAudioPlayer?
    private var work: [Task<Void, Never>] = []

    override init() {
        super.init()
        synth.delegate = self
        // .playback: heard even with the silent switch on. Playing activates the session.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
    }

    func red(_ speech: WarningSpeech?) {
        stop()
        let haptics = UINotificationFeedbackGenerator()
        haptics.prepare()
        work.append(Task { @MainActor in
            for i in 0..<3 {
                if Task.isCancelled { return }
                haptics.notificationOccurred(.error)
                if i < 2 { try? await Task.sleep(for: .milliseconds(450)) }
            }
        })
        player = try? AVAudioPlayer(data: AlertTone.wav())
        player?.volume = 1
        player?.play()
        guard let speech, !UIAccessibility.isVoiceOverRunning else { return }
        work.append(Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(AlertTone.durationMs + 250))
            guard !Task.isCancelled else { return }
            self?.speak(speech)
        })
    }

    /// "Be careful": the system's warning haptic, no sound (red uses .error; taps a light impact: Logic/Motion.swift).
    func yellow() {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
    }

    func stop() {
        work.forEach { $0.cancel() }
        work = []
        player?.stop()
        player = nil
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
    }

    private func speak(_ speech: WarningSpeech) {
        let u = AVSpeechUtterance(string: speech.text)
        u.voice = AVSpeechSynthesisVoice(language: "en-GB") ?? AVSpeechSynthesisVoice(language: "en-US")
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 0.9      // a little slower than normal
        synth.speak(u)
    }

    /// Done talking: let other audio (music) come back up. Off the main thread (it can block).
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        DispatchQueue.global(qos: .utility).async {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }
}
