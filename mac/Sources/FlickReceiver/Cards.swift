import AppKit
import QuickLookThumbnailing

enum Palette {
    static let paper = NSColor(calibratedRed: 1.0, green: 0.972, blue: 0.925, alpha: 1)
    static let paperShade = NSColor(calibratedRed: 0.945, green: 0.906, blue: 0.847, alpha: 1)
    static let ink = NSColor(calibratedRed: 0.165, green: 0.141, blue: 0.125, alpha: 1)
    static let inkSoft = NSColor(calibratedRed: 0.165, green: 0.141, blue: 0.125, alpha: 0.55)
    static let ember = NSColor(calibratedRed: 1.0, green: 0.353, blue: 0.165, alpha: 1)

    static func serif(_ size: CGFloat, weight: NSFont.Weight = .regular) -> NSFont {
        let base = NSFont.systemFont(ofSize: size, weight: weight)
        if let descriptor = base.fontDescriptor.withDesign(.serif), let font = NSFont(descriptor: descriptor, size: size) {
            return font
        }
        return base
    }

    static func mono(_ size: CGFloat) -> NSFont {
        NSFont.monospacedSystemFont(ofSize: size, weight: .medium)
    }
}

/// Shows each arrival as a photo print that flies up from the bottom edge of the screen,
/// with a bloom of warm light where it crosses the edge.
final class CardPresenter {
    private var panels: [NSPanel] = []
    private let glow = EdgeGlow()

    func present(fileURL: URL, power: Double) {
        let request = QLThumbnailGenerator.Request(
            fileAt: fileURL, size: CGSize(width: 520, height: 520),
            scale: NSScreen.main?.backingScaleFactor ?? 2, representationTypes: .thumbnail)
        // Wait for the preview so the print can take the picture's own proportions.
        QLThumbnailGenerator.shared.generateBestRepresentation(for: request) { rep, _ in
            DispatchQueue.main.async {
                let card: CardView
                if let image = rep?.nsImage {
                    card = CardView(content: .picture(image), caption: fileURL.lastPathComponent, fileURL: fileURL)
                } else {
                    let icon = NSWorkspace.shared.icon(forFile: fileURL.path)
                    card = CardView(content: .icon(icon), caption: fileURL.lastPathComponent, fileURL: fileURL)
                }
                self.fly(card, power: power)
            }
        }
    }

    func present(text: String, power: Double) {
        fly(CardView(content: .text(text), caption: "On your clipboard", fileURL: nil), power: power)
    }

    private func fly(_ card: CardView, power: Double) {
        guard let screen = NSScreen.main ?? NSScreen.screens.first else { return }
        let visible = screen.visibleFrame
        let pad: CGFloat = 60
        let size = NSSize(width: card.frame.width + pad * 2, height: card.frame.height + pad * 2)

        let panel = NSPanel(contentRect: NSRect(origin: .zero, size: size),
                            styleMask: [.borderless, .nonactivatingPanel],
                            backing: .buffered, defer: false)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.level = .floating
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.isReleasedWhenClosed = false
        panel.hidesOnDeactivate = false

        let container = NSView(frame: NSRect(origin: .zero, size: size))
        card.setFrameOrigin(NSPoint(x: pad, y: pad))
        container.addSubview(card)
        panel.contentView = container

        // A harder flick lands higher on the screen; later cards fan out sideways.
        let strength = max(0.15, min(1.0, power))
        let slot = CGFloat(panels.count % 5) - 2
        let landingX = visible.midX - size.width / 2 + slot * 84 + CGFloat.random(in: -24...24)
        let landingY = visible.minY + 20 + (visible.height - size.height - 40) * CGFloat(0.10 + 0.5 * strength)
        let start = NSRect(x: landingX + CGFloat.random(in: -80...80),
                           y: screen.frame.minY - size.height, width: size.width, height: size.height)
        let end = NSRect(x: landingX, y: landingY, width: size.width, height: size.height)

        glow.bloom(on: screen, centerX: start.midX, strength: strength)

        card.frameCenterRotation = CGFloat.random(in: -22...22)
        panel.setFrame(start, display: false)
        panel.orderFrontRegardless()
        panels.append(panel)

        NSAnimationContext.runAnimationGroup { context in
            context.duration = 0.72
            context.timingFunction = CAMediaTimingFunction(controlPoints: 0.14, 1.24, 0.3, 1.0)
            context.allowsImplicitAnimation = true
            panel.animator().setFrame(end, display: true)
            card.animator().frameCenterRotation = CGFloat.random(in: -4.5...4.5)
        }

        card.onDismiss = { [weak self, weak panel] in
            guard let panel else { return }
            self?.dismiss(panel)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self, weak panel] in
            guard let panel else { return }
            self?.dismiss(panel)
        }
    }

    private func dismiss(_ panel: NSPanel) {
        guard panels.contains(where: { $0 === panel }) else { return }
        panels.removeAll { $0 === panel }
        NSAnimationContext.runAnimationGroup({ context in
            context.duration = 0.38
            context.timingFunction = CAMediaTimingFunction(name: .easeIn)
            panel.animator().alphaValue = 0
            panel.animator().setFrame(panel.frame.offsetBy(dx: 0, dy: -36), display: true)
        }, completionHandler: {
            panel.orderOut(nil)
        })
    }
}

/// A soft bloom of light along the bottom edge of the screen, where the card comes in.
final class EdgeGlow {
    private var panel: NSPanel?
    private let gradient = CAGradientLayer()
    private var hideWork: DispatchWorkItem?

    func bloom(on screen: NSScreen, centerX: CGFloat, strength: Double) {
        let width: CGFloat = 1100
        let height: CGFloat = 300 + 160 * CGFloat(strength)
        let frame = NSRect(x: centerX - width / 2, y: screen.frame.minY, width: width, height: height)

        let panel = self.panel ?? makePanel()
        self.panel = panel
        panel.setFrame(frame, display: false)
        gradient.frame = NSRect(origin: .zero, size: frame.size)
        panel.orderFrontRegardless()

        let pulse = CAKeyframeAnimation(keyPath: "opacity")
        pulse.values = [0, 0.55 + 0.4 * strength, 0]
        pulse.keyTimes = [0, 0.22, 1]
        pulse.timingFunctions = [CAMediaTimingFunction(name: .easeOut), CAMediaTimingFunction(name: .easeInEaseOut)]
        pulse.duration = 1.25
        gradient.opacity = 0
        gradient.add(pulse, forKey: "bloom")

        hideWork?.cancel()
        let work = DispatchWorkItem { [weak panel] in panel?.orderOut(nil) }
        hideWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4, execute: work)
    }

    private func makePanel() -> NSPanel {
        let panel = NSPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel],
                            backing: .buffered, defer: false)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.level = .floating
        panel.ignoresMouseEvents = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.isReleasedWhenClosed = false
        panel.hidesOnDeactivate = false

        let view = NSView()
        view.wantsLayer = true
        gradient.type = .radial
        gradient.colors = [
            Palette.ember.withAlphaComponent(0.85).cgColor,
            NSColor(calibratedRed: 1.0, green: 0.62, blue: 0.3, alpha: 0.35).cgColor,
            Palette.ember.withAlphaComponent(0).cgColor,
        ]
        gradient.locations = [0, 0.4, 1]
        gradient.startPoint = CGPoint(x: 0.5, y: 0)
        gradient.endPoint = CGPoint(x: 1, y: 1)
        gradient.opacity = 0
        view.layer?.addSublayer(gradient)
        panel.contentView = view
        return panel
    }
}

/// The print itself. Click to reveal the file in Finder, or drag it anywhere.
final class CardView: NSView, NSDraggingSource {
    enum Content {
        case picture(NSImage)
        case icon(NSImage)
        case text(String)
    }

    /// Photos are shown bare; files and text sit on a paper card with a caption.
    private let border: CGFloat
    private let captionHeight: CGFloat
    private let corner: CGFloat

    let content: Content
    let caption: String
    let fileURL: URL?
    var onDismiss: (() -> Void)?

    private let artSize: NSSize
    private var mouseDownPoint: NSPoint?
    private var dragging = false

    init(content: Content, caption: String, fileURL: URL?) {
        self.content = content
        self.caption = caption
        self.fileURL = fileURL

        switch content {
        case .picture:
            border = 0; captionHeight = 0; corner = 16
        case .icon, .text:
            border = 11; captionHeight = 46; corner = 7
        }

        switch content {
        case .picture(let image):
            // Keep the picture's proportions, within a comfortable range.
            let longest: CGFloat = 330
            let ratio = max(0.6, min(1.7, image.size.width / max(image.size.height, 1)))
            artSize = ratio >= 1
                ? NSSize(width: longest, height: (longest / ratio).rounded())
                : NSSize(width: (longest * ratio).rounded(), height: longest)
        case .icon:
            artSize = NSSize(width: 210, height: 200)
        case .text:
            artSize = NSSize(width: 270, height: 190)
        }

        let size = NSSize(width: artSize.width + border * 2,
                          height: artSize.height + border + captionHeight)
        super.init(frame: NSRect(origin: .zero, size: size))
        wantsLayer = true
        shadow = {
            let shadow = NSShadow()
            shadow.shadowColor = NSColor(calibratedRed: 0.1, green: 0.04, blue: 0, alpha: 0.5)
            shadow.shadowBlurRadius = 30
            shadow.shadowOffset = NSSize(width: 0, height: -14)
            return shadow
        }()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func draw(_ dirtyRect: NSRect) {
        let art = NSRect(x: border, y: captionHeight, width: artSize.width, height: artSize.height)

        if case .picture(let image) = content {
            NSGraphicsContext.saveGraphicsState()
            NSBezierPath(roundedRect: bounds, xRadius: corner, yRadius: corner).addClip()
            image.draw(in: aspectFill(image.size, in: art), from: .zero, operation: .sourceOver, fraction: 1)
            NSGraphicsContext.restoreGraphicsState()
            // A faint light edge so dark photos still read against a dark desktop.
            NSColor.white.withAlphaComponent(0.22).setStroke()
            let edge = NSBezierPath(roundedRect: bounds.insetBy(dx: 0.5, dy: 0.5), xRadius: corner, yRadius: corner)
            edge.lineWidth = 1
            edge.stroke()
            return
        }

        Palette.paper.setFill()
        NSBezierPath(roundedRect: bounds, xRadius: corner, yRadius: corner).fill()
        let artPath = NSBezierPath(roundedRect: art, xRadius: 3, yRadius: 3)

        switch content {
        case .picture:
            break

        case .icon(let image):
            Palette.paperShade.setFill()
            artPath.fill()
            let box = art.insetBy(dx: 44, dy: 39)
            image.draw(in: aspectFit(image.size, in: box), from: .zero, operation: .sourceOver, fraction: 1)

        case .text(let text):
            Palette.paperShade.setFill()
            artPath.fill()
            Palette.ember.setFill()
            NSBezierPath(rect: NSRect(x: art.minX, y: art.minY, width: 3, height: art.height)).fill()
            let style = NSMutableParagraphStyle()
            style.lineBreakMode = .byWordWrapping
            style.lineSpacing = 3
            text.draw(with: art.insetBy(dx: 18, dy: 14),
                      options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine],
                      attributes: [.font: Palette.serif(16), .foregroundColor: Palette.ink, .paragraphStyle: style])
        }

        let style = NSMutableParagraphStyle()
        style.alignment = .left
        style.lineBreakMode = .byTruncatingMiddle
        let captionRect = NSRect(x: border + 2, y: 13, width: artSize.width - 4, height: 20)
        caption.draw(in: captionRect, withAttributes: [
            .font: Palette.serif(14, weight: .medium), .foregroundColor: Palette.ink, .paragraphStyle: style,
        ])
    }

    private func aspectFit(_ size: NSSize, in rect: NSRect) -> NSRect {
        guard size.width > 0, size.height > 0 else { return rect }
        let scale = min(rect.width / size.width, rect.height / size.height)
        let fitted = NSSize(width: size.width * scale, height: size.height * scale)
        return NSRect(x: rect.midX - fitted.width / 2, y: rect.midY - fitted.height / 2,
                      width: fitted.width, height: fitted.height)
    }

    private func aspectFill(_ size: NSSize, in rect: NSRect) -> NSRect {
        guard size.width > 0, size.height > 0 else { return rect }
        let scale = max(rect.width / size.width, rect.height / size.height)
        let filled = NSSize(width: size.width * scale, height: size.height * scale)
        return NSRect(x: rect.midX - filled.width / 2, y: rect.midY - filled.height / 2,
                      width: filled.width, height: filled.height)
    }

    // MARK: Click and drag

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func mouseDown(with event: NSEvent) {
        mouseDownPoint = event.locationInWindow
        dragging = false
    }

    override func mouseDragged(with event: NSEvent) {
        guard !dragging, let start = mouseDownPoint, let fileURL else { return }
        let moved = hypot(event.locationInWindow.x - start.x, event.locationInWindow.y - start.y)
        guard moved > 5 else { return }
        dragging = true
        let item = NSDraggingItem(pasteboardWriter: fileURL as NSURL)
        let preview = NSImage(size: bounds.size)
        if let rep = bitmapImageRepForCachingDisplay(in: bounds) {
            cacheDisplay(in: bounds, to: rep)
            preview.addRepresentation(rep)
        }
        item.setDraggingFrame(bounds, contents: preview)
        beginDraggingSession(with: [item], event: event, source: self)
    }

    override func mouseUp(with event: NSEvent) {
        guard !dragging else { return }
        if let fileURL {
            NSWorkspace.shared.activateFileViewerSelecting([fileURL])
        }
        onDismiss?()
    }

    func draggingSession(_ session: NSDraggingSession,
                         sourceOperationMaskFor context: NSDraggingContext) -> NSDragOperation {
        [.copy, .move, .generic]
    }

    func draggingSession(_ session: NSDraggingSession, endedAt screenPoint: NSPoint, operation: NSDragOperation) {
        dragging = false
        if operation != [] { onDismiss?() }
    }
}
