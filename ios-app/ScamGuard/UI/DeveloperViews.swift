import SwiftUI
import UIKit

// The hidden Developer view (Settings → Help & about → tap the version 7 times). For demos and debugging; English
// only. Same sections, order and wording as android-app ui/DeveloperScreens.kt. Read-only except Tools and the server
// address; nothing here is saved (except the unlocked flag) or sent.

/// The confirmation for "Reset app to first launch" (same words on Android).
let resetAppText = "This deletes all call history, settings, contact and PIN. The app will start like a new install."

struct DeveloperView: View {
    @Environment(CallViewModel.self) private var vm
    @Environment(\.dismiss) private var dismiss
    @State private var confirmReset = false
    @State private var sampleNote: String?
    @State private var copied: String?

    var body: some View {
        let _ = vm.devTick                      // the log lives outside SwiftUI: redraw when it changes
        let connection = vm.devConnectionRows()
        let models = vm.devModelRows()
        let call = vm.devLog.lastCall()
        let entries = vm.devLog.all()
        ScrollViewReader { proxy in
        List {
            Section {
                Text("For demos and debugging. Only the unlocked flag is saved; logs stay in memory and caller text is masked.")
                    .font(.subheadline).foregroundStyle(Theme.muted)
                Toggle(isOn: Binding(get: { vm.devUnlocked }, set: { on in
                    vm.setDeveloperUnlocked(on)
                    if !on { dismiss() }
                })) {
                    ToggleLabel(title: "Developer view", line: "Off hides this page and the server settings again, and forgets the logs.")
                }
                .tint(Theme.brand)
                if let copied { Text("Copied \(copied)").font(.subheadline).foregroundStyle(Theme.brand) }
            }
            .listRowBackground(Theme.card)

            Section {
                KeyValues(rows: connection)
                ServerAddress()
            } header: { DevHeader(title: "1. Connection") { copy("connection", rows(connection)) } }
            .listRowBackground(Theme.card)
            .id("connection")

            Section {
                KeyValues(rows: models)
                Button { Task { _ = await vm.checkModelUpdates(); vm.refreshDevView() } } label: {
                    Text("Check for model updates").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent).foregroundStyle(Theme.onBrand)
                if !vm.modelCheck.isEmpty { Mono(text: vm.modelCheck) }
                Button {} label: { Text("Train on my calls").frame(maxWidth: .infinity, minHeight: 44) }
                    .buttonStyle(.bordered).disabled(true)
                Text("Training on the phone comes in a later version.").font(.footnote).foregroundStyle(Theme.muted)
            } header: { DevHeader(title: "2. Models") { copy("models", rows(models)) } }
            .listRowBackground(Theme.card)
            .id("models")

            Section {
                if let call {
                    KeyValues(rows: [("call_id", call.callId), ("privacy", call.privacy), ("ended_by", call.endedBy ?? "still on")])
                    ScrollView(.horizontal) { Mono(text: DevFormat.table(call.lines), size: 12) }
                        .accessibilityIdentifier("devTable")
                    Text("End-of-call reply").font(.subheadline.weight(.semibold))
                    Mono(text: call.end.map(DevFormat.end) ?? (call.privacy == "cloud" ? "none yet" : "none (not sent at this privacy level)"))
                } else {
                    Text("No call since unlocking. Calls are kept in memory only.").font(.subheadline)
                }
            } header: {
                DevHeader(title: "3. Last call") {
                    copy("last call", vm.devDiagnostics().components(separatedBy: "3. Last call\n").last ?? "")
                }
            }
            .listRowBackground(Theme.card)
            .id("call")

            Section {
                if entries.isEmpty { Text("No requests since unlocking.").font(.subheadline) }
                ForEach(entries) { e in RawEntry(entry: e) { copy("reply", entryText(e)) } }
            } header: { DevHeader(title: "4. Raw replies (\(entries.count) of \(DevLog.capacity))", onCopy: nil) }
            .listRowBackground(Theme.card)
            .id("raw")

            Section {
                Text("Call playback speed").font(.subheadline.weight(.semibold))
                Picker("Call playback speed", selection: Binding(get: { vm.playbackSpeed }, set: { vm.setPlaybackSpeed($0) })) {
                    ForEach(PlaybackSpeed.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("speedPicker")
                Text("Scripted and pasted calls. Instant keeps a \(Playback.instantGapMs) ms gap so lines stay in order. Back to 1× when locked.")
                    .font(.footnote).foregroundStyle(Theme.muted)
                Toggle(isOn: Binding(get: { vm.devOverlay }, set: { vm.setDevOverlay($0) })) {
                    ToggleLabel(title: "Show live scores during calls", line: "A small panel at the top of the call screen. Hidden while the red warning shows.")
                }
                .tint(Theme.brand)
                ToolButton(title: "Load sample history") { vm.loadSampleHistory(); sampleNote = "Added about 60 sample calls." }
                ToolButton(title: "Delete sample history") { vm.deleteSampleHistory(); sampleNote = "Sample calls deleted (real calls kept)." }
                if let sampleNote { Text(sampleNote).font(.subheadline) }
                ToolButton(title: "Copy diagnostics") { copy("diagnostics", vm.devDiagnostics()) }
                ToolButton(title: "Reset setup only") { vm.resetOnboarding() }
                Text("Keeps the history and settings; shows \"Who will use this phone?\" again.").font(.footnote).foregroundStyle(Theme.muted)
                Button(role: .destructive) { confirmReset = true } label: {
                    Text("Reset app to first launch").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent).tint(Theme.scamRed)
                .accessibilityIdentifier("resetApp")
                .id("reset")
            } header: { DevHeader(title: "5. Tools", onCopy: nil) }
            .listRowBackground(Theme.card)
            .id("tools")
        }
        #if DEBUG
        // -demoDevScroll connection|models|call|raw|tools (screenshots); -demoDevExpand YES opens the first raw reply
        .task {
            if let id = UserDefaults.standard.string(forKey: "demoDevScroll") {
                try? await Task.sleep(nanoseconds: 400_000_000)
                proxy.scrollTo(id, anchor: .top)
            }
        }
        #endif
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle("Developer view")
        .accessibilityIdentifier("developerView")
        .sheet(isPresented: $confirmReset) {
            YesNoSheet(title: "Reset app to first launch?", text: resetAppText, yes: "Reset app", no: "Cancel",
                       yesIsDanger: true, onYes: { confirmReset = false; vm.resetApp() }, onNo: { confirmReset = false })
        }
    }

    private func rows(_ r: [(String, String)]) -> String { r.map { "\($0.0): \($0.1)" }.joined(separator: "\n") }

    private func entryText(_ e: DevEntry) -> String {
        "\(e.method) \(e.path) \(e.status) \(e.ms) ms \(DevFormat.time(e.timeMs))\nRequest:\n\(e.request.isEmpty ? "(none)" : e.request)\nReply:\n\(e.response.isEmpty ? "(none)" : e.response)"
    }

    private func copy(_ label: String, _ text: String) {
        UIPasteboard.general.string = text
        copied = label
    }
}

private struct DevHeader: View {
    let title: String
    let onCopy: (() -> Void)?

    init(title: String, onCopy: (() -> Void)?) { self.title = title; self.onCopy = onCopy }

    var body: some View {
        HStack {
            Text(title).font(.title3.weight(.semibold)).foregroundStyle(Color.primary).textCase(nil)
            Spacer()
            if let onCopy { Button("Copy", action: onCopy).font(.body).textCase(nil) }
        }
    }
}

private struct KeyValues: View {
    let rows: [(String, String)]

    var body: some View {
        ForEach(Array(rows.enumerated()), id: \.offset) { _, r in
            VStack(alignment: .leading, spacing: 2) {
                Text(r.0).font(.caption).foregroundStyle(Theme.muted)
                Mono(text: r.1)
            }
            .accessibilityElement(children: .combine)
        }
    }
}

private struct Mono: View {
    let text: String
    var size: CGFloat = 13

    var body: some View {
        Text(text).font(.system(size: size, design: .monospaced)).textSelection(.enabled)
            .fixedSize(horizontal: false, vertical: true)
    }
}

private struct ToolButton: View {
    let title: String
    let action: () -> Void
    var body: some View {
        Button(action: action) { Text(title).frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.bordered)
    }
}

private struct RawEntry: View {
    let entry: DevEntry
    let onCopy: () -> Void
    #if DEBUG
    @State private var open = UserDefaults.standard.bool(forKey: "demoDevExpand")   // screenshots
    #else
    @State private var open = false
    #endif

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button { open.toggle() } label: {
                Mono(text: "\(open ? "▾" : "▸") \(entry.method) \(entry.path)  \(entry.status)  \(entry.ms) ms  \(DevFormat.time(entry.timeMs))")
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            if open {
                Text("Request").font(.caption)
                ScrollView(.horizontal) { Mono(text: entry.request.isEmpty ? "(none)" : entry.request, size: 12) }
                Text("Reply").font(.caption)
                ScrollView(.horizontal) { Mono(text: entry.response.isEmpty ? "(none)" : entry.response, size: 12) }
                Button("Copy", action: onCopy).buttonStyle(.borderless)
            }
        }
    }
}

/// Server address: the field, presets, Save and Test connection with the full error. Developer view only.
struct ServerAddress: View {
    @Environment(CallViewModel.self) private var vm
    @State private var url = ""
    @State private var result: String?
    @State private var testing = false
    @State private var loaded = false

    static let presets = [
        ("Simulator", "http://localhost:8080/"),
        ("Simulator edge", "http://localhost:8088/api/"),
        ("Mac on Wi-Fi", "http://192.168.1.23:8080/"),
        ("HTTPS", "https://192.168.1.23:8443/api/"),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Server address").font(.subheadline.weight(.semibold))
            StatusChip(text: testing ? "Checking…" : connectionLabel(vm.connection), color: testing ? Theme.muted : connectionColor(vm.connection))
            TextField("Gateway URL", text: $url)
                .font(.system(.body, design: .monospaced))
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(10)
                .background(Theme.background, in: RoundedRectangle(cornerRadius: 10))
                .accessibilityIdentifier("devUrl")
            let encrypted = url.trimmingCharacters(in: .whitespaces).lowercased().hasPrefix("https://")
            Label(encrypted ? "Encrypted (HTTPS)" : "Not encrypted (HTTP): caller text is sent as plain text",
                  systemImage: encrypted ? "lock.fill" : "exclamationmark.triangle.fill")
                .font(.subheadline).foregroundStyle(encrypted ? Theme.safeGreen : Theme.scamRed)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Self.presets, id: \.0) { p in
                        Button(p.0) { url = p.1 }.buttonStyle(.bordered).tint(url == p.1 ? Theme.brand : .secondary)
                    }
                }
            }
            HStack(spacing: 12) {
                Button { vm.saveGatewayUrl(url); url = vm.gatewayUrl; result = "Saved." } label: {
                    Text("Save").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent).foregroundStyle(Theme.onBrand)
                .accessibilityIdentifier("devSave")
                Button {
                    testing = true
                    let u = url
                    Task { result = await vm.testConnection(u); testing = false }
                } label: { Text("Test connection").frame(maxWidth: .infinity, minHeight: 44) }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("devTest")
            }
            if let result { Mono(text: result) }
        }
        .padding(.vertical, 4)
        .onAppear { if !loaded { url = vm.gatewayUrl; loaded = true } }
    }
}

/// The live score overlay over the call screen: the newest caller line's scores, BERT, the rule and where it was
/// checked. Tap to collapse. The caller hides it while the red warning shows (DevOverlay).
struct DevScoreOverlay: View {
    let line: DevLine?
    @State private var collapsed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if collapsed {
                Text("Live scores ▸")
            } else {
                Text("Live scores · tap to hide").font(.system(size: 11, design: .monospaced)).opacity(0.7)
                ForEach(lines, id: \.self) { Text($0) }
            }
        }
        .font(.system(size: 12, design: .monospaced))
        .foregroundStyle(.white)
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Color.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 12))
        .frame(maxWidth: 420)
        .onTapGesture { collapsed.toggle() }
        .dynamicTypeSize(...DynamicTypeSize.large)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("devOverlay")
    }

    private var lines: [String] {
        guard let l = line else { return ["waiting for a caller line…"] }
        return [
            "#\(l.seq)  U \(DevFormat.score(l.urgency))  M \(DevFormat.score(l.money))  I \(DevFormat.score(l.impersonation))",
            "combined \(DevFormat.score(l.combined))  BERT \(DevFormat.score(l.bertScore)) \(l.bertStatus ?? "-")",
            "rule \(l.rule ?? "-")  live \(l.liveAlert ?? "-")  \(l.verdict ?? "-")",
            "checked: \(l.place) · \(l.latencyMs) ms",
        ]
    }
}
