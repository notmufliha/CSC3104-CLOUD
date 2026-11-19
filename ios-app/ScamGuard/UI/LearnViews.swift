import SwiftUI

// "Learn to spot scams": scam-type cards and the practice quiz. Same screens, order and wording as android-app
// ui/LearnScreens.kt. The content comes from Resources/Learn/learn.json (shared with Android).

/// Simple Home's big "Learn to spot scams" card.
struct LearnHomeCard: View {
    var body: some View {
        NavigationLink(value: HomeRoute.learn) {
            HStack(spacing: 14) {
                Image(systemName: "star.fill").font(.system(size: 24, weight: .semibold)).foregroundStyle(Theme.onBrand)
                    .frame(width: 48, height: 48).background(Theme.brand, in: Circle()).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L.s(.learn_title)).font(.title2.bold()).foregroundStyle(Theme.onBrandContainer)
                    Text(L.s(.learn_card_line)).font(.body).foregroundStyle(Theme.onBrandContainer)
                }
                .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").foregroundStyle(Theme.onBrandContainer).accessibilityHidden(true)
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.brandContainer, in: RoundedRectangle(cornerRadius: 24))
            .contentShape(RoundedRectangle(cornerRadius: 24))
        }
        .buttonStyle(PressFeedbackStyle())
        .accessibilityIdentifier("learnCard")
    }
}

/// The list: the quiz first, then one row per scam type.
struct LearnView: View {
    @Environment(CallViewModel.self) private var vm

    var body: some View {
        List {
            Section {
                Text(L.s(.learn_line)).font(.body).foregroundStyle(Theme.muted)
            }
            .listRowBackground(Color.clear)
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Text(L.s(.learn_quiz)).font(.title2.bold()).foregroundStyle(Theme.onBrandContainer).accessibilityAddTraits(.isHeader)
                    Text(L.s(.learn_quiz_line)).font(.body).foregroundStyle(Theme.onBrandContainer)
                    NavigationLink(value: HomeRoute.quiz) {
                        Text(L.s(.learn_quiz)).font(.title3.weight(.semibold)).foregroundStyle(Theme.onBrand)
                            .frame(maxWidth: .infinity, minHeight: 64).background(Theme.brand, in: Capsule())
                    }
                    .buttonStyle(PressFeedbackStyle())
                    .accessibilityIdentifier("startQuiz")
                }
                .padding(.vertical, 8)
            }
            .listRowBackground(Theme.brandContainer)
            Section {
                if vm.learn.types.isEmpty { EmptyState(text: L.s(.empty_learn), systemImage: "info.circle") }
                ForEach(vm.learn.types) { t in
                    NavigationLink(value: HomeRoute.learnType(t.id)) { SettingsRowLabel(icon: "exclamationmark.triangle.fill", title: t.title) }
                        .accessibilityIdentifier("type_\(t.id)")
                }
            } header: { SettingsHeader(text: L.s(.learn_types)) }
            .listRowBackground(Theme.card)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(L.s(.learn_title))
        .accessibilityIdentifier("learnList")
    }
}

/// One scam type: 3 warning signs, what to do, "Hear an example", the source.
struct ScamTypeView: View {
    @Environment(CallViewModel.self) private var vm
    @Environment(\.openURL) private var openURL
    let id: String

    var body: some View {
        let t = vm.learn.types.first { $0.id == id }
        ScrollView {
            if let t {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(L.s(.learn_signs)).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                        ForEach(t.signs, id: \.self) { sign in
                            HStack(alignment: .firstTextBaseline, spacing: 12) {
                                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Theme.warnYellow).font(.title3)
                                    .accessibilityHidden(true)
                                Text(sign).font(.body).fixedSize(horizontal: false, vertical: true)
                            }
                            .accessibilityElement(children: .combine)
                        }
                    }
                    .cardStyle()
                    VStack(alignment: .leading, spacing: 12) {
                        Text(L.s(.learn_todo)).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.safeGreen).font(.title3).accessibilityHidden(true)
                            Text(t.todo).font(.body).fixedSize(horizontal: false, vertical: true)
                        }
                        .accessibilityElement(children: .combine)
                    }
                    .cardStyle(Theme.safeGreen.opacity(0.14))
                    BigButton(title: L.s(.learn_hear), id: "hearExample") {
                        if let s = vm.scripts.first(where: { $0.fileName == t.script }) { vm.ring(s) }
                    }
                    Text(L.s(.learn_hear_line)).font(.body).foregroundStyle(Theme.muted)
                    Button(L.s(.learn_source, t.sourceName)) { if let u = URL(string: t.sourceUrl) { openURL(u) } }
                        .font(.body).frame(minHeight: 48)
                        .accessibilityIdentifier("typeSource")
                }
                .padding(16)
            }
        }
        .background(Theme.background)
        .navigationTitle(t?.title ?? "")
        .accessibilityIdentifier("scamType")
    }
}

/// The practice quiz: 5 real caller lines, "Is this a scam?", instant feedback with one sentence why, a result.
/// Simple mode's result is only a friendly line (no score, no best score).
struct QuizView: View {
    @Environment(CallViewModel.self) private var vm
    @State private var round: [QuizItem] = []
    @State private var index = 0
    @State private var answer: Bool?
    @State private var score = 0
    @State private var done = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if done {
                    result
                } else if index < round.count {
                    question(round[index])
                }
            }
            .padding(16)
        }
        // The next step stays on screen at the bottom, even at the largest text (no hunting for it below the fold).
        .safeAreaInset(edge: .bottom, spacing: 0) {
            bottomBar
                .padding(.horizontal, 16).padding(.vertical, 12)
                .frame(maxWidth: .infinity)
                .background(Theme.background.shadow(.drop(color: .black.opacity(0.12), radius: 6, y: -2)))
        }
        .background(Theme.background)
        .navigationTitle(L.s(.quiz_title))
        .accessibilityIdentifier("quiz")
        .onAppear {
            guard round.isEmpty else { return }
            round = Quiz.round(vm.learn)
            #if DEBUG
            // -demoQuiz answered|result (screenshots): the first question answered, or the result (4 of 5)
            switch UserDefaults.standard.string(forKey: "demoQuiz") {
            case "answered": answer = true
            case "result": score = 4; done = true
            default: break
            }
            #endif
        }
    }

    @ViewBuilder
    private func question(_ item: QuizItem) -> some View {
        Text(L.s(.quiz_progress, "\(index + 1)", "\(round.count)")).font(.headline).foregroundStyle(Theme.muted)
            .accessibilityIdentifier("quizProgress")
        VStack(alignment: .leading, spacing: 8) {
            Text(L.s(.quiz_caller_says)).font(.headline).foregroundStyle(Theme.muted)
            Text("“\(item.text)”").font(.title2.italic()).fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("quizLine")
        }
        .cardStyle()
        Text(L.s(.quiz_question)).font(.title2.bold()).accessibilityAddTraits(.isHeader)
        if let a = answer { feedback(item, right: Quiz.isRight(item, saidScam: a)) }
    }

    @ViewBuilder
    private var bottomBar: some View {
        if done {
            BigButton(title: L.s(.btn_try_again), id: "quizAgain") {
                round = Quiz.round(vm.learn, avoid: Set(round.map(\.text)))
                index = 0; answer = nil; score = 0; done = false
            }
        } else if index < round.count {
            let item = round[index]
            if answer == nil {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) { answerButtons(item) }
                    VStack(spacing: 12) { answerButtons(item) }
                }
            } else {
                let last = index == round.count - 1
                BigButton(title: L.s(last ? .quiz_see_result : .btn_next), id: "quizNext") {
                    if last { done = true; vm.recordQuiz(score) } else { index += 1; answer = nil }
                }
            }
        }
    }

    @ViewBuilder
    private func answerButtons(_ item: QuizItem) -> some View {
        answerButton(L.s(.quiz_scam), Theme.scamRed, "answerScam") { answer = true; if Quiz.isRight(item, saidScam: true) { score += 1 } }
        answerButton(L.s(.quiz_safe), Theme.safeGreen, "answerSafe") { answer = false; if Quiz.isRight(item, saidScam: false) { score += 1 } }
    }

    private func answerButton(_ label: String, _ color: Color, _ id: String, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            Text(label).font(.title.bold()).foregroundStyle(Color.primary)
                .frame(maxWidth: .infinity, minHeight: 72)
                .overlay(Capsule().stroke(color, lineWidth: 3))
                .contentShape(Capsule())
        }
        .buttonStyle(PressFeedbackStyle())
        .accessibilityIdentifier(id)
    }

    private func feedback(_ item: QuizItem, right: Bool) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Image(systemName: right ? "checkmark.circle.fill" : "info.circle.fill").font(.largeTitle)
                    .foregroundStyle(right ? Theme.safeGreen : Theme.warningInkAdaptive).accessibilityHidden(true)
                Text(L.s(right ? .quiz_right : .quiz_wrong) + " " + L.s(item.scam ? .quiz_was_scam : .quiz_was_safe))
                    .font(.title2.bold()).fixedSize(horizontal: false, vertical: true)
            }
            Text(item.why).font(.body).fixedSize(horizontal: false, vertical: true)
        }
        .cardStyle((right ? Theme.safeGreen : Theme.warnYellow).opacity(0.16))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("quizFeedback")
        .onAppear { UIAccessibility.post(notification: .announcement, argument: L.s(right ? .quiz_right : .quiz_wrong)) }
    }

    @ViewBuilder
    private var result: some View {
        VStack(spacing: 12) {
            Image(systemName: "star.fill").font(.system(size: 52)).foregroundStyle(Theme.brand).accessibilityHidden(true)
            // Simple mode: only the friendly line. Standard: the score too, and the best so far.
            if !vm.simpleMode {
                Text(L.s(.quiz_score, "\(score)", "\(Quiz.size)")).font(.largeTitle.bold()).accessibilityIdentifier("quizScore")
            }
            Text(L.s(Quiz.resultLine(score))).font(.title2).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
            if !vm.simpleMode, let best = vm.quizBest {
                Text(L.s(.quiz_best, "\(best)", "\(Quiz.size)")).font(.body).accessibilityIdentifier("quizBest")
            }
        }
        .foregroundStyle(Theme.onBrandContainer)
        .frame(maxWidth: .infinity)
        .cardStyle(Theme.brandContainer)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("quizResult")
    }
}

extension View {
    /// The app's card: 20 pt padding, 24 pt corners (Android: AppCard, Space.card / Space.corner).
    func cardStyle(_ color: Color = Theme.card) -> some View {
        self.padding(20).frame(maxWidth: .infinity, alignment: .leading).background(color, in: RoundedRectangle(cornerRadius: 24))
    }
}
