import Foundation
import UserNotifications

/// Where the weekly reminder lives: the system's local notifications (no server). A protocol so tests can count how
/// often permission is asked.
protocol WeeklyNotifier: Sendable {
    /// Notifications are allowed for ScamGuard (asks nothing).
    func permitted() async -> Bool
    /// Shows the system question. Only called when the user turns the reminder on.
    func requestPermission() async -> Bool
    func scheduleWeekly()
    func cancelWeekly()
}

/// Every Sunday around 10:00, repeating; tapping it opens the app.
nonisolated struct LiveWeeklyNotifier: WeeklyNotifier {
    static let id = "weekly-summary"

    func permitted() async -> Bool {
        let s = await UNUserNotificationCenter.current().notificationSettings()
        return s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
    }

    func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
    }

    func scheduleWeekly() {
        let content = UNMutableNotificationContent()
        content.title = L.s(.notif_weekly_title)
        content.body = L.s(.notif_weekly_text)
        var when = DateComponents()
        when.weekday = 1          // Sunday
        when.hour = 10
        let request = UNNotificationRequest(identifier: Self.id, content: content,
                                            trigger: UNCalendarNotificationTrigger(dateMatching: when, repeats: true))
        UNUserNotificationCenter.current().add(request)
    }

    func cancelWeekly() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [Self.id])
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [Self.id])
    }
}
