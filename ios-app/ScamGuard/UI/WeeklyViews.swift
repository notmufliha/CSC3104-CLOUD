import SwiftUI

// "Share weekly summary": a preview of the plain note, the system share sheet (the user picks WhatsApp, Messages, …)
// and the optional weekly reminder. Same screen, order and wording as android-app ui/WeeklyScreens.kt.

struct WeeklySummaryView: View {
    @Environment(CallViewModel.self) private var vm
    @State private var denied = false
    @State private var busy = false

    var body: some View {
        let _ = vm.historyVersion
        let text = vm.weeklySummary()
        List {
            Section {
                Text(L.s(.weekly_line)).font(.body).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
            }
            .listRowBackground(Color.clear)
            Section {
                Text(text).font(.body).fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("weeklyText")
            } header: { SettingsHeader(text: L.s(.weekly_preview)) }
            .listRowBackground(Theme.card)
            Section {
                ShareLink(item: text) {
                    Label(L.s(.btn_share), systemImage: "square.and.arrow.up")
                        .font(.title3.weight(.semibold)).foregroundStyle(Theme.onBrand)
                        .frame(maxWidth: .infinity, minHeight: 64)
                        .background(Theme.brand, in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(PressFeedbackStyle())
                .accessibilityIdentifier("weeklyShare")
            }
            .listRowBackground(Color.clear)
            Section {
                Toggle(isOn: Binding(get: { vm.weeklyReminder }, set: { on in
                    busy = true
                    denied = false
                    // Notification permission is asked only now, when turning it on.
                    Task { denied = !(await vm.setWeeklyReminder(on)); busy = false }
                })) {
                    ToggleLabel(title: L.s(.weekly_reminder), line: L.s(.weekly_reminder_line))
                }
                .tint(Theme.brand)
                .disabled(busy)
                .frame(minHeight: 64)
                .accessibilityIdentifier("weeklyReminder")
                if denied {
                    Text(L.s(.weekly_reminder_denied)).font(.body).foregroundStyle(Theme.warningInkAdaptive)
                        .fixedSize(horizontal: false, vertical: true).accessibilityIdentifier("weeklyDenied")
                }
            }
            .listRowBackground(Theme.card)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        .navigationTitle(L.s(.weekly_title))
        .accessibilityIdentifier("weekly")
    }
}

/// "Share weekly summary" (Dashboard and "For family").
struct WeeklyButton: View {
    var body: some View {
        NavigationLink(value: DashboardRoute.weekly) {
            Label(L.s(.btn_weekly), systemImage: "square.and.arrow.up")
                .font(.title3.weight(.semibold)).foregroundStyle(Theme.brand)
                .frame(maxWidth: .infinity, minHeight: 64)
                .overlay(Capsule().stroke(Theme.brand, lineWidth: 2))
                .contentShape(Capsule())
        }
        .buttonStyle(PressFeedbackStyle())
        .accessibilityIdentifier("weeklyButton")
    }
}
