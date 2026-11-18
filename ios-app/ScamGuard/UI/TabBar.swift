import SwiftUI
import UIKit

/// The bottom tab bar, drawn by the app instead of the system's, so its labels grow with the text size (the system
/// tab bar keeps them at about 10 pt). Same look as Android's NavigationBar: a pill behind the selected icon, the label
/// below. Labels follow Dynamic Type up to accessibility2 so they stay on one line; above that, a long press still
/// shows the label in the large content viewer.
struct SeniorTabBar<T: Hashable>: View {
    struct Item: Identifiable {
        let tab: T
        let title: String
        let systemImage: String
        let id: String
    }

    let items: [Item]
    @Binding var selection: T

    var body: some View {
        HStack(spacing: 4) {
            ForEach(items) { item in
                let selected = item.tab == selection
                Button { selection = item.tab } label: {
                    VStack(spacing: 4) {
                        Image(systemName: selected ? item.systemImage + ".fill" : item.systemImage)
                            .font(.system(size: 24, weight: selected ? .semibold : .regular))
                            .frame(width: 64, height: 34)
                            .background(selected ? Theme.brandContainer : .clear, in: Capsule())
                            .foregroundStyle(selected ? Theme.onBrandContainer : Theme.muted)
                        Text(item.title)
                            .font(.system(.subheadline, weight: selected ? .bold : .medium))
                            .foregroundStyle(selected ? Color.primary : Theme.muted)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .frame(maxWidth: .infinity, minHeight: 64)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(item.title)
                .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
                .accessibilityShowsLargeContentViewer {
                    Image(systemName: item.systemImage)
                    Text(item.title)
                }
                .accessibilityIdentifier(item.id)
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        .padding(.horizontal, 8)
        .padding(.top, 8)
        .padding(.bottom, 4)
        .background(Theme.card.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(Theme.outlineVariant).frame(height: 1) }
    }
}

/// True while the on-screen keyboard is up (the custom tab bar hides then, as the system one does).
@MainActor @Observable
final class KeyboardState {
    private(set) var visible = false
    private var tokens: [NSObjectProtocol] = []

    init() {
        let c = NotificationCenter.default
        tokens.append(c.addObserver(forName: UIResponder.keyboardWillShowNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.visible = true }
        })
        tokens.append(c.addObserver(forName: UIResponder.keyboardWillHideNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.visible = false }
        })
    }
}
