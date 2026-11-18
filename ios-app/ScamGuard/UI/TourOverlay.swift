import SwiftUI

// The guided tour's coach marks. Same steps, wording and look as android-app ui/TourOverlay.kt; the steps live in
// Logic/Onboarding.swift (Tour).

/// Where each tour target is on screen, in window (global) coordinates; the overlay fills the window.
@Observable
@MainActor
final class TourTargets {
    var frames: [String: CGRect] = [:]
}

extension View {
    /// Marks a real element the tour can highlight.
    func tourTarget(_ target: Tour.Target) -> some View { modifier(TourTargetModifier(target: target)) }
}

private struct TourTargetModifier: ViewModifier {
    let target: Tour.Target
    @Environment(TourTargets.self) private var targets: TourTargets?

    func body(content: Content) -> some View {
        if let targets { content.background(
            GeometryReader { g in
                let frame = g.frame(in: .global)
                Color.clear
                    .onAppear { targets.frames[target.rawValue] = frame }
                    .onChange(of: frame) { _, f in targets.frames[target.rawValue] = f }
                    .onDisappear { targets.frames[target.rawValue] = nil }
            }
        ) } else { content }
    }
}

/// The coach mark: dims the screen, cuts a rounded hole around the step's element, and shows a bubble with the
/// title, one line, "2 of 7", Skip tour and Next. Taps anywhere else do nothing.
struct TourOverlay: View {
    let index: Int
    let onNext: () -> Void
    let onSkip: () -> Void

    @Environment(TourTargets.self) private var targets
    @AccessibilityFocusState private var titleFocused: Bool

    private static let pad: CGFloat = 8
    private static let corner: CGFloat = 16

    var body: some View {
        let step = Tour.steps[index]
        GeometryReader { outer in
        let insets = outer.safeAreaInsets          // read here: the inner reader ignores the safe area
        GeometryReader { geo in
            // The overlay ignores the safe area, so its own space is the window's: global frames fit as they are.
            let origin = geo.frame(in: .global).origin
            let hole = targets.frames[step.target.rawValue].map {
                $0.offsetBy(dx: -origin.x, dy: -origin.y).insetBy(dx: -Self.pad, dy: -Self.pad)
            }
            ZStack(alignment: .topLeading) {
                Dim(hole: hole, corner: Self.corner)
                    .fill(Color.black.opacity(0.62), style: FillStyle(eoFill: true))
                    .contentShape(Rectangle())
                    .onTapGesture {}                         // swallow taps: the screen underneath can't be used
                    .accessibilityHidden(true)
                if let hole {
                    RoundedRectangle(cornerRadius: Self.corner)
                        .stroke(Theme.brand, lineWidth: 3)
                        .frame(width: hole.width, height: hole.height)
                        .offset(x: hole.minX, y: hole.minY)
                        .accessibilityElement()
                        .accessibilityLabel("Highlighted: \(step.target.spoken)")
                }
                bubble(step)
                    .frame(maxWidth: min(geo.size.width - 32, 480))
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity)
                    .alignmentGuide(.top) { d in -bubbleY(hole, bubbleHeight: d.height, size: geo.size, insets: insets) }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
        }
        .ignoresSafeArea()
        }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityIdentifier("tourOverlay")
        .onAppear { titleFocused = true }
        .onChange(of: index) { _, _ in
            titleFocused = true
            UIAccessibility.post(notification: .screenChanged, argument: nil)
        }
    }

    private func bubble(_ step: Tour.Step) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(Tour.progress(index)).font(.body.weight(.semibold)).foregroundStyle(Theme.brand)
            Text(step.title).font(.title2.bold())
                .accessibilityAddTraits(.isHeader)
                .accessibilityFocused($titleFocused)
            Text(step.line).font(.body).fixedSize(horizontal: false, vertical: true)
            HStack {
                Button(action: onSkip) { Text("Skip tour").font(.body).frame(minHeight: 44).padding(.horizontal, 8) }
                    .accessibilityIdentifier("tourSkip")
                Spacer()
                Button(action: onNext) {
                    Text(Tour.nextLabel(index)).font(.body.weight(.semibold))
                        .foregroundStyle(Theme.onBrand)
                        .padding(.horizontal, 24)
                        .frame(minHeight: 48)
                        .background(Theme.brand, in: Capsule())
                }
                .accessibilityIdentifier("tourNext")
            }
            .padding(.top, 4)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        // Dark mode: a lighter card (M3 surfaceContainerHigh) and a thin outline, so it stands out from the dim.
        .background(Theme.dynamic(0xFFFBFE, 0x2B2930), in: RoundedRectangle(cornerRadius: 24))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(Theme.dynamic(0xFFFBFE, 0x938F99), lineWidth: 1))
        .shadow(color: .black.opacity(0.25), radius: 12, y: 4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("tourBubble")
    }

    /// Under the highlight when it is in the top half of the screen, above it otherwise; kept inside the safe area.
    private func bubbleY(_ hole: CGRect?, bubbleHeight h: CGFloat, size: CGSize, insets: EdgeInsets) -> CGFloat {
        let top = insets.top + 16
        let bottom = size.height - insets.bottom - 16
        let gap: CGFloat = 12
        let y: CGFloat
        if let hole {
            y = hole.midY < size.height / 2 ? hole.maxY + gap : hole.minY - gap - h
        } else {
            y = (size.height - h) / 2
        }
        return min(max(y, top), max(bottom - h, top))
    }
}

/// The dim layer: the whole screen with a rounded hole (even-odd fill).
private struct Dim: Shape {
    let hole: CGRect?
    let corner: CGFloat

    func path(in rect: CGRect) -> Path {
        var p = Path(rect)
        if let hole { p.addRoundedRect(in: hole, cornerSize: CGSize(width: corner, height: corner), style: .continuous) }
        return p
    }
}
