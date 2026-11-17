import SwiftUI

/// Port of Android's SettingsScreen: gateway address + presets, Save, Test connection; privacy level; MobileBERT
/// status; on-device model updates; federated training (disabled here).
struct SettingsView: View {
    @Environment(CallViewModel.self) private var vm
    @State private var url = ""
    @State private var result: String?
    @State private var loaded = false

    struct Preset: Identifiable {
        let label: String
        let url: String
        var id: String { label }
    }

    static let presets = [
        Preset(label: "Simulator", url: "http://localhost:8080/"),
        Preset(label: "Simulator edge", url: "http://localhost:8088/api/"),
        Preset(label: "Mac on Wi-Fi", url: "http://192.168.1.23:8080/"),
        Preset(label: "HTTPS", url: "https://192.168.1.23:8443/api/"),
    ]

    var body: some View {
        Form {
            Section {
                TextField("Gateway URL", text: $url)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                let encrypted = url.trimmingCharacters(in: .whitespaces).lowercased().hasPrefix("https://")
                Text(encrypted ? "Encrypted (HTTPS)" : "Not encrypted: transcripts are sent as plain text")
                    .font(.caption)
                    .foregroundStyle(encrypted ? Theme.brand : Theme.scamRed)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Self.presets) { p in
                            Button(p.label) { url = p.url }.buttonStyle(.bordered)
                        }
                    }
                }
                Text("Simulator: it shares the Mac's network, so localhost is the Mac running docker compose " +
                     "(:8080 = gateway, :8088/api/ = edge). iPhone: use the Mac's Wi-Fi IP instead of 192.168.1.23 " +
                     "(on the Mac: ipconfig getifaddr en0). HTTPS (Kubernetes, demo/expose): first install " +
                     "demo/tls/ca.crt on the iPhone as a profile (Settings → General → VPN & Device Management), " +
                     "then trust it in General → About → Certificate Trust Settings.")
                    .font(.footnote).foregroundStyle(.secondary)
                HStack {
                    Button("Save") { vm.saveGatewayUrl(url); url = vm.gatewayUrl; result = "Saved" }
                        .buttonStyle(.borderedProminent)
                    Button("Test connection") {
                        result = "Testing…"
                        let u = url
                        Task { result = await vm.testConnection(u) }
                    }
                    .buttonStyle(.bordered)
                }
                if let result { Text(result).font(.subheadline) }
            } header: {
                Text("Server")
            }

            Section {
                ForEach(PrivacyLevel.allCases) { level in
                    Button { vm.setPrivacyLevel(level) } label: {
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: level == vm.privacyLevel ? "largecircle.fill.circle" : "circle")
                                .foregroundStyle(level.available ? Theme.brand : Theme.neutralGrey)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(level.title).foregroundStyle(level.available ? Color.primary : Theme.neutralGrey)
                                Text(level.explanation + (level.available ? "" : " (Not available in this version yet.)"))
                                    .font(.footnote).foregroundStyle(.secondary)
                                if level == .onDevice && !vm.mobileBertStatus.isEmpty {
                                    Text(vm.mobileBertStatus).font(.caption2).foregroundStyle(Theme.brand)
                                }
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(!level.available)
                    .accessibilityAddTraits(level == vm.privacyLevel ? [.isSelected] : [])
                }
                Text("A change applies from the next call.").font(.footnote)
            } header: {
                Text("Privacy level")
            }

            Section {
                Text("Used at privacy levels 2 and 3. Improved versions come from federated learning: phones train on " +
                     "their own calls and share only model changes, never the calls. The app checks for new versions " +
                     "when it starts (not at level 3, which makes no network traffic). Downloads come from the edge " +
                     "(/models next to /api/; :8080 is mapped to the edge's :8088).")
                    .font(.footnote).foregroundStyle(.secondary)
                Text(vm.modelStatus).font(.subheadline)
                Button("Check for model updates") { Task { _ = await vm.checkModelUpdates() } }
                if !vm.modelCheck.isEmpty { Text(vm.modelCheck).font(.caption).foregroundStyle(Theme.brand) }
            } header: {
                Text("On-device models")
            }

            Section {
                Text("Trains the downloaded models on this phone's labelled calls, then sends only the change to the " +
                     "models' numbers. Not in the iOS version yet: the upload service answers 502 on Kubernetes " +
                     "(FINAL_RESULTS.md, open problem 1).")
                    .font(.footnote).foregroundStyle(.secondary)
                Button("Train on my calls and share the update") {}
                    .disabled(true)
                Text("Coming soon").font(.caption).foregroundStyle(.secondary)
            } header: {
                Text("Help train the models")
            }
        }
        .navigationTitle("Settings")
        .onAppear {
            if !loaded { url = vm.gatewayUrl; loaded = true }
        }
    }
}
