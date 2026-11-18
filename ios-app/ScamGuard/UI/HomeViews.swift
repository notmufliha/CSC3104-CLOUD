import SwiftUI

// Port of android-app ui/OtherScreens.kt: Home, script picker, paste text.

struct HomeView: View {
    @Environment(CallViewModel.self) private var vm
    @Binding var path: [HomeRoute]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Simulate a call and watch the scam check work live.")
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 4)
                Button { ring("scam") } label: { wide("Simulate scam call") }
                    .buttonStyle(.borderedProminent)
                    .tourTarget(.scamCall)
                Button { ring("normal") } label: { wide("Simulate normal call") }
                    .buttonStyle(.borderedProminent)
                Button { path.append(.pick) } label: { wide("Choose a call script…") }
                    .buttonStyle(.bordered)
                    .tourTarget(.pickScript)
                Button { path.append(.paste) } label: { wide("Paste text") }
                    .buttonStyle(.bordered)
                if vm.scripts.isEmpty {
                    Text("No call scripts are bundled with this build.").foregroundStyle(Theme.scamRed)
                }
            }
            .padding(16)
        }
        .navigationTitle("ScamGuard")
    }

    private func wide(_ s: String) -> some View {
        Text(s).frame(maxWidth: .infinity, minHeight: 40)
    }

    private func ring(_ kind: String) {
        if let s = vm.randomScript(kind) { vm.ring(s) }
    }
}

struct ScriptPickerView: View {
    @Environment(CallViewModel.self) private var vm

    enum Sort: String, CaseIterable, Identifiable {
        case type = "Type", title = "Title A–Z", language = "Language", length = "Shortest"
        var id: String { rawValue }
    }

    @State private var kind: String?          // nil = all
    @State private var lang: String?
    @State private var sort: Sort = .type
    @State private var filtersOpen = false

    private var shown: [CallScript] {
        let list = vm.scripts.filter { (kind == nil || $0.kind == kind) && (lang == nil || $0.lang == lang) }
        switch sort {
        case .type: return list.sorted { a, b in (a.kind == "scam") != (b.kind == "scam") ? a.kind == "scam" : a.title < b.title }
        case .title: return list.sorted { $0.title < $1.title }
        case .language: return list.sorted { a, b in (a.lang ?? "~") != (b.lang ?? "~") ? (a.lang ?? "~") < (b.lang ?? "~") : a.title < b.title }
        case .length: return list.sorted { a, b in a.lines.count != b.lines.count ? a.lines.count < b.lines.count : a.title < b.title }
        }
    }

    var body: some View {
        List {
            Section {
                DisclosureGroup(isExpanded: $filtersOpen) {
                    Picker("Type", selection: $kind) {
                        Text("All").tag(String?.none); Text("Scam").tag(String?.some("scam")); Text("Normal").tag(String?.some("normal"))
                    }
                    .pickerStyle(.segmented)
                    Picker("Language", selection: $lang) {
                        Text("All").tag(String?.none); Text("English").tag(String?.some("en")); Text("Chinese").tag(String?.some("zh"))
                    }
                    .pickerStyle(.segmented)
                    Picker("Sort", selection: $sort) {
                        ForEach(Sort.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Filter & sort").fontWeight(.semibold)
                        // the header always says what is active, so nothing is hidden silently
                        Text(activeSummary).font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            // H's learning-loop test calls go last, in their own section (ScriptTitles): they are meant to be missed.
            let groups = ScriptTitles.sections(shown)
            Section {
                ForEach(groups.regular) { s in
                    Button { vm.ring(s) } label: { ScriptRow(script: s) }
                        .buttonStyle(.plain)
                }
            }
            if !groups.tests.isEmpty {
                Section {
                    ForEach(groups.tests) { s in
                        Button { vm.ring(s) } label: { ScriptRow(script: s) }
                            .buttonStyle(.plain)
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(ScriptTitles.testSection).font(.headline).foregroundStyle(Color.primary).textCase(nil)
                        Text(ScriptTitles.testSectionNote).font(.footnote).foregroundStyle(Color.primary.opacity(0.75)).textCase(nil)
                    }
                }
            }
        }
        .navigationTitle("Call scripts")
    }

    private var activeSummary: String {
        let parts = [kind.map { $0.prefix(1).uppercased() + $0.dropFirst() }, lang.map(Theme.langLabel), sort.rawValue].compactMap { $0 }
        return parts.joined(separator: " · ") + " · \(shown.count) of \(vm.scripts.count)"
    }
}

private struct ScriptRow: View {
    let script: CallScript

    var body: some View {
        let color = Theme.kindColor(script.kind)
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 3).fill(color).frame(width: 6)
            VStack(alignment: .leading, spacing: 4) {
                Text(Theme.kindLabel(script.kind))
                    .font(.caption2.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(color, in: RoundedRectangle(cornerRadius: 4))
                Text(script.title).fontWeight(.semibold)
                Text("\(Theme.langLabel(script.lang)) · \(script.lines.count) lines")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

struct PasteTextView: View {
    @Environment(CallViewModel.self) private var vm
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("What the caller said").font(.subheadline).foregroundStyle(.secondary)
            TextEditor(text: $text)
                .frame(minHeight: 180)
                .padding(6)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.4)))
            Button {
                vm.startPasted(text.trimmingCharacters(in: .whitespacesAndNewlines))
            } label: {
                Text("Check").frame(maxWidth: .infinity, minHeight: 36)
            }
            .buttonStyle(.borderedProminent)
            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Spacer()
        }
        .padding(16)
        .navigationTitle("Paste text")
    }
}
