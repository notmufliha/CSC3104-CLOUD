import SwiftUI

// Port of android-app ui/OtherScreens.kt: Home, script picker.

struct HomeView: View {
    @Environment(CallViewModel.self) private var vm
    @Binding var path: [HomeRoute]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if vm.appStyleTip { AppStyleTipCard() }
                Text(L.s(.home_full_line))
                    .foregroundStyle(Theme.muted)
                    .padding(.bottom, 4)
                Button { ring("scam") } label: { wide(L.s(.btn_sim_scam)) }
                    .buttonStyle(.borderedProminent)
                    .tourTarget(.scamCall)
                Button { ring("normal") } label: { wide(L.s(.btn_sim_normal)) }
                    .buttonStyle(.borderedProminent)
                Button { path.append(.pick) } label: { wide(L.s(.btn_pick_script)) }
                    .buttonStyle(.bordered)
                    .tourTarget(.pickScript)
                Button { path.append(.learn) } label: { wide(L.s(.btn_learn)) }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("learnButton")
                if vm.scripts.isEmpty {
                    EmptyState(text: L.s(.pick_none), systemImage: "info.circle", compact: true)
                }
            }
            .padding(16)
        }
        .navigationTitle(L.s(.app_name_text))
    }

    private func wide(_ s: String) -> some View {
        Text(s).font(.title3.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 56)
    }

    private func ring(_ kind: String) {
        if let s = vm.randomScript(kind) { vm.ring(s) }
    }
}

struct ScriptPickerView: View {
    @Environment(CallViewModel.self) private var vm

    enum Sort: String, CaseIterable, Identifiable {
        case type, title, language, length
        var id: String { rawValue }
        var label: String {
            switch self {
            case .type: return L.s(.pick_sort_type)
            case .title: return L.s(.pick_sort_title)
            case .language: return L.s(.pick_sort_lang)
            case .length: return L.s(.pick_sort_short)
            }
        }
    }

    @State private var kind: String?          // nil = all
    @State private var lang: String?
    @State private var sort: Sort = .type
    @State private var filtersOpen = false

    /// The learning-loop test calls live in their own section, only while the Developer view is unlocked.
    private var everyday: [CallScript] { vm.scripts.filter { !DevScripts.isTestCall($0.fileName) } }
    private var testCalls: [CallScript] { vm.devUnlocked ? vm.scripts.filter { DevScripts.isTestCall($0.fileName) } : [] }

    private var shown: [CallScript] {
        let list = everyday.filter { (kind == nil || $0.kind == kind) && (lang == nil || $0.lang == lang) }
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
                    Picker(L.s(.pick_type), selection: $kind) {
                        Text(L.s(.pick_all)).tag(String?.none); Text(L.s(.pick_scam)).tag(String?.some("scam")); Text(L.s(.pick_normal)).tag(String?.some("normal"))
                    }
                    .pickerStyle(.segmented)
                    Picker(L.s(.pick_language), selection: $lang) {
                        Text(L.s(.pick_all)).tag(String?.none); Text(L.s(.pick_english)).tag(String?.some("en")); Text(L.s(.pick_chinese)).tag(String?.some("zh"))
                    }
                    .pickerStyle(.segmented)
                    Picker(L.s(.pick_sort), selection: $sort) {
                        ForEach(Sort.allCases) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L.s(.pick_filter)).fontWeight(.semibold)
                        // the header always says what is active, so nothing is hidden silently
                        Text(activeSummary).font(.caption).foregroundStyle(Theme.muted)
                    }
                }
            }
            Section {
                ForEach(shown) { s in
                    Button { vm.ring(s) } label: { ScriptRow(script: s) }
                        .buttonStyle(.plain)
                }
            }
            if !testCalls.isEmpty {
                Section {
                    ForEach(testCalls) { s in
                        Button { vm.ring(s) } label: { ScriptRow(script: s) }
                            .buttonStyle(.plain)
                    }
                } header: {
                    Text("Test calls (learning loop)").font(.headline).foregroundStyle(Color.primary).textCase(nil)
                        .accessibilityIdentifier("testCallsHeader")
                }
            }
        }
        .navigationTitle(L.s(.pick_title))
    }

    private var activeSummary: String {
        let parts = [kind.map { $0 == "scam" ? L.s(.pick_scam) : L.s(.pick_normal) }, lang.map(Theme.langLabel), sort.label].compactMap { $0 }
        return L.s(.pick_count, parts.joined(separator: " · "), "\(shown.count)", "\(everyday.count)")
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
                Text(L.s(.pick_lines, Theme.langLabel(script.lang), "\(script.lines.count)"))
                    .font(.caption).foregroundStyle(Theme.muted)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
